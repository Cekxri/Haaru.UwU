//! Java Properties.load(InputStream) compatible durable settings codec.
use std::collections::BTreeMap;

pub type Properties = BTreeMap<String, String>;

fn whitespace(value: u8) -> bool { matches!(value, b' ' | b'\t' | 0x0c) }

/// Input bytes are ISO-8859-1; Unicode escapes are decoded as UTF-16 pairs.
/// Like Java Properties, a repeated property uses its final value.
pub fn parse(bytes: &[u8]) -> Result<Properties, String> {
    let mut physical = Vec::new();
    let mut start = 0;
    let mut index = 0;
    while index < bytes.len() {
        if matches!(bytes[index], b'\r' | b'\n') {
            physical.push(&bytes[start..index]);
            if bytes[index] == b'\r' && bytes.get(index + 1) == Some(&b'\n') { index += 1; }
            start = index + 1;
        }
        index += 1;
    }
    if start < bytes.len() { physical.push(&bytes[start..]); }
    let mut result = BTreeMap::new();
    let mut logical = Vec::new();
    let mut continuing = false;
    for line in physical {
        let offset = line.iter().position(|&byte| !whitespace(byte)).unwrap_or(line.len());
        let line = &line[offset..];
        if !continuing && (line.is_empty() || matches!(line.first(), Some(b'#' | b'!'))) { continue; }
        logical.extend_from_slice(line);
        let slashes = logical.iter().rev().take_while(|&&byte| byte == b'\\').count();
        if slashes % 2 == 1 {
            logical.pop(); continuing = true;
        } else {
            insert_line(&logical, &mut result)?; logical.clear(); continuing = false;
        }
    }
    if continuing || !logical.is_empty() { insert_line(&logical, &mut result)?; }
    Ok(result)
}

fn insert_line(line: &[u8], result: &mut Properties) -> Result<(), String> {
    if line.is_empty() { return Ok(()); }
    let mut key_end = line.len();
    let mut value_start = line.len();
    let mut escaped = false;
    let mut separator = false;
    for (index, &byte) in line.iter().enumerate() {
        if !escaped && (byte == b'=' || byte == b':') {
            key_end = index; value_start = index + 1; separator = true; break;
        }
        if !escaped && whitespace(byte) { key_end = index; value_start = index + 1; break; }
        escaped = byte == b'\\' && !escaped;
    }
    while value_start < line.len() {
        let byte = line[value_start];
        if !whitespace(byte) {
            if !separator && (byte == b'=' || byte == b':') { separator = true; }
            else { break; }
        }
        value_start += 1;
    }
    result.insert(unescape(&line[..key_end])?, unescape(&line[value_start..])?);
    Ok(())
}

fn unescape(bytes: &[u8]) -> Result<String, String> {
    let mut units = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        let byte = bytes[index]; index += 1;
        if byte != b'\\' { units.push(byte as u16); continue; }
        let escaped = *bytes.get(index).ok_or("Malformed trailing Properties escape")?; index += 1;
        let value = match escaped {
            b't' => b'\t' as u16, b'n' => b'\n' as u16, b'r' => b'\r' as u16, b'f' => 0x0c,
            b'u' => {
                let digits = bytes.get(index..index + 4).ok_or("Malformed Properties Unicode escape")?;
                let mut value = 0u16;
                for digit in digits {
                    let number = match digit { b'0'..=b'9' => digit - b'0', b'a'..=b'f' => digit - b'a' + 10,
                        b'A'..=b'F' => digit - b'A' + 10, _ => return Err("Malformed Properties Unicode escape".into()) };
                    value = value * 16 + number as u16;
                }
                index += 4; value
            }
            other => other as u16,
        };
        units.push(value);
    }
    String::from_utf16(&units).map_err(|_| "Properties contains an unpaired Unicode surrogate".into())
}

fn escape(value: &str) -> String {
    let mut output = String::new();
    for unit in value.encode_utf16() {
        match unit {
            0x09 => output.push_str("\\t"), 0x0a => output.push_str("\\n"), 0x0d => output.push_str("\\r"),
            0x0c => output.push_str("\\f"), 0x5c => output.push_str("\\\\"),
            0x20 | 0x3d | 0x3a | 0x23 | 0x21 => { output.push('\\'); output.push(char::from_u32(unit as u32).unwrap()); }
            0x21..=0x7e => output.push(char::from_u32(unit as u32).unwrap()),
            _ => output.push_str(&format!("\\u{unit:04X}")),
        }
    }
    output
}

pub fn encode(properties: &Properties) -> Vec<u8> {
    let mut output = String::from("#Haaru durable settings migrated into KernelSU staging\n");
    for (key, value) in properties { output.push_str(&escape(key)); output.push('='); output.push_str(&escape(value)); output.push('\n'); }
    output.into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn java_properties_syntax_and_unicode() {
        let input = b"#comment\r\n ! comment\nplain=value\nkey\\ with\\ spaces : hello\\\r\n  world\nescaped\\=key=value\\:x\nlatin=\xe9\nemoji=\\uD83D\\uDE00\nunknown=\\q\nrepeat=first\nrepeat=last\n";
        let values = parse(input).unwrap();
        assert_eq!(values["key with spaces"], "helloworld"); assert_eq!(values["escaped=key"], "value:x");
        assert_eq!(values["latin"], "é"); assert_eq!(values["emoji"], "😀"); assert_eq!(values["unknown"], "q");
        assert_eq!(values["repeat"], "last"); assert_eq!(parse(&encode(&values)).unwrap(), values);
    }
    #[test]
    fn rejects_invalid_surrogates_and_hex() {
        for bytes in [b"v=\\uD800".as_slice(), b"v=\\uDC00", b"v=\\u12xz", b"v=\\u12"] { assert!(parse(bytes).is_err()); }
    }
    #[test]
    fn continuation_escape_and_empty_values() {
        let values = parse(b"one=end\\\ntwo=x\\\\\nempty\n=value\nodd=end\\").unwrap();
        assert_eq!(values["one"], "endtwo=x\\"); assert_eq!(values["empty"], ""); assert_eq!(values[""], "value");
        assert_eq!(values["odd"], "end");
        let punctuation = BTreeMap::from([("a=b:c !\\".into(), " leading 台灣,😀\\:#=".into())]);
        assert_eq!(parse(&encode(&punctuation)).unwrap(), punctuation);
    }
}
