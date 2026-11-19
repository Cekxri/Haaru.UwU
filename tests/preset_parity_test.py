#!/usr/bin/env python3
"""Check the native App catalogue against independent, attributed preset data."""
from pathlib import Path
import json
import re
ROOT = Path(__file__).resolve().parents[1]
golden = json.loads((ROOT / "tests/fixtures/preset-catalog.json").read_text(encoding="utf-8"))
source = (ROOT / "companion/src/main/kotlin/haaru/ui/Presets.kt").read_text(encoding="utf-8")
quoted = r'("(?:\\.|[^"\\])*")'
countries = re.findall(r'CountryPreset\(' + quoted + r',\s*' + quoted + r'\)', source)
carriers = re.findall(r'CarrierPreset\(' + quoted + r',\s*' + quoted + r',\s*' + quoted + r',\s*' + quoted + r'\)', source)
assert len(countries) == len(golden["countries"]) == 28
assert len(carriers) == len(golden["carriers"]) == 74
assert [json.loads(code) for code, _ in countries] == golden["countries"]
values = [tuple(json.loads(value) for value in item) for item in carriers]
assert [(display, region) for _, _, display, region in values] == [tuple(item) for item in golden["carriers"]]
assert len({item[0] for item in values}) == 74
print("Native preset catalogue: all 28 countries and 74 carrier values verified")
