use haaru_core::{migration::{migrate, migrate_with_observer}, properties::{self, Properties}};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static SEQUENCE: AtomicU64 = AtomicU64::new(0);
struct Fixture { root: PathBuf, source: PathBuf, destination: PathBuf }
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!("haaru-rust-migration-{}-{}-{}", std::process::id(),
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos(), SEQUENCE.fetch_add(1, Ordering::Relaxed)));
        let source = create_module(&root.join("live/haaru"));
        let destination = create_module(&root.join("stage/haaru"));
        Self { root, source, destination }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        // Every fixture is a fresh named directory under the OS temp directory.
        if self.root.starts_with(std::env::temp_dir()) && self.root.file_name().unwrap().to_string_lossy().starts_with("haaru-rust-migration-") {
            let _ = fs::remove_dir_all(&self.root);
        }
    }
}
fn create_module(path: &Path) -> PathBuf {
    fs::create_dir_all(path.join("webroot")).unwrap();
    fs::write(path.join("module.prop"), b"id=haaru\nname=Haaru\nauthor=Cekxri\nversion=0.6.0\nversionCode=8\n").unwrap(); path.to_owned()
}
fn sample() -> Properties {
    Properties::from([
        ("schema".into(), "5".into()), ("enabled".into(), "true".into()), ("boot".into(), "old-boot".into()),
        ("profile.0.country".into(), "us".into()), ("profile.0.carrier".into(), "台灣😀, A\\B:Carrier".into()),
        ("profile.0.subId".into(), "10".into()), ("profile.0.identity".into(), "iccid-sha256:original".into()),
        ("profile.0.confirmedBoot".into(), "old-boot".into()), ("profile.0.enabled".into(), "true".into()),
        ("profile.1.country".into(), "".into()), ("profile.1.carrier".into(), "Name only".into()),
        ("profile.1.subId".into(), "20".into()), ("profile.1.identity".into(), "weak:slot=1;subId=20".into()),
        ("profile.1.confirmedBoot".into(), "old-boot".into()), ("profile.1.enabled".into(), "false".into()),
    ])
}
fn save(path: &Path, values: &Properties) {
    fs::create_dir_all(path.join("data")).unwrap(); fs::write(path.join("data/state.properties"), properties::encode(values)).unwrap();
}
fn load(path: &Path) -> Properties { properties::parse(&fs::read(path.join("data/state.properties")).unwrap()).unwrap() }
fn snapshot(path: &Path) -> Vec<u8> { fs::read(path.join("data/state.properties")).unwrap() }

#[test]
fn verified_predecessor_is_discovered_by_metadata_without_a_legacy_id_literal() {
    let fixture = Fixture::new();
    let predecessor = fixture.root.join("live/previous_module");
    fs::create_dir_all(predecessor.join("webroot")).unwrap();
    fs::write(predecessor.join("module.prop"), b"id=previous_module\nname=Haaru\nauthor=Cekxri\nversion=0.6.1\n").unwrap();
    save(&predecessor, &sample()); let original = snapshot(&predecessor);
    let result = migrate(&predecessor, &fixture.destination).unwrap();
    assert_eq!(result["verified"], true);
    assert_eq!(snapshot(&predecessor), original, "Migrator never modifies the installed predecessor's settings");
    assert_eq!(load(&fixture.destination).get("profile.0.country").map(String::as_str), Some("us"));
    assert!(!predecessor.join("remove").exists(), "Only the installer may commit a verified handoff marker");
    assert!(!predecessor.join("disable").exists());
}

#[test]
fn predecessor_identity_and_target_id_must_be_unambiguous() {
    for metadata in [
        "id=previous_module\nname=Other\nauthor=Cekxri\n",
        "id=previous_module\nname=Haaru\nauthor=Other\n",
        "id=other_directory\nname=Haaru\nauthor=Cekxri\n",
        "id=../outside\nname=Haaru\nauthor=Cekxri\n",
    ] {
        let fixture = Fixture::new(); let predecessor = fixture.root.join("live/previous_module");
        fs::create_dir_all(&predecessor).unwrap(); fs::write(predecessor.join("module.prop"), metadata).unwrap();
        assert!(migrate(&predecessor, &fixture.destination).is_err());
        assert!(!fixture.destination.join("data/state.properties").exists());
    }
    let fixture = Fixture::new(); let wrong = fixture.root.join("stage/other_target");
    fs::create_dir_all(&wrong).unwrap(); fs::write(wrong.join("module.prop"), "id=other_target\nname=Haaru\nauthor=Cekxri\n").unwrap();
    assert!(migrate(&fixture.source, &wrong).is_err());
}

#[test]
fn preserves_settings_and_excludes_all_transient_boot_data() {
    let fixture = Fixture::new(); let mut original = sample();
    for key in ["targetPackages", "baseline.0.country", "pending.0.country", "last.0.country", "unrecognized"] { original.insert(key.into(), "obsolete".into()); }
    save(&fixture.source, &original); let before = snapshot(&fixture.source);
    let appearance = br#"{"style":"MATERIAL3","appearance":"DARK","palette":"MINT","glass":true,"reduceMotion":false}"#;
    fs::write(fixture.source.join("webroot/appearance.json"), appearance).unwrap();
    for name in ["runtime.json", "global-status.json", "global-shadow.json", "global-transaction.json", "leftover.tmp"] {
        fs::write(fixture.source.join("data").join(name), b"old-process-data").unwrap();
    }
    fs::write(fixture.source.join("update"), b"").unwrap(); let metadata = fs::read(fixture.destination.join("module.prop")).unwrap();
    let result = migrate(&fixture.source, &fixture.destination).unwrap();
    assert_eq!(result["verified"], true); assert_eq!(result["settingsPreserved"], true); assert_eq!(result["appearancePreserved"], true);
    assert_eq!(result["rebootRequired"], true); assert_eq!(load(&fixture.destination), sample());
    assert_eq!(snapshot(&fixture.source), before); assert_eq!(fs::read(fixture.destination.join("webroot/appearance.json")).unwrap(), appearance);
    assert_eq!(fs::read(fixture.destination.join("module.prop")).unwrap(), metadata);
    for name in ["runtime.json", "global-status.json", "global-shadow.json", "global-transaction.json", "lock", "leftover.tmp"] {
        assert!(!fixture.destination.join("data").join(name).exists());
    }
    assert!(!fixture.destination.join("update").exists()); assert!(!fixture.destination.join("remove").exists());
}

#[test]
fn repeated_updates_capture_new_values_and_disabled_marker() {
    let fixture = Fixture::new(); save(&fixture.source, &sample()); migrate(&fixture.source, &fixture.destination).unwrap();
    let mut newer = sample(); newer.insert("profile.0.country".into(), "jp".into()); newer.insert("profile.0.enabled".into(), "false".into()); save(&fixture.source, &newer);
    fs::write(fixture.source.join("disable"), b"").unwrap(); let result = migrate(&fixture.source, &fixture.destination).unwrap();
    assert_eq!(load(&fixture.destination), newer); assert_eq!(result["disabledPreserved"], true); assert!(fixture.destination.join("disable").is_file());
    fs::remove_file(fixture.source.join("disable")).unwrap(); migrate(&fixture.source, &fixture.destination).unwrap(); assert!(!fixture.destination.join("disable").exists());
}

#[test]
fn rejects_schema_and_invalid_profiles_without_partial_writes() {
    let fixture = Fixture::new(); save(&fixture.destination, &sample()); let staged = snapshot(&fixture.destination);
    for schema in ["6", "future", "05", "3", "-1"] {
        let mut values = sample(); values.insert("schema".into(), schema.into()); save(&fixture.source, &values); let before = snapshot(&fixture.source);
        assert!(migrate(&fixture.source, &fixture.destination).is_err()); assert_eq!(snapshot(&fixture.source), before); assert_eq!(snapshot(&fixture.destination), staged);
    }
    for (key, invalid) in [("profile.0.country", "ZZ"), ("profile.0.carrier", "bad\nname"), ("profile.0.enabled", "yes"),
        ("profile.0.subId", "2147483648"), ("profile.0.identity", "bad\nidentity"), ("profile.8.country", "us")] {
        let mut values = sample(); values.insert(key.into(), invalid.into()); save(&fixture.source, &values);
        assert!(migrate(&fixture.source, &fixture.destination).is_err()); assert_eq!(snapshot(&fixture.destination), staged);
    }
    let mut values = sample(); values.insert("profile.0.carrier".into(), "😀".repeat(80)); save(&fixture.source, &values);
    migrate(&fixture.source, &fixture.destination).unwrap(); assert_eq!(load(&fixture.destination)["profile.0.carrier"].chars().count(), 80);
    values.insert("profile.0.carrier".into(), "😀".repeat(81)); save(&fixture.source, &values); assert!(migrate(&fixture.source, &fixture.destination).is_err());
}

#[test]
fn rejects_appearance_duplicates_utf8_and_oversize_before_committing_state() {
    let fixture = Fixture::new(); save(&fixture.source, &sample()); save(&fixture.destination, &sample()); let before = snapshot(&fixture.destination);
    let bad = vec![br#"{"style":"other"}"#.to_vec(), br#"{"glass":true,"glass":false}"#.to_vec(), br#"{"glass":true,"\u0067lass":false}"#.to_vec(),
        vec![0xc0, 0xaf], vec![b' '; 2049]];
    for appearance in bad {
        fs::write(fixture.source.join("webroot/appearance.json"), appearance).unwrap(); assert!(migrate(&fixture.source, &fixture.destination).is_err()); assert_eq!(snapshot(&fixture.destination), before);
    }
    fs::remove_file(fixture.source.join("webroot/appearance.json")).unwrap(); fs::create_dir(fixture.destination.join("webroot/appearance.json")).unwrap();
    assert!(migrate(&fixture.source, &fixture.destination).is_err()); assert_eq!(snapshot(&fixture.destination), before);
}

#[test]
fn rolls_back_every_actual_interrupted_commit_including_absence() {
    let fixture = Fixture::new(); let mut newer = sample(); newer.insert("profile.0.country".into(), "jp".into()); save(&fixture.source, &newer); save(&fixture.destination, &sample());
    fs::write(fixture.source.join("webroot/appearance.json"), br#"{"style":"MATERIAL3"}"#).unwrap();
    fs::write(fixture.destination.join("webroot/appearance.json"), br#"{"style":"MIUIX"}"#).unwrap();
    fs::write(fixture.source.join("disable"), b"").unwrap();
    let original = snapshot(&fixture.source); let before = snapshot(&fixture.destination); let appearance = fs::read(fixture.destination.join("webroot/appearance.json")).unwrap();
    for name in ["state.properties", "appearance.json", "disable"] {
        assert!(migrate_with_observer(&fixture.source, &fixture.destination, &mut |target| {
            if target.file_name().unwrap() == name { Err("Injected post-commit interruption".into()) } else { Ok(()) }
        }).is_err());
        assert_eq!(snapshot(&fixture.destination), before); assert_eq!(fs::read(fixture.destination.join("webroot/appearance.json")).unwrap(), appearance);
        assert_eq!(snapshot(&fixture.source), original); assert!(!fixture.destination.join("disable").exists());
    }
    // A source without appearance removes the old staged file; a later failure must restore it.
    fs::remove_file(fixture.source.join("webroot/appearance.json")).unwrap();
    assert!(migrate_with_observer(&fixture.source, &fixture.destination, &mut |target| {
        if target.file_name().unwrap() == "disable" { Err("Injected failure after unlink".into()) } else { Ok(()) }
    }).is_err());
    assert_eq!(snapshot(&fixture.destination), before); assert_eq!(fs::read(fixture.destination.join("webroot/appearance.json")).unwrap(), appearance);
}

#[test]
fn removed_source_and_wrong_or_nested_modules_are_rejected() {
    let fixture = Fixture::new(); save(&fixture.source, &sample()); fs::write(fixture.source.join("remove"), b"").unwrap();
    assert!(migrate(&fixture.source, &fixture.destination).is_err()); assert!(!fixture.destination.join("data/state.properties").exists());
    fs::remove_file(fixture.source.join("remove")).unwrap(); assert!(migrate(&fixture.source, &fixture.source).is_err());
    let nested = create_module(&fixture.source.join("child/haaru")); assert!(migrate(&fixture.source, &nested).is_err());
    fs::write(fixture.destination.join("module.prop"), b"id=other\n").unwrap(); assert!(migrate(&fixture.source, &fixture.destination).is_err());
}

#[test]
fn legacy_flags_and_appearance_only_preserve_known_contract() {
    let fixture = Fixture::new(); let mut values = sample(); values.insert("schema".into(), "4".into());
    values.remove("profile.0.enabled"); values.remove("profile.1.subId"); save(&fixture.source, &values); migrate(&fixture.source, &fixture.destination).unwrap();
    let loaded = load(&fixture.destination); assert_eq!(loaded["schema"], "5"); assert_eq!(loaded["profile.0.enabled"], "true"); assert_eq!(loaded["profile.1.enabled"], "false");
    values.remove("schema"); save(&fixture.source, &values); migrate(&fixture.source, &fixture.destination).unwrap(); assert_eq!(load(&fixture.destination)["schema"], "5");
    let prefs_source = create_module(&fixture.root.join("prefs/haaru")); fs::write(prefs_source.join("webroot/appearance.json"), b"{}").unwrap();
    let result = migrate(&prefs_source, &fixture.destination).unwrap(); assert_eq!(result["appearancePreserved"], true); assert_eq!(result["settingsPreserved"], false);
    assert!(!prefs_source.join("data").exists()); assert!(!fixture.destination.join("data/state.properties").exists());
}

#[test]
fn process_local_lock_contention_and_removal_during_commit_fail_closed() {
    let fixture = Fixture::new(); save(&fixture.source, &sample());
    let mut checked_lock = false;
    migrate_with_observer(&fixture.source, &fixture.destination, &mut |_| {
        if !checked_lock { assert!(migrate(&fixture.source, &fixture.destination).is_err()); checked_lock = true; } Ok(())
    }).unwrap(); assert!(checked_lock);
    let before = snapshot(&fixture.destination);
    assert!(migrate_with_observer(&fixture.source, &fixture.destination, &mut |target| {
        if target.file_name().unwrap() == "state.properties" { fs::write(fixture.source.join("remove"), b"").unwrap(); } Ok(())
    }).is_err()); assert_eq!(snapshot(&fixture.destination), before);
}

#[test]
fn source_links_and_windows_junctions_do_not_redirect_lock() {
    let fixture = Fixture::new(); let outside = fixture.root.join("outside"); fs::create_dir(&outside).unwrap();
    #[cfg(unix)]
    std::os::unix::fs::symlink(&outside, fixture.source.join("data")).unwrap();
    #[cfg(windows)]
    {
        // cmd builtins treat forward slashes in Rust PathBuf text as switches.
        let link = fixture.source.join("data").to_string_lossy().replace('/', "\\");
        let target = outside.to_string_lossy().replace('/', "\\");
        let result = std::process::Command::new("cmd").args(["/c", "mklink", "/J"]).arg(link).arg(target).output().unwrap();
        assert!(result.status.success(), "Could not create Windows junction fixture: {} {}", String::from_utf8_lossy(&result.stdout), String::from_utf8_lossy(&result.stderr));
    }
    assert!(migrate(&fixture.source, &fixture.destination).is_err()); assert!(!outside.join("lock").exists()); assert!(!fixture.destination.join("data/state.properties").exists());
}
