#!/usr/bin/env python3
"""Create a clean source snapshot for GitHub after the source privacy audit passes."""
import argparse
from pathlib import Path
import shutil
from privacy_guard import audit, publishable

ROOT = Path(__file__).resolve().parent
GENERATED_MODULE = {"native-build-manifest.json", "rust-build-manifest.json", "companion.prop"}

def repository_file(name):
    if not publishable(name): return False
    p = Path(name)
    if p.suffix.lower() in {".apk", ".zip", ".idsig"}: return False
    if p.parts[:2] in {("module", "zygisk"), ("module", "app")}: return False
    if len(p.parts) > 1 and p.parts[0] == "module" and p.name in GENERATED_MODULE: return False
    if p.parts[:2] == ("module", "bin") and p.name.startswith(("haaru-status-", "haaru-upgrade-")): return False
    if name in {"build_ui.py", "tests/ui_browser_test.mjs", "tests/ui_probe.mjs"}: return False
    return True

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    destination = args.destination.resolve()
    if destination == ROOT or destination.is_relative_to(ROOT): raise SystemExit("Public snapshot must be separate from the working project")
    if destination.exists(): raise SystemExit("Use a fresh destination; never overwrite a repository or history")
    result = audit(ROOT)
    if not result["passed"]: raise SystemExit("Source privacy audit failed; publication is blocked")
    destination.mkdir(parents=True)
    count = 0
    for item in result["sources"]:
        name = item["path"]
        if not repository_file(name): continue
        source = ROOT / name; target = destination / name
        if source.is_symlink() or not target.resolve().is_relative_to(destination): raise SystemExit("Unsafe publication path")
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target); count += 1
    final = audit(destination)
    if not final["passed"]: raise SystemExit("Public snapshot privacy audit failed")
    print("Prepared clean public source snapshot:", count, "files")

if __name__ == "__main__": main()
