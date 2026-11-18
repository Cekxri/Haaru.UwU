// SPDX-License-Identifier: Apache-2.0
//! Bounded, platform-independent contracts for the volatile SIM engine.

use serde_json::Value;
use std::collections::HashSet;

pub const MAX_JSON_BYTES: usize = 65_536;
pub const MAX_PROPERTY_BYTES: usize = 91;

/// Counts alone cannot prove coverage: require the discovered contract and each requested field.
pub fn ack_coverage(ack: &Value, profiles: &[Value]) -> bool {
    let Some(required) = ack.get("requiredHooks").and_then(Value::as_u64).filter(|value| *value >= 3 && *value <= i32::MAX as u64) else { return false; };
    if ack.get("hooks").and_then(Value::as_u64) != Some(required)
        || ack.get("coverageComplete").and_then(Value::as_bool) != Some(true) { return false; }
    let Some(country) = ack.get("supportsCountry").and_then(Value::as_bool) else { return false; };
    let Some(name) = ack.get("supportsCarrierName").and_then(Value::as_bool) else { return false; };
    profiles.iter().all(|profile| {
        let Some(iso) = profile.get("country").and_then(Value::as_str) else { return false; };
        let Some(label) = profile.get("carrierName").and_then(Value::as_str) else { return false; };
        (iso.is_empty() || country) && (label.is_empty() || name)
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CoreError {
    InvalidInput,
    InvalidSlot,
    TooLong,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PropertyProfile {
    pub slot: i32,
    pub country: String,
    pub name: String,
}

/// Android StringList escaping: preserve empty slots, including a trailing one.
/// A trailing backslash is literal; other backslashes escape the following character.
pub fn split_property(text: &str) -> Vec<String> {
    let mut values = vec![String::new()];
    let mut escaped = false;
    for character in text.chars() {
        if escaped {
            values.last_mut().unwrap().push(character);
            escaped = false;
        } else if character == '\\' {
            escaped = true;
        } else if character == ',' {
            values.push(String::new());
        } else {
            values.last_mut().unwrap().push(character);
        }
    }
    if escaped {
        values.last_mut().unwrap().push('\\');
    }
    values
}

pub fn join_property(values: &[String]) -> String {
    let mut result = String::new();
    for (index, value) in values.iter().enumerate() {
        if index > 0 {
            result.push(',');
        }
        for character in value.chars() {
            if character == ',' || character == '\\' {
                result.push('\\');
            }
            result.push(character);
        }
    }
    result
}

pub fn overlay_property(
    original: &str,
    profiles: &[PropertyProfile],
    country: bool,
) -> Result<String, CoreError> {
    if profiles.len() > 8 || has_name_control(original) {
        return Err(CoreError::InvalidInput);
    }
    let mut slots = [false; 8];
    let mut values = split_property(original);
    let mut changed = false;
    for profile in profiles {
        let slot = usize::try_from(profile.slot).map_err(|_| CoreError::InvalidSlot)?;
        if slot >= slots.len() || slots[slot] {
            return Err(CoreError::InvalidSlot);
        }
        slots[slot] = true;
        if !valid_country(&profile.country) || has_name_control(&profile.name) {
            return Err(CoreError::InvalidInput);
        }
        let value = if country {
            &profile.country
        } else {
            &profile.name
        };
        if value.is_empty() {
            continue;
        }
        changed = true;
        if values.len() <= slot {
            values.resize(slot + 1, String::new());
        }
        values[slot] = if country {
            value.to_ascii_lowercase()
        } else {
            value.clone()
        };
    }
    // Decoding/re-encoding a noncanonical natural value would alter its bytes.
    // Omitted overrides therefore return the original representation verbatim.
    let result = if changed {
        join_property(&values)
    } else {
        original.to_owned()
    };
    if result.len() > MAX_PROPERTY_BYTES {
        Err(CoreError::TooLong)
    } else {
        Ok(result)
    }
}

/// serde_json validates UTF-8, syntax and surrogate pairs. Its Value map normally
/// overwrites duplicate keys, so inspect the validated token stream as well.
/// This shared parser lets configuration and appearance readers reject ambiguity.
pub fn parse_json_strict(bytes: &[u8]) -> Result<Value, CoreError> {
    if bytes.len() > MAX_JSON_BYTES {
        return Err(CoreError::TooLong);
    }
    let value = serde_json::from_slice(bytes).map_err(|_| CoreError::InvalidInput)?;
    let mut scanner = KeyScanner { bytes, position: 0 };
    scanner.value(0)?;
    scanner.whitespace();
    if scanner.position != bytes.len() {
        return Err(CoreError::InvalidInput);
    }
    Ok(value)
}

pub fn validate_runtime(bytes: &[u8], boot: &str) -> bool {
    if boot.is_empty() {
        return false;
    }
    let Ok(value) = parse_json_strict(bytes) else {
        return false;
    };
    let Some(config) = value.as_object() else {
        return false;
    };
    if config.get("protocol").and_then(Value::as_i64) != Some(2)
        || config.get("global").and_then(Value::as_bool) != Some(true)
        || config.get("engine").and_then(Value::as_str) != Some("system-global-volatile")
        || config.get("bootId").and_then(Value::as_str) != Some(boot)
        || ["enabled", "readOnly"]
            .iter()
            .any(|key| config.get(*key).is_some_and(|value| !value.is_boolean()))
    {
        return false;
    }
    let Some(profiles) = config.get("profiles").and_then(Value::as_array) else {
        return false;
    };
    if profiles.len() > 8 {
        return false;
    }
    let mut slots = [false; 8];
    let mut ids = HashSet::new();
    for profile in profiles {
        let Some(profile) = profile.as_object() else {
            return false;
        };
        let Some(slot) = profile.get("slot").and_then(integer_i32) else {
            return false;
        };
        let Some(id) = profile.get("subId").and_then(integer_i32) else {
            return false;
        };
        if !(0..=7).contains(&slot) || id < 0 || slots[slot as usize] || !ids.insert(id) {
            return false;
        }
        slots[slot as usize] = true;
        let Some(country) = profile.get("country").and_then(Value::as_str) else {
            return false;
        };
        let Some(name) = profile.get("carrierName").and_then(Value::as_str) else {
            return false;
        };
        if !valid_country(country) || name.chars().count() > 80 || has_name_control(name) {
            return false;
        }
        let weak = match profile.get("weakIdentity") {
            None => false,
            Some(value) => match value.as_bool() {
                Some(value) => value,
                None => return false,
            },
        };
        let confirmed_boot = match profile.get("confirmedBoot") {
            None => "",
            Some(value) => match value.as_str() {
                Some(value) => value,
                None => return false,
            },
        };
        if weak && confirmed_boot != boot {
            return false;
        }
    }
    true
}

pub fn validate_shadow(bytes: &[u8], boot: &str) -> bool {
    if boot.is_empty() {
        return false;
    }
    let Ok(value) = parse_json_strict(bytes) else {
        return false;
    };
    let Some(shadow) = value.as_object() else {
        return false;
    };
    if shadow.get("bootId").and_then(Value::as_str) != Some(boot) {
        return false;
    }
    let Some(countries) = shadow.get("countryList").and_then(Value::as_str) else {
        return false;
    };
    let Some(names) = shadow.get("nameList").and_then(Value::as_str) else {
        return false;
    };
    countries.len() <= MAX_PROPERTY_BYTES
        && names.len() <= MAX_PROPERTY_BYTES
        && split_property(countries).len() <= 8
        && split_property(names).len() <= 8
        && split_property(countries)
            .iter()
            .all(|country| valid_country(country))
        && !has_name_control(names)
}

pub fn fnv1a64(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

pub fn cache_allowed(
    protocol: i32,
    read_only: bool,
    enabled: bool,
    boot_matches: bool,
) -> bool {
    protocol == 2 && !read_only && enabled && boot_matches
}

pub fn uid_allowed(uid: u32) -> bool {
    uid == 1001
}

fn integer_i32(value: &Value) -> Option<i32> {
    value.as_i64().and_then(|number| i32::try_from(number).ok())
}

fn valid_country(country: &str) -> bool {
    country.is_empty()
        || (country.len() == 2 && country.as_bytes().iter().all(u8::is_ascii_alphabetic))
}

fn has_name_control(name: &str) -> bool {
    // Match the existing native/Java policy: C0 controls and DEL, not UTF-8 bytes.
    name.chars()
        .any(|character| character <= '\u{1f}' || character == '\u{7f}')
}

struct KeyScanner<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl KeyScanner<'_> {
    fn whitespace(&mut self) {
        while self
            .bytes
            .get(self.position)
            .is_some_and(u8::is_ascii_whitespace)
        {
            self.position += 1;
        }
    }

    fn consume(&mut self, token: u8) -> Result<(), CoreError> {
        self.whitespace();
        if self.bytes.get(self.position) != Some(&token) {
            return Err(CoreError::InvalidInput);
        }
        self.position += 1;
        Ok(())
    }

    fn string(&mut self) -> Result<String, CoreError> {
        self.whitespace();
        let start = self.position;
        self.consume(b'"')?;
        while let Some(byte) = self.bytes.get(self.position) {
            match byte {
                b'\\' => self.position += 2,
                b'"' => {
                    self.position += 1;
                    return serde_json::from_slice(&self.bytes[start..self.position])
                        .map_err(|_| CoreError::InvalidInput);
                }
                _ => self.position += 1,
            }
        }
        Err(CoreError::InvalidInput)
    }

    fn value(&mut self, depth: usize) -> Result<(), CoreError> {
        if depth >= 128 {
            return Err(CoreError::InvalidInput);
        }
        self.whitespace();
        match self.bytes.get(self.position) {
            Some(b'{') => {
                self.position += 1;
                self.whitespace();
                if self.bytes.get(self.position) == Some(&b'}') {
                    self.position += 1;
                    return Ok(());
                }
                let mut keys = HashSet::new();
                loop {
                    if !keys.insert(self.string()?) {
                        return Err(CoreError::InvalidInput);
                    }
                    self.consume(b':')?;
                    self.value(depth + 1)?;
                    self.whitespace();
                    match self.bytes.get(self.position) {
                        Some(b',') => self.position += 1,
                        Some(b'}') => {
                            self.position += 1;
                            break;
                        }
                        _ => return Err(CoreError::InvalidInput),
                    }
                }
            }
            Some(b'[') => {
                self.position += 1;
                self.whitespace();
                if self.bytes.get(self.position) == Some(&b']') {
                    self.position += 1;
                    return Ok(());
                }
                loop {
                    self.value(depth + 1)?;
                    self.whitespace();
                    match self.bytes.get(self.position) {
                        Some(b',') => self.position += 1,
                        Some(b']') => {
                            self.position += 1;
                            break;
                        }
                        _ => return Err(CoreError::InvalidInput),
                    }
                }
            }
            Some(b'"') => {
                self.string()?;
            }
            Some(_) => {
                let start = self.position;
                while self.bytes.get(self.position).is_some_and(|byte| {
                    !byte.is_ascii_whitespace() && !matches!(byte, b',' | b'}' | b']')
                }) {
                    self.position += 1;
                }
                if self.position == start {
                    return Err(CoreError::InvalidInput);
                }
            }
            None => return Err(CoreError::InvalidInput),
        }
        Ok(())
    }
}
