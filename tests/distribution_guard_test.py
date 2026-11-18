#!/usr/bin/env python3
"""Exercise the real release guard against accidentally bundled live settings."""
import importlib.util
from pathlib import Path
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("haaru_release_build", ROOT / "build.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

with tempfile.TemporaryDirectory(prefix="haaru-distribution-") as temporary:
    module = Path(temporary) / "module"
    (module / "bin").mkdir(parents=True)
    (module / "module.prop").write_text("id=haaru\nversion=fixture\n")
    (module / "bin/haaru.dex").write_bytes(b"fixture code")
    builder.validate_module_distribution(module)
    count = 1
    for name in ("data/state.properties", "data/runtime.json", "data/global-shadow.json",
                 "data/global-status.json", "data/global-transaction.json", "data/arbitrary-user-data",
                 "webroot/appearance.json", "disable", "remove", "update", "bin/lock", "webroot/asset.tmp"):
        path = module / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"private mutable fixture")
        try:
            builder.validate_module_distribution(module)
        except ValueError as error:
            assert name in str(error), (name, str(error))
        else:
            raise AssertionError("Live data was accepted for distribution: " + name)
        path.unlink()
        count += 1
    builder.validate_module_distribution(ROOT / "module")
    count += 1
print(f"Distribution guard: {count} live-data packaging checks passed")
