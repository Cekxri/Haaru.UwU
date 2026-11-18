/* SPDX-License-Identifier: Apache-2.0 */
//! Host contract runner; it calls the same safe functions exported to the Android shim.
use haaru_core::model::{self, PropertyProfile};
use serde_json::{json, Value};
use std::io::{self, BufRead};

fn run(value: &Value) -> Result<Value, String> {
    let op = value.get("op").and_then(Value::as_str).ok_or("Missing operation")?;
    let string = |key: &str| value.get(key).and_then(Value::as_str).ok_or_else(|| format!("Missing {key}"));
    let boolean = |key: &str| value.get(key).and_then(Value::as_bool).ok_or_else(|| format!("Missing {key}"));
    let boot = || string("bootId");
    match op {
        "runtime" => {
            let bytes = if let Some(raw) = value.get("runtimeRaw").and_then(Value::as_str) { raw.as_bytes().to_vec() }
                else { serde_json::to_vec(value.get("runtime").ok_or("Missing runtime")?).map_err(|e| e.to_string())? };
            Ok(json!({"ok":true,"accepted":model::validate_runtime(&bytes, boot()?)}))
        }
        "shadow" => {
            let bytes = serde_json::to_vec(value.get("shadow").ok_or("Missing shadow")?).map_err(|e| e.to_string())?;
            Ok(json!({"ok":true,"accepted":model::validate_shadow(&bytes, boot()?)}))
        }
        "uid" => {
            let uid = value.get("uid").and_then(Value::as_u64).and_then(|id| u32::try_from(id).ok()).ok_or("Invalid UID")?;
            Ok(json!({"ok":true,"accepted":model::uid_allowed(uid)}))
        }
        "cache" => {
            let protocol = value.get("protocol").and_then(Value::as_i64).and_then(|v| i32::try_from(v).ok()).ok_or("Invalid protocol")?;
            Ok(json!({"ok":true,"accepted":model::cache_allowed(protocol,boolean("readOnly")?,boolean("enabled")?,boolean("bootMatches")?)}))
        }
        "split" => Ok(json!({"ok":true,"result":model::split_property(string("value")?)})),
        "join" => {
            let values = value.get("values").and_then(Value::as_array).ok_or("Missing values")?.iter()
                .map(|item| item.as_str().map(str::to_owned).ok_or("Invalid string list".to_owned())).collect::<Result<Vec<_>, _>>()?;
            Ok(json!({"ok":true,"result":model::join_property(&values)}))
        }
        "fnv" => {
            let bytes = if let Some(items) = value.get("bytes").and_then(Value::as_array) {
                items.iter().map(|item| item.as_u64().and_then(|n| u8::try_from(n).ok()).ok_or("Invalid byte".to_owned())).collect::<Result<Vec<_>, _>>()?
            } else { string("value")?.as_bytes().to_vec() };
            Ok(json!({"ok":true,"result":model::fnv1a64(&bytes).to_string()}))
        }
        "overlay" => {
            let profiles = value.get("profiles").and_then(Value::as_array).ok_or("Missing profiles")?.iter().map(|item| {
                let slot = item.get("slot").and_then(Value::as_i64).and_then(|n| i32::try_from(n).ok()).ok_or("Invalid slot".to_owned())?;
                let country = item.get("country").and_then(Value::as_str).ok_or("Invalid country".to_owned())?.to_owned();
                let name = item.get("name").and_then(Value::as_str).ok_or("Invalid name".to_owned())?.to_owned();
                Ok(PropertyProfile {slot,country,name})
            }).collect::<Result<Vec<_>, String>>()?;
            let result = model::overlay_property(string("original")?, &profiles, boolean("country")?).map_err(|e| format!("{e:?}"))?;
            Ok(json!({"ok":true,"result":result}))
        }
        _ => Err("Unknown operation".into()),
    }
}
fn main() {
    for line in io::stdin().lock().lines() {
        let response = line.map_err(|e| e.to_string()).and_then(|line| {
            let value = model::parse_json_strict(line.as_bytes()).map_err(|e| format!("{e:?}"))?;
            run(&value)
        }).unwrap_or_else(|error| json!({"ok":false,"error":error}));
        println!("{response}");
    }
}
