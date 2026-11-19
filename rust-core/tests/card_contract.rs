// SPDX-License-Identifier: Apache-2.0
//! Public card status is derived from a complete, current engine acknowledgement.
use haaru_core::card::{classify, description, CardState, Lifecycle, Snapshot};
use haaru_core::model::fnv1a64;
use serde_json::{json, Value};

const BOOT: &str = "card-boot";
const NOW: u64 = 1_000_000;

fn runtime() -> Value {
    json!({
        "protocol": 2, "global": true, "engine": "system-global-volatile",
        "bootId": BOOT, "enabled": true, "readOnly": false,
        "profiles": [
            {"slot": 0, "subId": 11, "country": "us", "carrierName": "T-Mobile USA"},
            {"slot": 1, "subId": 12, "country": "jp", "carrierName": "NTT DOCOMO"}
        ]
    })
}

fn ack_for(bytes: &[u8]) -> Value {
    json!({
        "ok": true, "initialized": true, "applied": true,
        "hooks": 14, "requiredHooks":14, "coverageComplete":true, "supportsCountry":true, "supportsCarrierName":true, "sdk": 36, "bootId": BOOT,
        "configHash": fnv1a64(bytes).to_string(),
        "timestampMs": NOW - 1, "phonePid": 12345, "error": ""
    })
}

struct Fixture {
    runtime: Option<Vec<u8>>,
    ack: Option<Vec<u8>>,
    slots: Vec<u8>,
    saved: bool,
    lifecycle: Lifecycle,
    now: u64,
    since: u64,
    data_error: bool,
}

impl Fixture {
    fn live() -> Self {
        let runtime = serde_json::to_vec(&runtime()).unwrap();
        let ack = serde_json::to_vec(&ack_for(&runtime)).unwrap();
        Self {runtime: Some(runtime), ack: Some(ack), slots: vec![0, 1], saved: true,
            lifecycle: Lifecycle::Active, now: NOW, since: NOW - 1_000, data_error: false}
    }
    fn snapshot(&self) -> Snapshot<'_> {
        Snapshot {boot_id: BOOT, runtime_bytes: self.runtime.as_deref(), ack_bytes: self.ack.as_deref(),
            enabled_slots: &self.slots, has_saved_profiles: self.saved, lifecycle: self.lifecycle,
            now_ms: self.now, waiting_since_ms: self.since, data_error: self.data_error}
    }
    fn state(&self) -> CardState { classify(&self.snapshot()) }
    fn change_ack(&mut self, update: impl FnOnce(&mut Value)) {
        let mut ack: Value = serde_json::from_slice(self.ack.as_ref().unwrap()).unwrap();
        update(&mut ack);
        self.ack = Some(serde_json::to_vec(&ack).unwrap());
    }
    fn change_runtime(&mut self, update: impl FnOnce(&mut Value), confirm: bool) {
        let mut runtime: Value = serde_json::from_slice(self.runtime.as_ref().unwrap()).unwrap();
        update(&mut runtime);
        let bytes = serde_json::to_vec(&runtime).unwrap();
        if confirm { self.ack = Some(serde_json::to_vec(&ack_for(&bytes)).unwrap()); }
        self.runtime = Some(bytes);
    }
}

#[test]
fn running_requires_the_current_complete_two_sim_plan() {
    let live = Fixture::live();
    assert_eq!(live.state(), CardState::Running);
    let mut reversed = Fixture::live();
    reversed.change_runtime(|runtime| runtime["profiles"].as_array_mut().unwrap().reverse(), true);
    assert_eq!(reversed.state(), CardState::Running, "Slot coverage is independent of list order");
    reversed.slots.reverse();
    assert_eq!(reversed.state(), CardState::Running, "Durable selection order also does not change slot coverage");
    let mut country_only = Fixture::live();
    country_only.change_runtime(|runtime| runtime["profiles"][0]["carrierName"] = json!(""), true);
    assert_eq!(country_only.state(), CardState::Running);
    let mut name_only = Fixture::live();
    name_only.change_runtime(|runtime| runtime["profiles"][1]["country"] = json!(""), true);
    assert_eq!(name_only.state(), CardState::Running);
}

#[test]
fn a_fresh_success_cannot_hide_missing_or_empty_sim_overrides() {
    for runtime in [
        json!([]),
        json!([{"slot": 0, "subId": 11, "country": "us", "carrierName": "Carrier"}]),
        json!([
            {"slot": 0, "subId": 11, "country": "us", "carrierName": "Carrier"},
            {"slot": 1, "subId": 12, "country": "", "carrierName": ""}
        ]),
    ] {
        let mut fixture = Fixture::live();
        fixture.change_runtime(|value| value["profiles"] = runtime, true);
        fixture.since = NOW - 31_000;
        assert_eq!(fixture.state(), CardState::Error, "ACK success never confirms only a subset of selected SIMs");
    }
}

#[test]
fn confirmation_matches_exactly_the_selected_logical_slots() {
    let mut extra = Fixture::live();
    extra.change_runtime(|runtime| runtime["profiles"].as_array_mut().unwrap().push(
        json!({"slot": 2, "subId": 13, "country": "gb", "carrierName": "Vodafone"})
    ), true);
    assert_eq!(extra.state(), CardState::Error, "An unexpected extra SIM cannot become a confirmed plan");
    let mut duplicate = Fixture::live();
    duplicate.slots = vec![0, 0];
    assert_eq!(duplicate.state(), CardState::Error, "Duplicate durable slot identifiers are not a real two-SIM selection");
    let mut invalid = Fixture::live();
    invalid.slots = vec![8];
    assert_eq!(invalid.state(), CardState::Error, "Slots outside the supported logical range are rejected");
}

#[test]
fn fresh_metadata_cannot_confirm_structurally_invalid_runtime() {
    for (key, value) in [
        ("protocol", json!(1)), ("protocol", json!("2")), ("global", json!(false)),
        ("engine", json!("selected-app")), ("bootId", json!("previous-boot")),
        ("enabled", json!(false)), ("readOnly", json!(true)),
    ] {
        let mut fixture = Fixture::live();
        fixture.change_runtime(|runtime| runtime[key] = value, true);
        fixture.since = NOW - 31_000;
        assert_eq!(fixture.state(), CardState::Error, "Invalid runtime field {key}");
    }
}

#[test]
fn unset_and_stopped_depend_on_durable_choices_not_old_ack() {
    let mut fixture = Fixture::live();
    fixture.slots.clear();
    fixture.saved = false;
    assert_eq!(fixture.state(), CardState::Unset, "An old successful ACK does not enable a new installation");
    fixture.saved = true;
    assert_eq!(fixture.state(), CardState::Stopped, "Saved but disabled choices remain stopped");
    fixture.runtime = None;
    fixture.ack = None;
    assert_eq!(fixture.state(), CardState::Stopped);
    fixture.saved = false;
    assert_eq!(fixture.state(), CardState::Unset);
}

#[test]
fn saving_while_no_ack_or_new_hash_is_pending_has_a_bounded_wait() {
    let mut missing_runtime = Fixture::live();
    missing_runtime.runtime = None;
    missing_runtime.ack = None;
    assert_eq!(missing_runtime.state(), CardState::Waiting);
    missing_runtime.since = NOW - 30_000;
    assert_eq!(missing_runtime.state(), CardState::Error, "The warmup deadline is thirty seconds, inclusive");

    let mut missing = Fixture::live();
    missing.ack = None;
    assert_eq!(missing.state(), CardState::Waiting);
    missing.since = NOW - 31_000;
    assert_eq!(missing.state(), CardState::Error, "A missing engine cannot remain waiting forever");

    let mut mismatch = Fixture::live();
    mismatch.change_ack(|ack| ack["configHash"] = json!("previous-plan"));
    assert_eq!(mismatch.state(), CardState::Waiting);
    mismatch.since = NOW - 31_000;
    assert_eq!(mismatch.state(), CardState::Error);

    let mut pending = Fixture::live();
    pending.change_ack(|ack| ack["applied"] = json!(false));
    assert_eq!(pending.state(), CardState::Waiting);
    pending.since = NOW - 31_000;
    assert_eq!(pending.state(), CardState::Error);
}

#[test]
fn fresh_ack_time_boundaries_match_the_existing_controller_contract() {
    for time in [NOW - 10_000, NOW, NOW + 1_000] {
        let mut fixture = Fixture::live();
        fixture.change_ack(|ack| ack["timestampMs"] = json!(time));
        assert_eq!(fixture.state(), CardState::Running, "Permitted ACK time {time}");
    }
    for time in [0, NOW - 10_001, NOW + 1_001] {
        let mut fixture = Fixture::live();
        fixture.change_ack(|ack| ack["timestampMs"] = json!(time));
        assert_eq!(fixture.state(), CardState::Error, "Invalid or stale ACK time {time}");
    }
}

#[test]
fn stale_boot_dead_generation_and_incomplete_hooks_never_run() {
    for (key, value) in [
        ("bootId", json!("previous-boot")), ("phonePid", json!(0)),
        ("phonePid", json!(-1)), ("phonePid", json!(4_294_967_296u64)),
        ("hooks", json!(13)), ("sdk", json!(28)),
    ] {
        let mut fixture = Fixture::live();
        fixture.change_ack(|ack| ack[key] = value);
        assert_eq!(fixture.state(), CardState::Error, "Invalid ACK field {key}");
    }
    let mut supported = Fixture::live();
    supported.change_ack(|ack| {ack["hooks"] = json!(3); ack["requiredHooks"] = json!(3); ack["sdk"] = json!(29);});
    assert_eq!(supported.state(), CardState::Running, "Complete discovered coverage works across interface generations");
}

#[test]
fn explicit_errors_win_over_contradictory_success_flags() {
    let mut fixture = Fixture::live();
    fixture.change_ack(|ack| ack["error"] = json!("Phone engine disconnected"));
    assert_eq!(fixture.state(), CardState::Error, "A successful-looking ACK with an error cannot become running");
    fixture.change_ack(|ack| {ack["ok"] = json!(false); ack["initialized"] = json!(false); ack["applied"] = json!(false);});
    assert_eq!(fixture.state(), CardState::Error);
    for key in ["ok", "initialized"] {
        let mut contradiction = Fixture::live();
        contradiction.change_ack(|ack| ack[key] = json!(false));
        assert_eq!(contradiction.state(), CardState::Error, "Applied metadata cannot contradict {key}");
    }
    let mut invalid_data = Fixture::live();
    invalid_data.data_error = true;
    assert_eq!(invalid_data.state(), CardState::Error, "Unreadable durable settings cannot be hidden by an old success");
}

#[test]
fn ack_fields_use_actual_boolean_integer_and_string_types() {
    for (key, value) in [
        ("ok", json!("true")), ("initialized", json!(1)), ("applied", json!(null)),
        ("hooks", json!(14.5)), ("sdk", json!("36")), ("phonePid", json!("12345")),
        ("timestampMs", json!("999999")), ("bootId", json!(99)),
        ("configHash", json!(123)), ("error", json!(false)),
    ] {
        let mut fixture = Fixture::live();
        fixture.change_ack(|ack| ack[key] = value);
        assert_eq!(fixture.state(), CardState::Error, "Malformed ACK field {key}");
    }
    for key in ["ok", "initialized", "applied", "hooks", "requiredHooks", "coverageComplete", "supportsCountry", "supportsCarrierName", "sdk", "phonePid", "timestampMs", "bootId", "configHash"] {
        let mut fixture = Fixture::live();
        fixture.change_ack(|ack| {ack.as_object_mut().unwrap().remove(key);});
        assert_eq!(fixture.state(), CardState::Error, "Missing ACK field {key}");
    }
}

#[test]
fn discovered_reader_and_requested_field_coverage_must_be_complete() {
    let mut two_hooks = Fixture::live();
    two_hooks.change_ack(|ack| { ack["hooks"] = json!(2); ack["requiredHooks"] = json!(2); });
    assert_eq!(two_hooks.state(), CardState::Error, "Attach plus both reader categories require at least three hooks");
    for (key, value) in [
        ("requiredHooks", json!(0)), ("requiredHooks", json!(1)), ("requiredHooks", json!(13)),
        ("requiredHooks", json!(14.5)), ("coverageComplete", json!(false)), ("coverageComplete", json!("true")),
        ("supportsCountry", json!(false)), ("supportsCarrierName", json!(false)),
    ] {
        let mut fixture = Fixture::live(); fixture.change_ack(|ack| ack[key] = value);
        assert_eq!(fixture.state(), CardState::Error, "Unproven reader or requested field {key} must not report Running");
    }
    for count in [3, 8, 21] {
        let mut fixture = Fixture::live(); fixture.change_ack(|ack| { ack["hooks"] = json!(count); ack["requiredHooks"] = json!(count); });
        assert_eq!(fixture.state(), CardState::Running, "Discovery rather than an Android version table determines hook count");
    }
}

#[test]
fn corrupt_ack_and_runtime_documents_do_not_become_waiting_or_running() {
    for bytes in [
        b"".to_vec(), b"null".to_vec(), b"[]".to_vec(), b"{broken".to_vec(),
        b"{\"ok\":true,\"ok\":false}".to_vec(),
        vec![0xed, 0xa0, 0x80], vec![b' '; 65_537],
    ] {
        let mut fixture = Fixture::live();
        fixture.ack = Some(bytes.clone());
        assert_eq!(fixture.state(), CardState::Error);
        fixture = Fixture::live();
        fixture.runtime = Some(bytes);
        assert_eq!(fixture.state(), CardState::Error);
    }
}

#[test]
fn hash_confirmation_uses_the_original_runtime_bytes() {
    let mut fixture = Fixture::live();
    let pretty = serde_json::to_vec_pretty(&runtime()).unwrap();
    assert_ne!(fnv1a64(fixture.runtime.as_ref().unwrap()), fnv1a64(&pretty));
    fixture.runtime = Some(pretty);
    assert_eq!(fixture.state(), CardState::Waiting, "An equivalent reserialised object is a different exact-byte configuration");
    fixture.ack = Some(serde_json::to_vec(&ack_for(fixture.runtime.as_ref().unwrap())).unwrap());
    assert_eq!(fixture.state(), CardState::Running);
}

#[test]
fn lifecycle_markers_override_even_a_fresh_success() {
    let mut fixture = Fixture::live();
    fixture.lifecycle = Lifecycle::Updating;
    assert_eq!(fixture.state(), CardState::Waiting);
    fixture.lifecycle = Lifecycle::Disabled;
    assert_eq!(fixture.state(), CardState::Stopped);
    fixture.lifecycle = Lifecycle::Removing;
    assert_eq!(fixture.state(), CardState::Stopped);
}

#[test]
fn descriptions_preserve_the_users_exact_unicode_and_short_blurb() {
    for (state, expected) in [
        (CardState::Running, "運行~中Nya^ ܸ⩌⩊⩌ ܸ^🍧 城堡之下的SIM國度.咖啡廳☕"),
        (CardState::Unset, "未設定~哦Nya^ ܸ⩌⩊⩌ ܸ^🍞 城堡之下的SIM國度.咖啡廳☕"),
        (CardState::Waiting, "暖機~中Nya^ ܸ⩌⩊⩌ ܸ^☕ 城堡之下的SIM國度.咖啡廳☕"),
        (CardState::Stopped, "休息~中Nya^ ܸ⩌⩊⩌ ܸ^🍞 城堡之下的SIM國度.咖啡廳☕"),
        (CardState::Error, "爆炸~了！Nya^ ܸ⩌⩊⩌ ܸ^🍛 城堡之下的SIM國度.咖啡廳☕"),
    ] {
        let actual = description(state);
        assert_eq!(actual, expected);
        assert!(actual.len() <= 256, "Card text stays short");
        assert!(!actual.contains(['\0', '\n', '\r']), "Metadata must remain a single UTF-8 property value");
        assert!(!actual.contains("\\u"), "Emoji and combining marks must be stored as real UTF-8");
    }
}
