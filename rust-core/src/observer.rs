//! Low-cost module-file observer for the KSU card; it never reads Binder or writes SIM settings.
use crate::card::{self, Lifecycle, Snapshot};
use crate::migration::{checked, optional, SourceLock};
use crate::properties;
use serde_json::{json, Value};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const VERSION: &str = env!("CARGO_PKG_VERSION");
static SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Default)]
pub struct Tracker { key: Option<String>, since_ms: u64 }

fn now_ms() -> Result<u64, String> {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|time| time.as_millis() as u64).map_err(|error| error.to_string())
}
fn present(path: &Path) -> Result<bool, String> {
    checked(path)?;
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(true),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error.to_string()),
    }
}
fn fields(text: &str) -> Result<(String, String), String> {
    if text.contains('\0') { return Err("Invalid module metadata".into()); }
    let mut id = None; let mut version = None;
    for line in text.lines() {
        if let Some((key, value)) = line.split_once('=') {
            match key.trim() {
                "id" if id.replace(value.trim().to_owned()).is_some() => return Err("Duplicate module ID".into()),
                "version" if version.replace(value.trim().to_owned()).is_some() => return Err("Duplicate module version".into()),
                _ => {},
            }
        }
    }
    Ok((id.ok_or("Missing module ID")?, version.ok_or("Missing module version")?))
}
fn metadata(module: &Path) -> Result<Vec<u8>, String> {
    let bytes = optional(&module.join("module.prop"), 16384)?.ok_or("Module is absent")?;
    let text = std::str::from_utf8(&bytes).map_err(|_| "Module metadata is not UTF-8")?;
    let (id, version) = fields(text)?;
    if id != "haaru" || version != VERSION { return Err("Observer does not own this module generation".into()); }
    Ok(bytes)
}
fn stage(module: &Path) -> Result<PathBuf, String> {
    let modules = module.parent().ok_or("Missing modules directory")?;
    let adb = modules.parent().ok_or("Missing module parent")?;
    Ok(adb.join("modules_update").join("haaru"))
}
fn may_write(module: &Path) -> Result<bool, String> {
    if !present(&module.join("module.prop"))? { return Ok(false); }
    if present(&module.join("update"))? || present(&stage(module)?)? { return Ok(false); }
    Ok(true)
}

/// Preserve all UTF-8 metadata except the single description value.
pub fn render_metadata(original: &str, description: &str) -> Result<String, String> {
    if description.contains(['\0', '\n', '\r']) || description.len() > 1024 { return Err("Invalid card description".into()); }
    let mut output = String::new(); let mut found = false;
    for line in original.split_inclusive('\n') {
        let body = line.trim_end_matches(['\r', '\n']);
        let is_description = body.split_once('=').map(|(key, _)| key.trim() == "description").unwrap_or(false);
        if is_description {
            if found { return Err("Duplicate description field".into()); }
            found = true;
            output.push_str("description="); output.push_str(description);
            if line.ends_with("\r\n") { output.push_str("\r\n"); }
            else if line.ends_with('\n') { output.push('\n'); }
        } else { output.push_str(line); }
    }
    if !found {
        if !output.is_empty() && !output.ends_with('\n') { output.push('\n'); }
        output.push_str("description="); output.push_str(description); output.push('\n');
    }
    if output.len() > 16384 { return Err("Module metadata exceeds size limit".into()); }
    Ok(output)
}

/// Atomically update only our current-generation card. No missing directory is recreated.
pub fn write_card(module: &Path, description: &str) -> Result<bool, String> {
    write_card_with_observer(module, description, &mut || Ok(()))
}
#[doc(hidden)]
pub fn write_card_with_observer(module: &Path, description: &str, before_commit: &mut dyn FnMut() -> Result<(), String>) -> Result<bool, String> {
    checked(module)?;
    if !may_write(module)? { return Ok(false); }
    let data = module.join("data"); checked(&data)?;
    if !data.is_dir() { return Err("Module data directory is absent".into()); }
    let _lock = SourceLock::acquire(&data.join("card-meta.lock"))?;
    if !may_write(module)? { return Ok(false); }
    let prior = metadata(module)?;
    let next = render_metadata(std::str::from_utf8(&prior).map_err(|error| error.to_string())?, description)?;
    if prior == next.as_bytes() { return Ok(false); }
    let temporary = module.join(format!(".haaru-card-{}.{}.tmp", std::process::id(), SEQUENCE.fetch_add(1, Ordering::Relaxed)));
    checked(&temporary)?;
    let result = (|| {
        let mut options = OpenOptions::new(); options.create_new(true).write(true);
        #[cfg(unix)]
        { use std::os::unix::fs::OpenOptionsExt; options.mode(0o644).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC); }
        let mut file = options.open(&temporary).map_err(|error| error.to_string())?;
        file.write_all(next.as_bytes()).and_then(|_| file.sync_all()).map_err(|error| error.to_string())?;
        drop(file);
        before_commit()?;
        // KSU creates staging before it installs new metadata. Never replace a
        // changed header or write through a pending update with an old process.
        if !may_write(module)? || metadata(module)? != prior { return Ok(false); }
        checked(&module.join("module.prop"))?;
        fs::rename(&temporary, module.join("module.prop")).map_err(|error| error.to_string())?;
        #[cfg(unix)]
        { let directory = OpenOptions::new().read(true).open(module).map_err(|error| error.to_string())?;
          directory.sync_all().map_err(|error| error.to_string())?; }
        Ok(true)
    })();
    let _ = fs::remove_file(&temporary);
    result
}

fn saved_plan(bytes: Option<&[u8]>) -> Result<(bool, Vec<u8>), String> {
    let Some(bytes) = bytes else { return Ok((false, Vec::new())); };
    let state = properties::parse(bytes)?;
    if let Some(schema) = state.get("schema") {
        if schema != "4" && schema != "5" { return Err("Unknown saved settings schema".into()); }
    }
    let legacy_enabled = state.get("enabled").map(String::as_str).unwrap_or("false");
    if !matches!(legacy_enabled, "true" | "false") { return Err("Invalid saved enabled state".into()); }
    let mut saved = false; let mut enabled = Vec::new();
    for slot in 0u8..8 {
        let prefix = format!("profile.{slot}.");
        if !state.contains_key(&(prefix.clone() + "country")) && !state.contains_key(&(prefix.clone() + "carrier")) { continue; }
        saved = true;
        let active = state.get(&(prefix + "enabled")).map(String::as_str).unwrap_or(legacy_enabled);
        if !matches!(active, "true" | "false") { return Err("Invalid saved SIM enabled state".into()); }
        if active == "true" { enabled.push(slot); }
    }
    Ok((saved, enabled))
}

pub fn refresh(module: &Path, boot: &str, now: u64, tracker: &mut Tracker) -> Result<Value, String> {
    checked(module)?; metadata(module)?;
    let updating = present(&module.join("update"))? || present(&stage(module)?)?;
    let lifecycle = if updating { Lifecycle::Updating }
        else if present(&module.join("remove"))? { Lifecycle::Removing }
        else if present(&module.join("disable"))? { Lifecycle::Disabled }
        else { Lifecycle::Active };
    let data = module.join("data"); checked(&data)?;
    if !data.is_dir() { return Err("Module data directory is absent".into()); }
    // Coordinate the state/runtime snapshot with the original Java FileChannel writer.
    let _snapshot_lock = SourceLock::acquire(&data.join("lock"))?;
    let mut bad = false;
    let state = optional(&data.join("state.properties"), 65536).unwrap_or_else(|_| { bad = true; None });
    let (saved, enabled) = saved_plan(state.as_deref()).unwrap_or_else(|_| { bad = true; (false, Vec::new()) });
    let runtime = optional(&data.join("runtime.json"), 65536).unwrap_or_else(|_| { bad = true; None });
    let ack = optional(&data.join("global-status.json"), 65536).unwrap_or_else(|_| { bad = true; None });
    #[cfg(target_os = "android")]
    if !enabled.is_empty() {
        if let Some(ack) = ack.as_deref().and_then(|bytes| crate::model::parse_json_strict(bytes).ok()) {
            if let Some(pid) = ack.get("phonePid").and_then(Value::as_u64).filter(|pid| *pid > 0) {
                // A positive PID in a recent file alone is insufficient after
                // phone exits or its PID is reused by an unrelated process.
                let name = fs::read(format!("/proc/{pid}/cmdline")).ok();
                if name.as_deref().and_then(|bytes| bytes.split(|byte| *byte == 0).next()) != Some(b"com.android.phone".as_slice()) {
                    bad = true;
                }
            }
        }
    }
    let hash = runtime.as_deref().map(crate::model::fnv1a64).unwrap_or(0);
    let key = format!("{boot}:{hash}:{enabled:?}");
    if tracker.key.as_deref() != Some(&key) { tracker.key = Some(key); tracker.since_ms = now; }
    let snapshot = Snapshot { boot_id: boot, runtime_bytes: runtime.as_deref(), ack_bytes: ack.as_deref(),
        enabled_slots: &enabled, has_saved_profiles: saved, lifecycle, now_ms: now,
        waiting_since_ms: tracker.since_ms, data_error: bad };
    let state = card::classify(&snapshot);
    let description = card::description(state);
    let wrote = write_card(module, description)?;
    drop(_snapshot_lock);
    Ok(json!({"ok":true,"state":format!("{state:?}"),"description":description,"written":wrote,
        "exit":matches!(lifecycle,Lifecycle::Disabled|Lifecycle::Removing|Lifecycle::Updating)}))
}

pub fn watch(module: &Path) -> Result<(), String> {
    checked(module)?; metadata(module)?;
    let data = module.join("data"); checked(&data)?;
    if !data.is_dir() { return Err("Module data directory is absent".into()); }
    let _session = SourceLock::acquire(&data.join("card-observer.lock"))?;
    let mut tracker = Tracker::default();
    loop {
        if !present(&module.join("module.prop"))? || !may_write(module)? { return Ok(()); }
        let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id").map_err(|error| error.to_string())?;
        let now = now_ms()?;
        match refresh(module, boot.trim(), now, &mut tracker) {
            Ok(result) if result.get("exit").and_then(Value::as_bool) == Some(true) => return Ok(()),
            Ok(_) => {},
            Err(_) => {
                // A busy control transaction is retried; missing/new module generations exit.
                if metadata(module).is_err() || !may_write(module)? { return Ok(()); }
            }
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

