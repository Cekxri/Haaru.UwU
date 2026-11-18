/* SPDX-License-Identifier: Apache-2.0 */
//! Bounded C ABI. Callers own all buffers; Rust never returns allocator-owned pointers.
use crate::model::{self, CoreError, PropertyProfile};
use serde_json::Value;

const LIMIT: usize = 65_536;

// SAFETY: the C caller supplies live, readable buffers of the declared length.
unsafe fn input<'a>(pointer: *const u8, length: usize) -> Result<&'a [u8], CoreError> {
    if length > LIMIT || (length != 0 && pointer.is_null()) { return Err(CoreError::InvalidInput); }
    if length == 0 { return Ok(&[]); }
    Ok(unsafe { std::slice::from_raw_parts(pointer, length) })
}
fn text(bytes: &[u8]) -> Result<&str, CoreError> {
    std::str::from_utf8(bytes).map_err(|_| CoreError::InvalidInput)
}
fn error_code(error: CoreError) -> i32 {
    match error { CoreError::InvalidInput => -1, CoreError::InvalidSlot => -2, CoreError::TooLong => -3 }
}
// SAFETY: the output is writable for capacity bytes. Only owned result bytes are copied.
unsafe fn output(result: Result<String, CoreError>, pointer: *mut u8, capacity: usize) -> i32 {
    match result {
        Err(error) => error_code(error),
        Ok(value) => {
            if capacity > LIMIT || value.len() > capacity || (value.len() != 0 && pointer.is_null()) { return -4; }
            if !value.is_empty() { unsafe { std::ptr::copy(value.as_ptr(), pointer, value.len()); } }
            value.len() as i32
        }
    }
}
fn profiles(bytes: &[u8]) -> Result<Vec<PropertyProfile>, CoreError> {
    let value = model::parse_json_strict(bytes)?;
    let rows = value.as_array().ok_or(CoreError::InvalidInput)?;
    if rows.len() > 8 { return Err(CoreError::InvalidInput); }
    rows.iter().map(|row| {
        let slot = row.get("slot").and_then(Value::as_i64).ok_or(CoreError::InvalidSlot)?;
        let slot = i32::try_from(slot).map_err(|_| CoreError::InvalidSlot)?;
        let country = row.get("country").and_then(Value::as_str).ok_or(CoreError::InvalidInput)?;
        let name = row.get("name").and_then(Value::as_str).ok_or(CoreError::InvalidInput)?;
        Ok(PropertyProfile { slot, country: country.into(), name: name.into() })
    }).collect()
}

/// SAFETY: every nonempty input buffer must be readable for its declared length.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_json_valid(pointer: *const u8, length: usize) -> u8 {
    unsafe { input(pointer, length) }.and_then(model::parse_json_strict).is_ok() as u8
}
/// SAFETY: every nonempty input buffer must be readable for its declared length.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_validate_runtime(pointer: *const u8, length: usize, boot: *const u8, boot_length: usize) -> u8 {
    let result = (|| Ok::<_, CoreError>(model::validate_runtime(unsafe { input(pointer, length)? }, text(unsafe { input(boot, boot_length)? })?)))();
    result.unwrap_or(false) as u8
}
/// SAFETY: every nonempty input buffer must be readable for its declared length.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_validate_shadow(pointer: *const u8, length: usize, boot: *const u8, boot_length: usize) -> u8 {
    let result = (|| Ok::<_, CoreError>(model::validate_shadow(unsafe { input(pointer, length)? }, text(unsafe { input(boot, boot_length)? })?)))();
    result.unwrap_or(false) as u8
}
/// SAFETY: nonempty input must be readable for length bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_fnv1a64(pointer: *const u8, length: usize) -> u64 {
    unsafe { input(pointer, length) }.map(model::fnv1a64).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "C" fn haaru_cache_allowed(protocol: i32, read_only: u8, enabled: u8, boot_matches: u8) -> u8 {
    if read_only > 1 || enabled > 1 || boot_matches > 1 { return 0; }
    model::cache_allowed(protocol, read_only != 0, enabled != 0, boot_matches != 0) as u8
}
#[unsafe(no_mangle)]
pub extern "C" fn haaru_uid_allowed(uid: u32) -> u8 { model::uid_allowed(uid) as u8 }

/// SAFETY: readable inputs and a writable output of capacity bytes; no retained pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_property_overlay(original: *const u8, original_length: usize, plan: *const u8, plan_length: usize,
                                               country: u8, destination: *mut u8, capacity: usize) -> i32 {
    let result = (|| {
        if country > 1 { return Err(CoreError::InvalidInput); }
        let original = text(unsafe { input(original, original_length)? })?;
        let plan = profiles(unsafe { input(plan, plan_length)? })?;
        model::overlay_property(original, &plan, country != 0)
    })();
    unsafe { output(result, destination, capacity) }
}
/// SAFETY: readable input and writable output; no retained pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_property_split(pointer: *const u8, length: usize, destination: *mut u8, capacity: usize) -> i32 {
    let result = (|| {
        let value = text(unsafe { input(pointer, length)? })?;
        serde_json::to_string(&model::split_property(value)).map_err(|_| CoreError::InvalidInput)
    })();
    unsafe { output(result, destination, capacity) }
}
/// SAFETY: readable JSON input and writable output; no retained pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn haaru_property_join(pointer: *const u8, length: usize, destination: *mut u8, capacity: usize) -> i32 {
    let result = (|| {
        let value = model::parse_json_strict(unsafe { input(pointer, length)? })?;
        let values = value.as_array().ok_or(CoreError::InvalidInput)?.iter()
            .map(|item| item.as_str().map(str::to_owned).ok_or(CoreError::InvalidInput)).collect::<Result<Vec<_>, _>>()?;
        Ok(model::join_property(&values))
    })();
    unsafe { output(result, destination, capacity) }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_ffi_inputs_never_read_or_write() {
        unsafe {
            assert_eq!(haaru_validate_runtime(std::ptr::null(), 1, std::ptr::null(), 0), 0);
            assert_eq!(haaru_validate_shadow(std::ptr::null(), LIMIT + 1, std::ptr::null(), 0), 0);
            assert_eq!(haaru_fnv1a64(std::ptr::null(), 0), 0xcbf29ce484222325);
            assert_eq!(haaru_fnv1a64(std::ptr::null(), 1), 0);
            assert_eq!(haaru_property_split(b"tw".as_ptr(), 2, std::ptr::null_mut(), 0), -4);
            let mut buffer = [0xaa; 2];
            assert_eq!(haaru_property_split(b"tw".as_ptr(), 2, buffer.as_mut_ptr(), 2), -4);
            assert_eq!(buffer, [0xaa; 2]);
            assert_eq!(haaru_property_join(b"null".as_ptr(), 4, buffer.as_mut_ptr(), 2), -1);
            assert_eq!(haaru_cache_allowed(2, 2, 1, 1), 0);
        }
    }
}

