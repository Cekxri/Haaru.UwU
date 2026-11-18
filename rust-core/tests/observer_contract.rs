// SPDX-License-Identifier: Apache-2.0
//! Execute the real metadata writer and file-only observer against an isolated module tree.
use haaru_core::card::{description, CardState};
use haaru_core::model::fnv1a64;
use haaru_core::observer::{refresh, render_metadata, write_card, write_card_with_observer, Tracker};
use serde_json::{json, Value};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

const BOOT: &str = "observer-boot";
const NOW: u64 = 1_000_000;
static NEXT: AtomicU64 = AtomicU64::new(0);

fn metadata() -> String {
    format!("# Haaru metadata\r\nid=haaru\r\nname=Haaru\r\nversion={}\r\nversionCode=9\r\nauthor=Cekxri ☕\r\nupdateJson=https://example.invalid/Haaru.UwU/update.json\r\ncustom=小雨と珈琲 🌧️\r\ndescription=initial rainy ☔\r\n", env!("CARGO_PKG_VERSION"))
}

struct Fixture {root: PathBuf, module: PathBuf, stage: PathBuf}
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!("haaru-observer-contract-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        let module = root.join("modules").join("haaru");
        let stage = root.join("modules_update").join("haaru");
        fs::create_dir_all(module.join("data")).unwrap();
        fs::write(module.join("module.prop"), metadata()).unwrap();
        Self {root, module, stage}
    }
    fn text(&self) -> String {fs::read_to_string(self.module.join("module.prop")).unwrap()}
    fn put(&self, relative: &str, bytes: impl AsRef<[u8]>) {
        let path = self.module.join(relative);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }
    fn configured(&self) -> Vec<u8> {
        self.put("data/state.properties", b"schema=5\nenabled=true\nprofile.0.country=us\nprofile.0.carrier=T-Mobile USA\nprofile.0.subId=11\nprofile.0.identity=identity-zero\nprofile.0.enabled=true\nprofile.1.country=jp\nprofile.1.carrier=NTT DOCOMO\nprofile.1.subId=12\nprofile.1.identity=identity-one\nprofile.1.enabled=true\n");
        let bytes = serde_json::to_vec(&json!({
            "protocol":2,"global":true,"engine":"system-global-volatile","bootId":BOOT,
            "enabled":true,"readOnly":false,"profiles":[
                {"slot":0,"subId":11,"country":"us","carrierName":"T-Mobile USA"},
                {"slot":1,"subId":12,"country":"jp","carrierName":"NTT DOCOMO"}
            ]
        })).unwrap();
        self.put("data/runtime.json", &bytes);
        bytes
    }
    fn ack(&self, runtime: &[u8], time: u64) {
        self.put("data/global-status.json", serde_json::to_vec(&json!({
            "ok":true,"initialized":true,"applied":true,"hooks":14,"requiredHooks":14,"coverageComplete":true,"supportsCountry":true,"supportsCarrierName":true,"sdk":36,"bootId":BOOT,
            "configHash":fnv1a64(runtime).to_string(),"timestampMs":time,"phonePid":12345,"error":""
        })).unwrap());
    }
    fn no_temporary_metadata(&self) {
        assert!(!fs::read_dir(&self.module).unwrap().any(|entry| entry.unwrap().file_name().to_string_lossy().starts_with(".haaru-card-")));
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        // Only remove this explicitly created test directory, never a computed source/module path.
        let expected = std::env::temp_dir();
        if self.root.starts_with(&expected)
            && self.root.file_name().is_some_and(|name| name.to_string_lossy().starts_with("haaru-observer-contract-")) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }
}

fn state(result: &Value) -> &str {result.get("state").and_then(Value::as_str).unwrap()}

#[test]
fn renders_only_description_and_preserves_every_utf8_header_byte() {
    let original = metadata();
    let text = description(CardState::Running);
    let expected = original.replace("description=initial rainy ☔", &format!("description={text}"));
    let rendered = render_metadata(&original, text).unwrap();
    assert_eq!(rendered.as_bytes(), expected.as_bytes());
    assert!(rendered.contains("author=Cekxri ☕\r\n"));
    assert!(rendered.contains("custom=小雨と珈琲 🌧️\r\n"));
    assert!(!rendered.contains("\\u"), "The KSU card consumes actual UTF-8, not Java Properties escape text");
    let lf = original.replace("\r\n", "\n");
    assert_eq!(render_metadata(&lf, text).unwrap(), lf.replace("description=initial rainy ☔", &format!("description={text}")));
    let missing = format!("id=haaru\nname=Haaru\nversion={}", env!("CARGO_PKG_VERSION"));
    assert_eq!(render_metadata(&missing, text).unwrap(), format!("{missing}\ndescription={text}\n"));
}

#[test]
fn identical_description_does_not_commit_or_change_metadata() {
    let fixture = Fixture::new();
    let text = description(CardState::Unset);
    assert!(write_card(&fixture.module, text).unwrap());
    let before = fixture.text();
    let modified = fs::metadata(fixture.module.join("module.prop")).unwrap().modified().unwrap();
    assert!(!write_card_with_observer(&fixture.module, text, &mut || Err("A no-op must not reach the commit hook".into())).unwrap());
    assert_eq!(fixture.text(), before);
    assert_eq!(fs::metadata(fixture.module.join("module.prop")).unwrap().modified().unwrap(), modified);
    fixture.no_temporary_metadata();
}

#[test]
fn description_injection_and_ambiguous_metadata_are_rejected() {
    let fixture = Fixture::new();
    let before = fixture.text();
    for text in ["bad\nversion=9.9.9", "bad\rname=other", "bad\0value"] {
        assert!(render_metadata(&before, text).is_err());
        assert!(write_card(&fixture.module, text).is_err());
        assert_eq!(fixture.text(), before);
    }
    assert!(write_card(&fixture.module, &"A".repeat(1025)).is_err());
    let duplicate = format!("{before}description=second\n");
    assert!(render_metadata(&duplicate, description(CardState::Error)).is_err());
    fixture.put("module.prop", &duplicate);
    assert!(write_card(&fixture.module, description(CardState::Error)).is_err());
    assert_eq!(fixture.text(), duplicate);
    fixture.no_temporary_metadata();
}

#[test]
fn wrong_generation_and_wrong_id_are_never_rewritten() {
    for (key, old, new) in [("id", "haaru", "other_module"), ("version", env!("CARGO_PKG_VERSION"), "0.6.0"),
                            ("version", env!("CARGO_PKG_VERSION"), "9.9.9")] {
        let fixture = Fixture::new();
        let wrong = fixture.text().replace(&format!("{key}={old}"), &format!("{key}={new}"));
        fixture.put("module.prop", &wrong);
        assert!(write_card(&fixture.module, description(CardState::Running)).is_err());
        assert_eq!(fixture.text(), wrong);
        fixture.no_temporary_metadata();
    }
}

#[test]
fn pending_update_and_stage_guard_leave_the_new_header_untouched() {
    for marker in ["update", "stage"] {
        let fixture = Fixture::new();
        if marker == "update" {fixture.put("update", b"");}
        else {fs::create_dir_all(&fixture.stage).unwrap();}
        let before = fixture.text();
        assert!(!write_card(&fixture.module, description(CardState::Running)).unwrap());
        assert_eq!(fixture.text(), before);
        fixture.no_temporary_metadata();
    }
}

#[test]
fn staging_and_new_version_during_commit_do_not_get_downgraded() {
    let fixture = Fixture::new();
    let next = fixture.text().replace(&format!("version={}", env!("CARGO_PKG_VERSION")), "version=9.9.9")
        .replace("versionCode=9", "versionCode=999");
    let result = write_card_with_observer(&fixture.module, description(CardState::Running), &mut || {
        fs::create_dir_all(&fixture.stage).map_err(|error| error.to_string())?;
        fs::write(fixture.module.join("module.prop"), &next).map_err(|error| error.to_string())?;
        Ok(())
    }).unwrap();
    assert!(!result);
    assert_eq!(fixture.text(), next, "An old observer cannot restore captured old version/name/author fields");
    fixture.no_temporary_metadata();
}

#[test]
fn same_generation_external_edits_and_commit_errors_are_preserved() {
    let fixture = Fixture::new();
    let changed = fixture.text().replace("custom=小雨と珈琲 🌧️", "custom=keep my new value 🥐");
    let result = write_card_with_observer(&fixture.module, description(CardState::Running), &mut || {
        fs::write(fixture.module.join("module.prop"), &changed).map_err(|error| error.to_string())?;
        Ok(())
    }).unwrap();
    assert!(!result);
    assert_eq!(fixture.text(), changed);
    assert!(write_card_with_observer(&fixture.module, description(CardState::Error), &mut || Err("Simulated metadata commit failure".into())).is_err());
    assert_eq!(fixture.text(), changed);
    fixture.no_temporary_metadata();
}

#[test]
fn no_missing_module_or_data_directory_is_recreated() {
    let fixture = Fixture::new();
    let absent = fixture.root.join("gone").join("haaru");
    assert!(!write_card(&absent, description(CardState::Running)).unwrap());
    assert!(!absent.exists());
    fs::remove_dir(fixture.module.join("data")).unwrap();
    let before = fixture.text();
    assert!(write_card(&fixture.module, description(CardState::Running)).is_err());
    assert!(refresh(&fixture.module, BOOT, NOW, &mut Tracker::default()).is_err());
    assert_eq!(fixture.text(), before);
    assert!(!fixture.module.join("data").exists());
}

#[test]
fn symlink_or_windows_junction_cannot_redirect_a_metadata_lock() {
    let fixture = Fixture::new();
    let outside = fixture.root.join("outside-data");
    fs::create_dir_all(&outside).unwrap();
    fs::write(outside.join("sentinel"), b"untouched outside fixture").unwrap();
    let data = fixture.module.join("data");
    fs::remove_dir(&data).unwrap();
    #[cfg(unix)]
    std::os::unix::fs::symlink(&outside, &data).unwrap();
    #[cfg(windows)]
    {
        let link = data.to_string_lossy().replace('/', "\\");
        let target = outside.to_string_lossy().replace('/', "\\");
        let result = std::process::Command::new("cmd").args(["/c", "mklink", "/J", &link, &target]).output().unwrap();
        assert!(result.status.success(), "Could not create junction fixture: {}", String::from_utf8_lossy(&result.stderr));
    }
    let before = fixture.text();
    assert!(write_card(&fixture.module, description(CardState::Running)).is_err());
    assert_eq!(fixture.text(), before);
    assert_eq!(fs::read(outside.join("sentinel")).unwrap(), b"untouched outside fixture");
    assert!(!outside.join("card-meta.lock").exists());
}

#[test]
fn fresh_running_card_changes_no_runtime_settings_or_ack_bytes() {
    let fixture = Fixture::new();
    let runtime = fixture.configured();
    fixture.ack(&runtime, NOW - 1);
    let state_bytes = fs::read(fixture.module.join("data/state.properties")).unwrap();
    let ack_bytes = fs::read(fixture.module.join("data/global-status.json")).unwrap();
    let mut tracker = Tracker::default();
    let result = refresh(&fixture.module, BOOT, NOW, &mut tracker).unwrap();
    assert_eq!(state(&result), "Running");
    assert_eq!(result["description"], description(CardState::Running));
    assert!(fixture.text().contains(&format!("description={}\r\n", description(CardState::Running))));
    assert_eq!(fs::read(fixture.module.join("data/runtime.json")).unwrap(), runtime);
    assert_eq!(fs::read(fixture.module.join("data/state.properties")).unwrap(), state_bytes);
    assert_eq!(fs::read(fixture.module.join("data/global-status.json")).unwrap(), ack_bytes);
    assert_eq!(refresh(&fixture.module, BOOT, NOW + 1, &mut tracker).unwrap()["written"], false);
}

#[test]
fn missing_ack_warmup_times_out_without_runtime_mtime_feedback() {
    let fixture = Fixture::new();
    let mut runtime = fixture.configured();
    let mut tracker = Tracker::default();
    assert_eq!(state(&refresh(&fixture.module, BOOT, NOW, &mut tracker).unwrap()), "Waiting");
    for elapsed in [10_000, 20_000] {
        fixture.put("data/runtime.json", &runtime); // Same data, newer filesystem mtime.
        assert_eq!(state(&refresh(&fixture.module, BOOT, NOW + elapsed, &mut tracker).unwrap()), "Waiting");
    }
    fixture.put("data/runtime.json", &runtime);
    assert_eq!(state(&refresh(&fixture.module, BOOT, NOW + 30_000, &mut tracker).unwrap()), "Error");
    assert!(fixture.text().contains(description(CardState::Error)));
    let mut changed: Value = serde_json::from_slice(&runtime).unwrap();
    changed["profiles"][0]["country"] = json!("gb");
    runtime = serde_json::to_vec(&changed).unwrap();
    fixture.put("data/runtime.json", &runtime);
    assert_eq!(state(&refresh(&fixture.module, BOOT, NOW + 31_000, &mut tracker).unwrap()), "Waiting", "A genuinely new configuration starts its own bounded warmup");
    assert_eq!(state(&refresh(&fixture.module, BOOT, NOW + 61_000, &mut tracker).unwrap()), "Error");
}

#[test]
fn fresh_install_is_unset_and_corrupt_saved_settings_are_errors() {
    let fixture = Fixture::new();
    let mut tracker = Tracker::default();
    assert_eq!(state(&refresh(&fixture.module, BOOT, NOW, &mut tracker).unwrap()), "Unset");
    assert!(!fixture.module.join("data/state.properties").exists());
    assert!(!fixture.module.join("data/runtime.json").exists());
    fixture.put("data/state.properties", b"schema=99\nenabled=true\n");
    let before = fs::read(fixture.module.join("data/state.properties")).unwrap();
    assert_eq!(state(&refresh(&fixture.module, BOOT, NOW + 1, &mut tracker).unwrap()), "Error");
    assert_eq!(fs::read(fixture.module.join("data/state.properties")).unwrap(), before);
}

#[test]
fn stopped_and_updating_states_never_republish_running_metadata() {
    for marker in ["disable", "remove", "update", "stage"] {
        let fixture = Fixture::new();
        let runtime = fixture.configured();
        fixture.ack(&runtime, NOW - 1);
        if marker == "stage" {fs::create_dir_all(&fixture.stage).unwrap();}
        else {fixture.put(marker, b"");}
        let before = fixture.text();
        let result = refresh(&fixture.module, BOOT, NOW, &mut Tracker::default()).unwrap();
        if matches!(marker, "update" | "stage") {
            assert_eq!(state(&result), "Waiting");
            assert_eq!(fixture.text(), before, "Pending installation owns its metadata");
        } else {
            assert_eq!(state(&result), "Stopped");
            assert!(fixture.text().contains(description(CardState::Stopped)));
        }
        assert_eq!(result["exit"], true);
        assert_eq!(fs::read(fixture.module.join("data/runtime.json")).unwrap(), runtime);
    }
}
