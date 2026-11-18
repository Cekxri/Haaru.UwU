// SPDX-License-Identifier: Apache-2.0
use haaru_core::model::{
    cache_allowed, fnv1a64, join_property, overlay_property, parse_json_strict, split_property,
    uid_allowed, validate_runtime, validate_shadow, CoreError, PropertyProfile, MAX_JSON_BYTES,
};
use serde_json::{json, Value};

fn profile(slot: i32, country: &str, name: &str) -> PropertyProfile {
    PropertyProfile { slot, country: country.into(), name: name.into() }
}

fn runtime() -> Value {
    json!({
        "protocol": 2, "global": true, "engine": "system-global-volatile",
        "bootId": "boot-one", "enabled": true, "readOnly": false,
        "profiles": [{"slot": 0, "subId": 11, "country": "US", "carrierName": "Carrier"}]
    })
}

fn runtime_ok(value: &Value) -> bool {
    validate_runtime(&serde_json::to_vec(value).unwrap(), "boot-one")
}

fn shadow(countries: &str, names: &str) -> Value {
    json!({"bootId": "boot-one", "countryList": countries, "nameList": names})
}

fn shadow_ok(value: &Value) -> bool {
    validate_shadow(&serde_json::to_vec(value).unwrap(), "boot-one")
}

#[test]
fn property_codec_preserves_slots_punctuation_and_unicode() {
    assert_eq!(split_property(""), vec![""]);
    assert_eq!(split_property("tw,,jp,"), vec!["tw", "", "jp", ""]);
    assert_eq!(split_property(r"A\,B,C\\D"), vec!["A,B", r"C\D"]);
    assert_eq!(join_property(&["A,B".into(), r"C\D".into(), "".into()]), r"A\,B,C\\D,");
    let values = vec!["".into(), "A,B".into(), r"C\D".into(), "台灣🗾".into(), "".into()];
    assert_eq!(split_property(&join_property(&values)), values);
    assert_eq!(split_property(r"Legacy\Q,tail\"), vec!["LegacyQ", r"tail\"]);
    // Every supported escape combination must round-trip as decoded slot data.
    for first in ["", ",", "\\", "\\,", "台灣", "🗾"] {
        for second in ["", ",", "\\", "A,B\\C", "日本"] {
            let slots = vec![first.into(), second.into(), "".into()];
            assert_eq!(split_property(&join_property(&slots)), slots);
        }
    }
}

#[test]
fn property_overlay_retains_the_existing_cpp_contract() {
    assert_eq!(overlay_property("tw,jp,kr", &[profile(0, "US", "")], true).unwrap(), "us,jp,kr");
    assert_eq!(overlay_property("tw,jp,kr", &[profile(2, "JP", ""), profile(0, "US", "")], true).unwrap(), "us,jp,jp");
    assert_eq!(overlay_property("tw,,kr,", &[profile(2, "US", "")], true).unwrap(), "tw,,us,");
    assert_eq!(overlay_property("tw,jp", &[profile(1, "", "Carrier")], true).unwrap(), "tw,jp");
    assert_eq!(overlay_property("Original0,Original1", &[profile(1, "JP", "")], false).unwrap(), "Original0,Original1");
    assert_eq!(overlay_property("Original0,Original1,Original2", &[profile(1, "", r"U,S\Carrier")], false).unwrap(), r"Original0,U\,S\\Carrier,Original2");
    for original in [r"A\,B,C\\D,", r"Legacy\Q,tail\"] {
        assert_eq!(overlay_property(original, &[], false).unwrap(), original);
        assert_eq!(overlay_property(original, &[profile(7, "", "")], false).unwrap(), original);
    }
    assert_eq!(overlay_property("tw", &[profile(7, "JP", "")], true).unwrap(), "tw,,,,,,,jp");
    assert_eq!(overlay_property("tw", &[profile(7, "", "")], true).unwrap(), "tw");
}

#[test]
fn property_limit_counts_utf8_separators_and_escape_expansion() {
    assert_eq!(overlay_property("", &[profile(0, "", &"A".repeat(91))], false).unwrap().len(), 91);
    assert_eq!(overlay_property("", &[profile(0, "", &"A".repeat(92))], false), Err(CoreError::TooLong));
    assert_eq!(overlay_property("", &[profile(0, "", &",".repeat(45))], false).unwrap().len(), 90);
    assert_eq!(overlay_property("", &[profile(0, "", &",".repeat(46))], false), Err(CoreError::TooLong));
    assert_eq!(overlay_property(&format!("{},X", "A".repeat(89)), &[profile(1, "", "B")], false).unwrap().len(), 91);
    assert_eq!(overlay_property(&format!("{},X", "A".repeat(90)), &[profile(1, "", "B")], false), Err(CoreError::TooLong));
    assert_eq!(overlay_property("", &[profile(0, "", &"台".repeat(30))], false).unwrap().len(), 90);
    assert_eq!(overlay_property("", &[profile(0, "", &"台".repeat(31))], false), Err(CoreError::TooLong));
    assert_eq!(overlay_property(&"X".repeat(92), &[], false), Err(CoreError::TooLong));
}

#[test]
fn property_profile_errors_are_explicit_and_do_not_depend_on_order() {
    for slot in [-1, 8, i32::MIN, i32::MAX] {
        assert_eq!(overlay_property("tw", &[profile(slot, "US", "")], true), Err(CoreError::InvalidSlot));
    }
    assert_eq!(overlay_property("tw", &[profile(0, "US", ""), profile(0, "JP", "")], true), Err(CoreError::InvalidSlot));
    assert_eq!(overlay_property("tw", &vec![profile(0, "", ""); 9], true), Err(CoreError::InvalidInput));
    for country in ["U", "USA", "U1", "ＵＳ", "éa", " US"] {
        assert_eq!(overlay_property("tw", &[profile(0, country, "")], true), Err(CoreError::InvalidInput));
    }
    for name in ["A\nB", "A\0B", "A\u{7f}B"] {
        assert_eq!(overlay_property("Original", &[profile(0, "", name)], false), Err(CoreError::InvalidInput));
        assert_eq!(overlay_property(name, &[], false), Err(CoreError::InvalidInput));
        assert_eq!(overlay_property(name, &[profile(0, "US", "")], true), Err(CoreError::InvalidInput));
    }
    assert_eq!(overlay_property("tw", &[profile(0, "jP", "")], true).unwrap(), "jp");
}

#[test]
fn runtime_accepts_empty_plans_partial_fields_and_all_eight_unique_slots() {
    assert!(runtime_ok(&runtime()));
    for (country, name) in [("", "Carrier"), ("JP", ""), ("", "")] {
        let mut value = runtime();
        value["profiles"][0]["country"] = json!(country);
        value["profiles"][0]["carrierName"] = json!(name);
        assert!(runtime_ok(&value));
    }
    let mut value = runtime();
    value["profiles"] = json!([]);
    assert!(runtime_ok(&value));
    value["profiles"] = Value::Array((0..8).map(|slot| json!({"slot":slot,"subId":100+slot,"country":"JP","carrierName":"日本🗾"})).collect());
    assert!(runtime_ok(&value));
}

#[test]
fn runtime_requires_the_current_global_protocol_and_boot() {
    let base = runtime();
    for (field, invalid) in [
        ("protocol", json!(1)), ("protocol", json!(2.0)), ("protocol", json!("2")),
        ("global", json!(false)), ("global", json!("true")),
        ("engine", json!("obsolete-process-scoped-engine")), ("engine", json!(null)),
        ("bootId", json!("boot-two")), ("bootId", json!(2)),
        ("enabled", json!("true")), ("readOnly", json!(0)),
        ("profiles", json!({})), ("profiles", json!(null)),
    ] {
        let mut value = base.clone(); value[field] = invalid;
        assert!(!runtime_ok(&value), "invalid {field}");
    }
    for field in ["protocol", "global", "engine", "bootId", "profiles"] {
        let mut value = base.clone(); value.as_object_mut().unwrap().remove(field);
        assert!(!runtime_ok(&value), "missing {field}");
    }
    let bytes = serde_json::to_vec(&base).unwrap();
    assert!(!validate_runtime(&bytes, ""));
    assert!(!validate_runtime(&bytes, "boot-two"));
    for scalar in [json!(null), json!([]), json!(true), json!(1)] {
        assert!(!runtime_ok(&scalar));
    }
}

#[test]
fn runtime_rejects_ambiguous_or_noninteger_sim_identifiers() {
    for (field, invalid) in [
        ("slot", json!(-1)), ("slot", json!(8)), ("slot", json!(0.0)), ("slot", json!("0")),
        ("slot", json!(2_147_483_648u64)), ("subId", json!(-1)), ("subId", json!(11.0)),
        ("subId", json!("11")), ("subId", json!(u64::MAX)),
    ] {
        let mut value = runtime(); value["profiles"][0][field] = invalid;
        assert!(!runtime_ok(&value), "invalid {field}");
    }
    for duplicate_slot in [true, false] {
        let mut value = runtime();
        value["profiles"].as_array_mut().unwrap().push(json!({"slot":if duplicate_slot {0} else {1},"subId":if duplicate_slot {12} else {11},"country":"JP","carrierName":"Carrier"}));
        assert!(!runtime_ok(&value));
    }
    let mut value = runtime();
    value["profiles"] = Value::Array((0..9).map(|slot| json!({"slot":slot,"subId":slot,"country":"","carrierName":""})).collect());
    assert!(!runtime_ok(&value));
    value["profiles"] = json!([null]); assert!(!runtime_ok(&value));
}

#[test]
fn runtime_name_limit_uses_unicode_scalars_and_rejects_controls() {
    let mut value = runtime();
    value["profiles"][0]["carrierName"] = json!("🗾".repeat(80));
    assert!(runtime_ok(&value));
    value["profiles"][0]["carrierName"] = json!("🗾".repeat(81));
    assert!(!runtime_ok(&value));
    for name in ["Line\nBreak", "nul\0name", "tab\tname", "del\u{7f}name"] {
        value["profiles"][0]["carrierName"] = json!(name); assert!(!runtime_ok(&value));
    }
    for country in ["USA", "U1", "ＵＳ", "éa", " U", "u\0"] {
        value = runtime(); value["profiles"][0]["country"] = json!(country); assert!(!runtime_ok(&value));
    }
    for field in ["slot", "subId", "country", "carrierName"] {
        value = runtime(); value["profiles"][0].as_object_mut().unwrap().remove(field); assert!(!runtime_ok(&value));
        value = runtime(); value["profiles"][0][field] = json!(null); assert!(!runtime_ok(&value));
    }
}

#[test]
fn weak_sim_identity_requires_explicit_confirmation_in_this_boot() {
    let mut value = runtime(); value["profiles"][0]["weakIdentity"] = json!(true);
    assert!(!runtime_ok(&value));
    for confirmation in [json!(""), json!("boot-two"), json!(false)] {
        value["profiles"][0]["confirmedBoot"] = confirmation; assert!(!runtime_ok(&value));
    }
    value["profiles"][0]["confirmedBoot"] = json!("boot-one"); assert!(runtime_ok(&value));
    value["profiles"][0]["weakIdentity"] = json!("false"); assert!(!runtime_ok(&value));
    value["profiles"][0]["weakIdentity"] = json!(false);
    value["profiles"][0]["confirmedBoot"] = json!("boot-two"); assert!(runtime_ok(&value));
}

#[test]
fn strict_json_rejects_duplicate_keys_including_escaped_aliases_and_nested_objects() {
    for bytes in [
        br#"{"protocol":1,"protocol":2}"#.as_slice(),
        br#"{"protocol":1,"\u0070rotocol":2}"#.as_slice(),
        br#"{"profiles":[{"slot":0,"slot":1}]}"#.as_slice(),
        br#"{"nested":{"name":"first","n\u0061me":"second"}}"#.as_slice(),
        br#"[{"a":{"x":1,"x":2}}]"#.as_slice(),
    ] {
        assert_eq!(parse_json_strict(bytes), Err(CoreError::InvalidInput));
    }
    assert!(parse_json_strict(br#"{"first":{"name":"same"},"second":{"name":"same"},"quoted":"\"slot\":0"}"#).is_ok());
    assert!(parse_json_strict(br#"[null,true,false,1,-2,1.5,"x",[],{}]"#).is_ok());
}

#[test]
fn malformed_utf8_surrogates_trailing_data_and_oversized_json_fail_closed() {
    for bytes in [b"".as_slice(), b"not-json".as_slice(), b"{}{}".as_slice(), b"[NaN]".as_slice(),
        b"{\"name\":\"\xc0\xaf\"}".as_slice(), br#"{"name":"\ud800"}"#.as_slice(),
        br#"{"name":"\udfff"}"#.as_slice(), br#"{"a":1,}"#.as_slice()] {
        assert_eq!(parse_json_strict(bytes), Err(CoreError::InvalidInput));
        assert!(!validate_runtime(bytes, "boot-one")); assert!(!validate_shadow(bytes, "boot-one"));
    }
    assert_eq!(parse_json_strict(br#"{"name":"\ud83d\uddfe"}"#).unwrap()["name"], json!("🗾"));
    let mut maximum = serde_json::to_vec(&runtime()).unwrap(); maximum.resize(MAX_JSON_BYTES, b' ');
    assert!(validate_runtime(&maximum, "boot-one"));
    maximum.push(b' '); assert_eq!(parse_json_strict(&maximum), Err(CoreError::TooLong));
    assert!(!validate_runtime(&maximum, "boot-one"));
    assert!(parse_json_strict(format!("{}0{}", "[".repeat(130), "]".repeat(130)).as_bytes()).is_err());
}

#[test]
fn shadow_preserves_the_same_boot_raw_lists_and_supported_slot_count() {
    assert!(shadow_ok(&shadow("", "")));
    assert!(shadow_ok(&shadow("tw,,JP,", r"A\,B,C\\D,,")));
    assert!(shadow_ok(&shadow(",,,,,,,", ",,,,,,,")));
    assert!(!shadow_ok(&shadow(",,,,,,,,", "")));
    assert!(!shadow_ok(&shadow("", ",,,,,,,,")));
    for country in ["USA", "ＵＳ", "U1", r"tw\,jp"] { assert!(!shadow_ok(&shadow(country, ""))); }
    assert!(shadow_ok(&shadow(r"A\Z", r"Legacy\Q,tail\")));
    let bytes = serde_json::to_vec(&shadow("tw", "Carrier")).unwrap();
    assert!(!validate_shadow(&bytes, "boot-two")); assert!(!validate_shadow(&bytes, ""));
}

#[test]
fn shadow_enforces_property_bytes_and_typed_strings() {
    assert!(shadow_ok(&shadow("tw", &"A".repeat(91))));
    assert!(!shadow_ok(&shadow("tw", &"A".repeat(92))));
    assert!(shadow_ok(&shadow("tw", &format!("{}A", "台".repeat(30)))));
    assert!(!shadow_ok(&shadow("tw", &"台".repeat(31))));
    for name in ["A\nB", "A\0B", "A\u{7f}B"] { assert!(!shadow_ok(&shadow("tw", name))); }
    for field in ["bootId", "countryList", "nameList"] {
        let mut value = shadow("tw", "Carrier"); value[field] = json!(false); assert!(!shadow_ok(&value));
        value = shadow("tw", "Carrier"); value.as_object_mut().unwrap().remove(field); assert!(!shadow_ok(&value));
    }
    assert!(!shadow_ok(&json!([])));
    assert!(!validate_shadow(br#"{"bootId":"boot-one","countryList":"tw","nameList":"A","nameList":"B"}"#, "boot-one"));
}

#[test]
fn fnv_hash_matches_published_byte_vectors() {
    assert_eq!(fnv1a64(b""), 0xcbf2_9ce4_8422_2325);
    assert_eq!(fnv1a64(b"a"), 0xaf63_dc4c_8601_ec8c);
    assert_eq!(fnv1a64(b"hello"), 0xa430_d846_80aa_bd0b);
    assert_eq!(fnv1a64(b"foobar"), 0x8594_4171_f739_67e8);
    assert_ne!(fnv1a64(b"A\0B"), fnv1a64(b"A"));
}

#[test]
fn cache_and_uid_policy_never_select_other_apps_or_stale_configuration() {
    assert!(cache_allowed(2, false, true, true));
    for protocol in [-1, 0, 1, 3, i32::MAX] { assert!(!cache_allowed(protocol, false, true, true)); }
    assert!(!cache_allowed(2, true, true, true));
    assert!(!cache_allowed(2, false, false, true));
    assert!(!cache_allowed(2, false, true, false));
    assert!(uid_allowed(1001));
    for uid in [0, 1000, 1002, 2000, 10_000, 101_001, u32::MAX] { assert!(!uid_allowed(uid)); }
}
