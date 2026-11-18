//! Module-local upgrade snapshots. This module never opens a telephony service.
use crate::properties::{self, Properties};
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{atomic::{AtomicU64, Ordering}, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const ISO_COUNTRIES: &str = "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW";
static LOCKED_PATHS: OnceLock<Mutex<BTreeSet<PathBuf>>> = OnceLock::new();
static TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);

fn control(value: char) -> bool { value <= '\u{1f}' || value == '\u{7f}' }
fn error(context: &str, failure: std::io::Error) -> String { format!("{context}: {failure}") }

fn exists(path: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(true), Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(failure) => Err(error("Cannot inspect module path", failure)),
    }
}

pub(crate) fn checked(path: &Path) -> Result<(), String> {
    for current in path.ancestors() {
        match fs::symlink_metadata(current) {
            Ok(metadata) => {
                #[cfg(windows)]
                let reparse = { use std::os::windows::fs::MetadataExt; metadata.file_attributes() & 0x400 != 0 };
                #[cfg(not(windows))]
                let reparse = false;
                if metadata.file_type().is_symlink() || reparse { return Err("Links and reparse points are not allowed in module paths".into()); }
            }
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {},
            Err(failure) => return Err(error("Cannot inspect module path", failure)),
        }
    }
    Ok(())
}

fn open_read(path: &Path) -> Result<File, String> {
    checked(path)?;
    let mut options = OpenOptions::new(); options.read(true);
    #[cfg(unix)]
    { use std::os::unix::fs::OpenOptionsExt; options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK); }
    options.open(path).map_err(|failure| error("Cannot read settings file", failure))
}

pub(crate) fn optional(path: &Path, limit: usize) -> Result<Option<Vec<u8>>, String> {
    checked(path)?;
    if !exists(path)? { return Ok(None); }
    let file = open_read(path)?;
    let metadata = file.metadata().map_err(|failure| error("Cannot inspect settings file", failure))?;
    if !metadata.is_file() || metadata.len() > limit as u64 { return Err("Invalid or oversized settings file".into()); }
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1).read_to_end(&mut bytes).map_err(|failure| error("Cannot read settings file", failure))?;
    if bytes.len() > limit { return Err("Settings file exceeds size limit".into()); }
    Ok(Some(bytes))
}

fn module(path: &Path, installed: bool) -> Result<PathBuf, String> {
    let path = if path.is_absolute() { path.to_owned() } else { std::env::current_dir().map_err(|failure| error("Cannot locate module", failure))?.join(path) };
    checked(&path)?;
    if !fs::metadata(&path).map_err(|failure| error("Module directory is absent", failure))?.is_dir() { return Err("Module directory is absent".into()); }
    let path = fs::canonicalize(&path).map_err(|failure| error("Cannot resolve module directory", failure))?;
    checked(&path)?;
    let bytes = optional(&path.join("module.prop"), 16384)?.ok_or("Missing module metadata")?;
    let properties = properties::parse(&bytes)?;
    let id = properties.get("id").ok_or("Missing module id")?;
    if id.is_empty() || id.len() > 64 || matches!(id.as_str(), "." | "..")
        || !id.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
        || path.file_name().and_then(|name| name.to_str()) != Some(id.as_str()) {
        return Err("Module id does not match a safe directory basename".into());
    }
    if installed {
        if id != "haaru" && (properties.get("name").map(String::as_str) != Some("Haaru")
                || properties.get("author").map(String::as_str) != Some("Cekxri")) {
            return Err("Installed module is not a verified Haaru predecessor".into());
        }
    } else if id != "haaru" { return Err("Staged module must use the Haaru id".into()); }
    #[cfg(target_os = "android")]
    {
        let expected = if installed { "modules" } else { "modules_update" };
        if path.parent().and_then(Path::file_name).and_then(|name| name.to_str()) != Some(expected)
            || path.parent().and_then(Path::parent).and_then(Path::file_name).and_then(|name| name.to_str()) != Some("adb") {
            return Err("Module migration is outside the installed/staged module directories".into());
        }
    }
    Ok(path)
}

fn refuse_removal(source: &Path) -> Result<(), String> {
    let path = source.join("remove"); checked(&path)?;
    if exists(&path)? { return Err("Installed module is scheduled for removal; settings will not be restored".into()); }
    Ok(())
}

pub(crate) struct SourceLock { file: File, path: PathBuf, token: platform_lock::Token }
impl SourceLock {
    pub(crate) fn acquire(path: &Path) -> Result<Self, String> {
        checked(path)?;
        let registry = LOCKED_PATHS.get_or_init(|| Mutex::new(BTreeSet::new()));
        {
            let mut entries = registry.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            if !entries.insert(path.to_owned()) { return Err("Another settings operation is in progress".into()); }
        }
        let result = (|| {
            let mut options = OpenOptions::new(); options.write(true).create(true).truncate(false);
            #[cfg(unix)]
            { use std::os::unix::fs::OpenOptionsExt; options.mode(0o600).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK); }
            let file = options.open(path).map_err(|failure| error("Cannot open installed settings lock", failure))?;
            if !file.metadata().map_err(|failure| error("Cannot inspect installed settings lock", failure))?.is_file() { return Err("Invalid installed settings lock".into()); }
            let token = platform_lock::acquire(&file)?;
            Ok(Self { file, path: path.to_owned(), token })
        })();
        if result.is_err() { registry.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).remove(path); }
        result
    }
}
impl Drop for SourceLock {
    fn drop(&mut self) {
        platform_lock::release(&self.file, &mut self.token);
        if let Some(registry) = LOCKED_PATHS.get() { registry.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).remove(&self.path); }
    }
}

#[cfg(unix)]
mod platform_lock {
    use std::fs::File;
    use std::os::fd::AsRawFd;
    pub type Token = ();
    pub fn acquire(file: &File) -> Result<Token, String> {
        // Java FileChannel uses POSIX record locks. BSD flock locks do not coordinate with it.
        let mut record: libc::flock = unsafe { std::mem::zeroed() };
        record.l_type = libc::F_WRLCK as _; record.l_whence = libc::SEEK_SET as _; record.l_len = 0;
        if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLK, &record) } == -1 {
            return Err(format!("Another settings operation is in progress or the lock is unavailable: {}", std::io::Error::last_os_error()));
        }
        Ok(())
    }
    pub fn release(file: &File, _: &mut Token) {
        let mut record: libc::flock = unsafe { std::mem::zeroed() };
        record.l_type = libc::F_UNLCK as _; record.l_whence = libc::SEEK_SET as _; record.l_len = 0;
        unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLK, &record); }
    }
}

#[cfg(windows)]
mod platform_lock {
    use std::ffi::c_void;
    use std::fs::File;
    use std::os::windows::io::AsRawHandle;
    #[repr(C)]
    pub struct Overlapped { internal: usize, internal_high: usize, offset: u32, offset_high: u32, event: *mut c_void }
    pub type Token = Box<Overlapped>;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn LockFileEx(file: *mut c_void, flags: u32, reserved: u32, bytes_low: u32, bytes_high: u32, overlapped: *mut Overlapped) -> i32;
        fn UnlockFileEx(file: *mut c_void, reserved: u32, bytes_low: u32, bytes_high: u32, overlapped: *mut Overlapped) -> i32;
    }
    pub fn acquire(file: &File) -> Result<Token, String> {
        let mut token = Box::new(Overlapped { internal: 0, internal_high: 0, offset: 0, offset_high: 0, event: std::ptr::null_mut() });
        // Exclusive + fail immediately, covering the same range as Java FileChannel.lock().
        if unsafe { LockFileEx(file.as_raw_handle(), 3, 0, u32::MAX, u32::MAX, &mut *token) } == 0 {
            return Err(format!("Another settings operation is in progress or the lock is unavailable: {}", std::io::Error::last_os_error()));
        }
        Ok(token)
    }
    pub fn release(file: &File, token: &mut Token) { unsafe { UnlockFileEx(file.as_raw_handle(), 0, u32::MAX, u32::MAX, &mut **token); } }
}

#[cfg(not(any(unix, windows)))]
mod platform_lock {
    use std::fs::File;
    pub type Token = ();
    pub fn acquire(_: &File) -> Result<Token, String> { Err("Settings migration is unsupported on this platform".into()) }
    pub fn release(_: &File, _: &mut Token) {}
}

fn boolean(value: &str) -> Result<&str, String> { if matches!(value, "true" | "false") { Ok(value) } else { Err("Invalid saved enabled flag".into()) } }
fn copy_text(input: &Properties, output: &mut Properties, key: &str, limit: usize) -> Result<(), String> {
    if let Some(value) = input.get(key) {
        if value.encode_utf16().count() > limit || value.chars().any(control) { return Err(format!("Invalid saved field: {key}")); }
        output.insert(key.into(), value.clone());
    }
    Ok(())
}

fn validated_state(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let input = properties::parse(bytes)?;
    if let Some(schema) = input.get("schema") { if !matches!(schema.as_str(), "4" | "5") { return Err("Unsupported saved settings schema; use a compatible module version".into()); } }
    for key in input.keys().filter(|key| key.starts_with("profile.")) {
        let parts: Vec<_> = key.splitn(3, '.').collect();
        if parts.len() != 3 || parts[1].len() != 1 || !matches!(parts[1].as_bytes()[0], b'0'..=b'7') || parts[2].is_empty() { return Err("Invalid saved SIM slot".into()); }
    }
    let mut output = Properties::new(); output.insert("schema".into(), "5".into());
    let enabled = boolean(input.get("enabled").map(String::as_str).unwrap_or("false"))?;
    output.insert("enabled".into(), enabled.into()); copy_text(&input, &mut output, "boot", 128)?;
    for slot in 0..8 {
        let prefix = format!("profile.{slot}.");
        if !input.contains_key(&(prefix.clone() + "country")) && !input.contains_key(&(prefix.clone() + "carrier")) { continue; }
        let country = input.get(&(prefix.clone() + "country")).map(String::as_str).unwrap_or("");
        if !country.is_empty() && (country.len() != 2 || !country.bytes().all(|byte| byte.is_ascii_alphabetic())
                || !ISO_COUNTRIES.split_ascii_whitespace().any(|iso| iso == country.to_ascii_uppercase())) { return Err(format!("Invalid saved country in SIM slot {slot}")); }
        let carrier = input.get(&(prefix.clone() + "carrier")).map(String::as_str).unwrap_or("");
        if carrier.chars().count() > 80 || carrier.chars().any(control) { return Err(format!("Invalid saved operator name in SIM slot {slot}")); }
        output.insert(prefix.clone() + "country", country.to_ascii_lowercase()); output.insert(prefix.clone() + "carrier", carrier.into());
        let mut active = boolean(input.get(&(prefix.clone() + "enabled")).map(String::as_str).unwrap_or(enabled))?;
        if let Some(id) = input.get(&(prefix.clone() + "subId")) {
            if id.is_empty() || id.len() > 10 || !id.bytes().all(|byte| byte.is_ascii_digit()) || id.parse::<i32>().is_err() { return Err("Invalid saved subscription ID".into()); }
            output.insert(prefix.clone() + "subId", id.clone());
        } else { active = "false"; }
        copy_text(&input, &mut output, &(prefix.clone() + "identity"), 256)?;
        if input.get(&(prefix.clone() + "identity")).map(String::is_empty).unwrap_or(true) { active = "false"; }
        copy_text(&input, &mut output, &(prefix.clone() + "confirmedBoot"), 128)?;
        output.insert(prefix + "enabled", active.into());
    }
    let bytes = properties::encode(&output); if bytes.len() > 65536 { return Err("Migrated settings are too large".into()); } Ok(bytes)
}

fn validate_appearance(bytes: &[u8]) -> Result<(), String> {
    let value = crate::model::parse_json_strict(bytes).map_err(|_| "Invalid appearance JSON".to_string())?;
    let object = value.as_object().ok_or("Appearance must be a JSON object")?;
    for (key, value) in object {
        let valid = match key.as_str() {
            "style" => matches!(value.as_str(), Some("MIUIX" | "MATERIAL3")),
            "appearance" => matches!(value.as_str(), Some("SYSTEM" | "LIGHT" | "DARK")),
            "palette" => matches!(value.as_str(), Some("MINT" | "BLUE" | "VIOLET" | "ROSE")),
            "glass" | "reduceMotion" => value.is_boolean(), _ => false,
        };
        if !valid { return Err(format!("Unknown or invalid appearance field: {key}")); }
    }
    Ok(())
}

fn sync_parent(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    if let Some(parent) = path.parent() { if exists(parent)? { File::open(parent).and_then(|file| file.sync_all()).map_err(|failure| error("Cannot synchronize staged settings directory", failure))?; } }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

fn write(target: &Path, bytes: Option<&[u8]>) -> Result<(), String> {
    checked(target)?;
    let parent = target.parent().ok_or("Missing staged settings parent")?; checked(parent)?;
    let Some(bytes) = bytes else {
        match fs::remove_file(target) { Ok(()) => {}, Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(failure) => return Err(error("Cannot remove absent staged setting", failure)) }
        return sync_parent(target);
    };
    fs::create_dir_all(parent).map_err(|failure| error("Cannot prepare staged settings directory", failure))?; checked(parent)?;
    let nonce = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|_| "Cannot determine temporary file nonce")?.as_nanos();
    let sequence = TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let temporary = parent.join(format!(".{}.{}.{}.{}.tmp", target.file_name().and_then(|name| name.to_str()).ok_or("Invalid staged filename")?, std::process::id(), nonce, sequence));
    checked(&temporary)?;
    let result = (|| {
        let mut options = OpenOptions::new(); options.write(true).create_new(true);
        #[cfg(unix)]
        { use std::os::unix::fs::OpenOptionsExt; options.mode(0o600).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC); }
        let mut file = options.open(&temporary).map_err(|failure| error("Cannot create staged settings temporary file", failure))?;
        file.write_all(bytes).and_then(|_| file.sync_all()).map_err(|failure| error("Cannot persist staged settings", failure))?;
        drop(file); checked(target)?;
        fs::rename(&temporary, target).map_err(|failure| error("Cannot replace staged settings", failure))?;
        sync_parent(target)?;
        if optional(target, 65536)?.as_deref() != Some(bytes) { return Err("Staged settings verification failed".into()); }
        Ok(())
    })();
    let _ = fs::remove_file(&temporary); result
}

/// Capture settings while holding the same whole-file lock as the existing Java helper.
pub fn migrate(source: &Path, destination: &Path) -> Result<Value, String> { migrate_with_observer(source, destination, &mut |_| Ok(())) }

/// The observer is used by host contracts to inject interruption after actual file commits.
#[doc(hidden)]
pub fn migrate_with_observer(source: &Path, destination: &Path, observer: &mut dyn FnMut(&Path) -> Result<(), String>) -> Result<Value, String> {
    let source = module(source, true)?; let destination = module(destination, false)?;
    if source == destination || source.starts_with(&destination) || destination.starts_with(&source) { return Err("Upgrade requires distinct, separate Haaru module directories".into()); }
    refuse_removal(&source)?;
    let data = source.join("data"); checked(&data)?;
    let _lock = if exists(&data)? {
        if !fs::metadata(&data).map_err(|failure| error("Cannot inspect installed settings directory", failure))?.is_dir() { return Err("Invalid installed settings directory".into()); }
        Some(SourceLock::acquire(&data.join("lock"))?)
    } else { None };
    refuse_removal(&source)?;
    let state = optional(&source.join("data/state.properties"), 65536)?.map(|bytes| validated_state(&bytes)).transpose()?;
    let appearance = optional(&source.join("webroot/appearance.json"), 2048)?;
    if let Some(bytes) = &appearance { validate_appearance(bytes)?; }
    let disabled_path = source.join("disable"); checked(&disabled_path)?; let disabled = exists(&disabled_path)?;
    if disabled && !fs::metadata(&disabled_path).map_err(|failure| error("Cannot inspect installed disable marker", failure))?.is_file() { return Err("Invalid installed disable marker".into()); }
    let changes = [
        (destination.join("data/state.properties"), state.clone()),
        (destination.join("webroot/appearance.json"), appearance.clone()),
        (destination.join("disable"), if disabled { Some(Vec::new()) } else { None }),
    ];
    let mut backups = Vec::new();
    for (target, _) in &changes {
        checked(target)?; let parent = target.parent().ok_or("Missing staged settings parent")?; checked(parent)?;
        if exists(parent)? && !fs::metadata(parent).map_err(|failure| error("Cannot inspect staged settings directory", failure))?.is_dir() { return Err("Invalid staged settings directory".into()); }
        backups.push(optional(target, 65536)?);
    }
    refuse_removal(&source)?;
    for (index, (target, bytes)) in changes.iter().enumerate() {
        let result = refuse_removal(&source).and_then(|_| write(target, bytes.as_deref())).and_then(|_| observer(target));
        if let Err(mut failure) = result {
            for rollback in (0..=index).rev() {
                if let Err(restore_failure) = write(&changes[rollback].0, backups[rollback].as_deref()) {
                    failure.push_str(&format!("; staged rollback failed: {restore_failure}"));
                }
            }
            return Err(failure);
        }
    }
    Ok(json!({"ok": true, "verified": true, "verification": "module-files", "settingsPreserved": state.is_some(),
        "appearancePreserved": appearance.is_some(), "disabledPreserved": disabled, "rebootRequired": true}))
}
