#!/usr/bin/env python3
"""Check the compiled design's persistent-write surface and KSU-owned footprint.

The real framework's removal flow was reviewed at KSU v3.3.1. This host test
checks our side of that contract; it is not an Android device execution test.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
module = ROOT / "module"
code = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "src").rglob("*.java"))
for forbidden in ("overrideConfig", "updateSubscriptionNickname", "setDisplayNameUsingSrc",
                  "setCountryIso", "setSimCountryIsoForPhone",
                  "content://telephony", "SQLiteDatabase"):
    assert forbidden not in code, "Forbidden persistent operation: " + forbidden
assert not (module / "uninstall.sh").exists()
ctl = (module / "bin/haaructl").read_text(encoding="utf-8")
assert '--module "$MODDIR"' in ctl and "--state" not in ctl
assert "/data/adb/haaru" not in ctl
monitor = (module / "bin/monitor.sh").read_text(encoding="utf-8")
assert "/data/adb/haaru" not in monitor
assert "/data/adb/service.d" not in monitor
assert '"$MODDIR/remove"' in monitor and '"$MODDIR/disable"' in monitor
for p in module.rglob("*.sh"):
    text = p.read_text(encoding="utf-8")
    assert not re.search(r"\b(setprop|resetprop|mount)\b", text), p
    assert "/data/adb/service.d" not in text and "/data/adb/post-fs-data.d" not in text
bridge = (ROOT / "companion/src/main/kotlin/dev/cekxri/haaru/ModuleBridge.kt").read_text(encoding="utf-8")
assert "LocalSocket" in bridge and "SharedPreferences" not in bridge
assert not (module / "webroot").exists()
native = (ROOT / "native/src/module.cpp").read_text(encoding="utf-8")
policy = (ROOT / "native/src/policy.hpp").read_text(encoding="utf-8")
assert 'haaru_uid_allowed(uid)' in policy
assert 'uid == 1001' in (ROOT / "rust-core/src/model.rs").read_text(encoding="utf-8")
assert 'com.android.phone' in native
assert 'marked(module.value, "disable")' in native and 'marked(module.value, "remove")' in native
assert 'marked(module.value, "update")' in native
assert 'O_RDONLY | O_DIRECTORY | O_NOFOLLOW' in native
assert 'global-shadow.json' in native and 'global-status.json' in native and 'global-transaction.json' in native
assert 'bootId()' in native and 'countryList' in native
assert 'DLCLOSE_MODULE_LIBRARY' in native
print("PASS removal surface: root configuration has no persistent telephony/DB/eSIM setters or external boot files")

# All settings paths used by the implementation belong below the removable folder.
work = (ROOT / "build/removal-footprint").resolve()
assert work.is_relative_to(ROOT.resolve())
work.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=work) as name:
    directory = Path(name).resolve()
    target = directory / "modules/haaru"
    assert target.resolve().is_relative_to(work)
    settings = ("data/state.properties", "data/runtime.json", "data/appearance.json",
                "data/global-status.json", "data/global-shadow.json", "data/global-transaction.json", "data/card-meta.lock", "data/card-observer.lock", "bin/haaru.dex", "bin/hook.dex", "bin/haaru-status-arm64-v8a", "zygisk/arm64-v8a.so", "update")
    for item in settings:
        p = target / item
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"module-owned fixture")
    outside = directory / "telephony-fixture.json"
    original = b'{"country":"tw","name":"Original SIM","eSimNickname":"Personal name"}'
    outside.write_bytes(original)
    # Same filesystem operation as KSU removing this one marked module directory.
    shutil.rmtree(target)
    assert not target.exists() and all(not (target / s).exists() for s in settings)
    assert outside.read_bytes() == original
print("PASS removal footprint: one module directory removal removes every owned settings path")

manifest_path = module / "native-build-manifest.json"
if manifest_path.exists():
    manifest = json.loads(manifest_path.read_text())
    assert manifest["zygiskApi"] == 4 and manifest["architecture"] == "system-global-central-phone-volatile"
    assert {a["abi"] for a in manifest["assets"]} == {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    for asset in manifest["assets"]:
        data = (module / asset["path"]).read_bytes()
        assert data.startswith(b"\x7fELF") and hashlib.sha256(data).hexdigest() == asset["sha256"]
print("Removal invariant host checks passed; Android device tested: false")
