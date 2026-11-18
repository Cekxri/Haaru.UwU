#!/usr/bin/env python3
"""Audit only the explicitly publishable project tree; never print matched secrets."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent
EXCLUDED = {"tools", "build", "dist", "target", "test-classes", "__pycache__", ".git", ".gradle", ".kotlin", "node_modules", "kotlin-js-store"}
PRIVATE_NAMES = {"local.properties", "keystore.properties", "signing.properties", "appearance.json", "device-report.json", "phone-rollback-report.json"}
PRIVATE_EXTENSIONS = {".jks", ".keystore", ".p12", ".pfx", ".key", ".pem", ".log", ".class", ".o", ".obj", ".pdb", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".mp4", ".mov"}
FORBIDDEN = [
    ("private-key", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    ("access-token", re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{35,}|sk-[A-Za-z0-9_-]{32,})")),
    ("personal-home-path", re.compile(rb"(?:[A-Za-z]:[/\\]Users[/\\][A-Za-z0-9_.~-]{2,}|(?<![A-Za-z0-9:/])/(?:home|Users)/[A-Za-z0-9_.~-]{2,})", re.I)),
    ("phone-capture", re.compile(rb"codex-clipboard-[0-9a-f-]{36}|Companion Page " + rb"snapshot|questionItem" + rb"Id|send_user_message_" + rb"question_reply")),
]

def publishable(name):
    p = Path(name)
    parts = p.parts
    if parts and parts[0].lower() in {"upstream", "ui", "research"}: return False
    if str(p).replace("\\", "/") in {"build_ui.py", "tests/ui_browser_test.mjs", "tests/ui_probe.mjs"}: return False
    if any(part.lower() in EXCLUDED for part in parts): return False
    if p.name.lower() in PRIVATE_NAMES or p.suffix.lower() in PRIVATE_EXTENSIONS: return False
    if len(parts) >= 2 and parts[:2] == ("module", "data"): return False
    if str(p).replace("\\", "/").startswith("native/vendor/dobby/"): return False
    if str(p).replace("\\", "/").startswith("native/vendor/") and any(part in {"docs", "tests", "test", "example", "examples", ".github"} for part in parts): return False
    return True

def private_markers():
    path = ROOT / "tools/publication-private-markers.json"
    if not path.exists(): return []
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, list) or not all(isinstance(x, str) and x for x in values): raise ValueError("Invalid private marker input")
    return [x.encode("utf-8") for x in values]

def check_bytes(name, data, markers):
    hits = []
    for label, pattern in FORBIDDEN:
        # Public upstream build examples are not personal workstation data.
        if label == "personal-home-path" and name.split("!", 1)[-1].startswith("native/vendor/"): continue
        if pattern.search(data): hits.append({"file": name, "kind": label})
    if any(marker.lower() in data.lower() for marker in markers): hits.append({"file": name, "kind": "private-local-marker"})
    return hits

def scan_nested(name, data, markers, depth=0, budget=None):
    if budget is None: budget = [512 * 1024 * 1024]
    if depth > 6: return [{"file": name, "kind": "archive-depth-limit"}]
    budget[0] -= len(data)
    if budget[0] < 0: return [{"file": name, "kind": "archive-size-limit"}]
    failures = check_bytes(name, data, markers)
    if data[:2] == b"PK" and zipfile.is_zipfile(io.BytesIO(data)):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for item in archive.infolist():
                if item.is_dir(): continue
                if item.file_size > budget[0]:
                    failures.append({"file": name + "!" + item.filename, "kind": "archive-size-limit"}); continue
                failures += scan_nested(name + "!" + item.filename, archive.read(item), markers, depth + 1, budget)
    return failures

def audit(root=ROOT):
    failures, records = [], []
    markers = private_markers()
    for path in sorted(root.rglob("*")):
        if not path.is_file(): continue
        name = path.relative_to(root).as_posix()
        if not publishable(name): continue
        if path.is_symlink(): failures.append({"file": name, "kind": "symlink"}); continue
        data = path.read_bytes()
        failures += scan_nested(name, data, markers)
        records.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return {"passed": not failures, "filesChecked": len(records), "failures": failures, "sources": records,
            "scope": "Publishable source only; runtime data, device captures, build logs, machine paths and signing secrets excluded."}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--artifact", action="append", type=Path, default=[])
    args = parser.parse_args(); result = audit(args.root.resolve())
    for path in args.artifact:
        result["failures"] += scan_nested(path.name, path.read_bytes(), private_markers())
    result["passed"] = not result["failures"]
    if args.output: args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "sources"}, indent=2))
    if not result["passed"]: raise SystemExit(1)

if __name__ == "__main__": main()
