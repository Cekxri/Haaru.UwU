//! Pure classification of the most recently observed KSU card state.
//! Description writes are deliberately separate from runtime configuration.
use crate::model;
use serde_json::Value;

const STARTUP_GRACE_MS: u64 = 30_000;
const ACK_MAX_AGE_MS: u64 = 10_000;
const ACK_MAX_FUTURE_MS: u64 = 1_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CardState { Running, Unset, Waiting, Stopped, Error }

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Lifecycle { Active, Disabled, Removing, Updating }

#[derive(Clone, Copy, Debug)]
pub struct Snapshot<'a> {
    pub boot_id: &'a str,
    pub runtime_bytes: Option<&'a [u8]>,
    pub ack_bytes: Option<&'a [u8]>,
    pub enabled_slots: &'a [u8],
    pub has_saved_profiles: bool,
    pub lifecycle: Lifecycle,
    pub now_ms: u64,
    pub waiting_since_ms: u64,
    pub data_error: bool,
}

fn waiting(snapshot: &Snapshot<'_>) -> CardState {
    if snapshot.now_ms.saturating_sub(snapshot.waiting_since_ms) < STARTUP_GRACE_MS {
        CardState::Waiting
    } else { CardState::Error }
}

fn integer(value: &Value, key: &str) -> Option<u64> {
    value.get(key)?.as_u64().filter(|number| *number <= i32::MAX as u64)
}

fn bounded_text<'a>(value: &'a Value, key: &str, limit: usize) -> Option<&'a str> {
    let text = value.get(key)?.as_str()?;
    (text.encode_utf16().count() <= limit).then_some(text)
}

/// A successful file save or restoration ACK alone never means active overrides.
pub fn classify(snapshot: &Snapshot<'_>) -> CardState {
    match snapshot.lifecycle {
        Lifecycle::Updating => return CardState::Waiting,
        Lifecycle::Disabled | Lifecycle::Removing => return CardState::Stopped,
        Lifecycle::Active => {},
    }
    if snapshot.data_error { return CardState::Error; }
    if snapshot.enabled_slots.is_empty() {
        return if snapshot.has_saved_profiles { CardState::Stopped } else { CardState::Unset };
    }
    if !snapshot.has_saved_profiles || snapshot.boot_id.is_empty() { return CardState::Error; }

    let mut requested = [false; 8];
    for &slot in snapshot.enabled_slots {
        if slot > 7 || requested[slot as usize] { return CardState::Error; }
        requested[slot as usize] = true;
    }

    let Some(runtime_bytes) = snapshot.runtime_bytes else { return waiting(snapshot); };
    let Ok(runtime) = model::parse_json_strict(runtime_bytes) else { return CardState::Error; };
    let Some(runtime_boot) = bounded_text(&runtime, "bootId", 128).filter(|boot| !boot.is_empty()) else { return CardState::Error; };
    // An old-boot file is legitimate during startup, but its shape must still be valid.
    if !model::validate_runtime(runtime_bytes, runtime_boot) { return CardState::Error; }
    if runtime_boot != snapshot.boot_id { return waiting(snapshot); }
    if runtime.get("enabled").and_then(Value::as_bool) != Some(true)
        || runtime.get("readOnly").and_then(Value::as_bool) != Some(false) {
        return CardState::Error;
    }
    let Some(profiles) = runtime.get("profiles").and_then(Value::as_array) else { return CardState::Error; };
    if profiles.is_empty() { return CardState::Error; }
    let mut accepted = [false; 8];
    for profile in profiles {
        let Some(slot) = integer(profile, "slot").filter(|slot| *slot < 8) else { return CardState::Error; };
        if accepted[slot as usize] { return CardState::Error; }
        accepted[slot as usize] = true;
        let Some(country) = profile.get("country").and_then(Value::as_str) else { return CardState::Error; };
        let Some(name) = profile.get("carrierName").and_then(Value::as_str) else { return CardState::Error; };
        if country.is_empty() && name.is_empty() { return CardState::Error; }
    }
    if accepted != requested { return CardState::Error; }

    let Some(ack_bytes) = snapshot.ack_bytes else { return waiting(snapshot); };
    let Ok(ack) = model::parse_json_strict(ack_bytes) else { return CardState::Error; };
    if !ack.is_object() { return CardState::Error; }
    let Some(ok) = ack.get("ok").and_then(Value::as_bool) else { return CardState::Error; };
    let Some(initialized) = ack.get("initialized").and_then(Value::as_bool) else { return CardState::Error; };
    let Some(applied) = ack.get("applied").and_then(Value::as_bool) else { return CardState::Error; };
    let Some(_hooks) = integer(&ack, "hooks") else { return CardState::Error; };
    let Some(sdk) = integer(&ack, "sdk") else { return CardState::Error; };
    let Some(pid) = integer(&ack, "phonePid").filter(|pid| *pid > 0) else { return CardState::Error; };
    let _ = pid; // This pure classifier checks the ACK generation; observer may check process liveness.
    let Some(timestamp) = ack.get("timestampMs").and_then(Value::as_u64)
        .filter(|time| *time > 0 && *time <= i64::MAX as u64) else { return CardState::Error; };
    let Some(ack_boot) = bounded_text(&ack, "bootId", 128) else { return CardState::Error; };
    let Some(hash) = bounded_text(&ack, "configHash", 128) else { return CardState::Error; };
    let error = match ack.get("error") {
        None | Some(Value::Null) => "",
        Some(_) => match bounded_text(&ack, "error", 500) { Some(error) => error, None => return CardState::Error },
    };
    if snapshot.now_ms.saturating_sub(timestamp) > ACK_MAX_AGE_MS
        || timestamp > snapshot.now_ms.saturating_add(ACK_MAX_FUTURE_MS) {
        return CardState::Error;
    }
    if ack_boot != snapshot.boot_id { return CardState::Error; }
    let current_hash = model::fnv1a64(runtime_bytes).to_string();
    if hash != current_hash { return waiting(snapshot); }
    if !error.trim().is_empty() { return CardState::Error; }
    if applied && !(ok && initialized) { return CardState::Error; }
    if !(ok && initialized && applied) { return waiting(snapshot); }
    if !model::ack_coverage(&ack, profiles) || sdk < 29 { return CardState::Error; }
    CardState::Running
}

pub fn description(state: CardState) -> &'static str {
    match state {
        CardState::Running => "運行~中Nya^ ܸ⩌⩊⩌ ܸ^🍧 城堡之下的SIM國度.咖啡廳☕",
        CardState::Unset => "未設定~哦Nya^ ܸ⩌⩊⩌ ܸ^🍞 城堡之下的SIM國度.咖啡廳☕",
        CardState::Waiting => "暖機~中Nya^ ܸ⩌⩊⩌ ܸ^☕ 城堡之下的SIM國度.咖啡廳☕",
        CardState::Stopped => "休息~中Nya^ ܸ⩌⩊⩌ ܸ^🍞 城堡之下的SIM國度.咖啡廳☕",
        CardState::Error => "爆炸~了！Nya^ ܸ⩌⩊⩌ ܸ^🍛 城堡之下的SIM國度.咖啡廳☕",
    }
}
