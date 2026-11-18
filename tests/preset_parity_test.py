#!/usr/bin/env python3
"""Check full original presets across native companion and historical frontends."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
golden = json.loads((ROOT / "tests/fixtures/preset-catalog.json").read_text(encoding="utf-8"))
country_codes = golden["countries"]
carrier_tuples = [tuple(value) for value in golden["carriers"]]
script = (ROOT / "module/webroot/presets.js").read_text(encoding="utf-8")
payload = script.split("global.HaaruPresets = ", 1)[1]
data, _ = json.JSONDecoder().raw_decode(payload.lstrip())
assert len(country_codes) == 28
assert len(carrier_tuples) == 74
assert [item["code"] for item in data["countries"]] == country_codes
assert [(item["displayName"], item["region"]) for item in data["carriers"]] == carrier_tuples
assert len({item["id"] for item in data["carriers"]}) == 74
quoted = r'("(?:\\.|[^"\\])*")'
native_source = (ROOT / "companion/src/main/kotlin/haaru/ui/Presets.kt").read_text(encoding="utf-8")
native_countries = re.findall(r'CountryPreset\(' + quoted + r',\s*' + quoted + r'\)', native_source)
native_carriers = re.findall(r'CarrierPreset\(' + quoted + r',\s*' + quoted + r',\s*' + quoted + r',\s*' + quoted + r'\)', native_source)
assert [(json.loads(code), json.loads(name)) for code, name in native_countries] == [(item["code"], item["name"]) for item in data["countries"]], "Native companion countries differ from complete catalog"
assert [tuple(json.loads(value) for value in row) for row in native_carriers] == [(item["id"], item["name"], item["displayName"], item["region"]) for item in data["carriers"]], "Native companion carriers differ from complete catalog"
print("Preset parity: all 28 country codes and 74 carrier values match the native companion and historical frontends")

