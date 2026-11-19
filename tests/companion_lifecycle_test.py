#!/usr/bin/env python3
"""Execute production shell lifecycle against temporary host files, never Android."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash") or "C:/Program Files/Git/bin/bash.exe"


def metadata(directory, identity, version="0.6.2", code=10, name="Haaru", author="Cekxri"):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "module.prop").write_text(f"id={identity}\nname={name}\nauthor={author}\nversion={version}\nversionCode={code}\n", encoding="utf-8")


class LifecycleTest(unittest.TestCase):
    def test_generic_discovery_rejects_ambiguity_and_never_resurrects_removed_data(self):
        with tempfile.TemporaryDirectory(prefix="haaru-discovery-") as temporary:
            modules = Path(temporary)
            script = ROOT / "module/bin/discover-upgrade.sh"
            def find():
                return subprocess.run([BASH, "-c", '. "$1"; haaru_find_upgrade "$2" haaru', "fixture", script.as_posix(), modules.as_posix()],
                                      capture_output=True, text=True)
            first = modules / "earlier_build"; metadata(first, "earlier_build")
            other = modules / "unrelated"; metadata(other, "unrelated", name="Other")
            self.assertEqual(find().stdout, first.as_posix())
            second = modules / "another_build"; metadata(second, "another_build")
            self.assertNotEqual(find().returncode, 0)
            (second / "remove").touch()
            self.assertEqual(find().stdout, first.as_posix())
            (first / "remove").touch()
            self.assertEqual(find().stdout, "")

    def fixture(self, root):
        modules = root / "modules"; old = modules / "earlier_build"; new = modules / "haaru"
        metadata(old, "earlier_build"); metadata(new, "haaru", "0.7.1", 12)
        for module in (old, new):
            (module / "data").mkdir(); (module / "webroot").mkdir(); (module / "bin").mkdir()
            (module / "data/state.properties").write_bytes(b"preserved settings fixture\n")
            (module / ("data/appearance.json" if module == new else "webroot/appearance.json")).write_bytes(b'{"glass":true}\n')
        (old / "disable").touch()
        digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        journal = ("sourceId=earlier_build\nsourceVersion=0.6.2\nsourceVersionCode=10\ntargetVersion=0.7.1\ntargetVersionCode=12\nsourcePrefsPath=webroot/appearance.json\n"
                   + "sourceStateSha256=" + digest(old / "data/state.properties") + "\n"
                   + "sourcePrefsSha256=" + digest(old / "webroot/appearance.json") + "\n"
                   + "targetStateSha256=" + digest(new / "data/state.properties") + "\n"
                   + "targetPrefsSha256=" + digest(new / "data/appearance.json") + "\n")
        (new / "data/upgrade-handoff.properties").write_text(journal)
        shutil.copyfile(ROOT / "module/bin/discover-upgrade.sh", new / "bin/discover-upgrade.sh")
        text = (ROOT / "module/bin/finalize-upgrade.sh").read_text().replace("/data/adb/modules", modules.as_posix())
        script = root / "finalize.sh"; script.write_text(text)
        return old, new, script

    def test_remove_commits_only_after_promotion_and_both_snapshots_match(self):
        with tempfile.TemporaryDirectory(prefix="haaru-handoff-") as temporary:
            old, new, script = self.fixture(Path(temporary))
            original = (old / "data/state.properties").read_bytes()
            not_promoted = new.parent.parent / "modules_update/haaru"
            not_promoted.mkdir(parents=True)
            self.assertEqual(subprocess.run([BASH, script.as_posix(), not_promoted.as_posix()]).returncode, 0)
            self.assertFalse((old / "remove").exists())
            (new / "data/appearance.json").write_bytes(b"tampered snapshot")
            self.assertNotEqual(subprocess.run([BASH, script.as_posix(), new.as_posix()]).returncode, 0)
            self.assertFalse((old / "remove").exists())
            (new / "data/appearance.json").write_bytes(b'{"glass":true}\n')
            self.assertEqual(subprocess.run([BASH, script.as_posix(), new.as_posix()]).returncode, 0)
            self.assertTrue((old / "remove").exists())
            self.assertFalse((new / "data/upgrade-handoff.properties").exists())
            self.assertEqual((old / "data/state.properties").read_bytes(), original)

    def test_failed_ksu_stage_keeps_unique_source_data_and_is_recoverable(self):
        with tempfile.TemporaryDirectory(prefix="haaru-stage-failure-") as temporary:
            old, new, script = self.fixture(Path(temporary))
            snapshot = (old / "data/state.properties").read_bytes()
            # KSU never promoted the staged target, so its boot finalizer never runs.
            self.assertTrue(new.resolve().is_relative_to(Path(temporary).resolve()))
            shutil.rmtree(new)
            failed_stage = Path(temporary) / "modules_update/haaru"
            failed_stage.mkdir(parents=True)
            self.assertEqual(subprocess.run([BASH, script.as_posix(), failed_stage.as_posix()]).returncode, 0)
            self.assertTrue((old / "disable").exists())
            self.assertFalse((old / "remove").exists())
            self.assertEqual((old / "data/state.properties").read_bytes(), snapshot)
            (old / "disable").unlink()  # The owner can re-enable the preserved module after a failed stage.
            self.assertFalse((old / "disable").exists())
            self.assertEqual((old / "data/state.properties").read_bytes(), snapshot)

    def test_changed_source_snapshot_never_removes_the_previous_module(self):
        with tempfile.TemporaryDirectory(prefix="haaru-source-handoff-") as temporary:
            old, new, script = self.fixture(Path(temporary))
            (old / "data/state.properties").write_bytes(b"changed after the upgrade snapshot\n")
            env = os.environ.copy()
            # An inherited valid path must not bypass the source-state check.
            env["HAARU_SOURCE_PREFS_PATH"] = "webroot/appearance.json"
            result = subprocess.run([BASH, script.as_posix(), new.as_posix()], env=env)
            self.assertEqual(result.returncode, 2)
            self.assertFalse((old / "remove").exists())
            self.assertTrue((new / "data/upgrade-handoff.properties").exists())

    def test_recovery_defers_and_boot_retries_only_until_confirmation(self):
        with tempfile.TemporaryDirectory(prefix="haaru-auto-boot-") as temporary:
            root = Path(temporary); module = root / "module"; metadata(module, "haaru", "0.7.0", 11)
            (module / "app").mkdir(); (module / "app/Haaru.apk").write_bytes(b"fixture"); (module / "app/install.prop").write_text("fixture")
            tools = root / "tools"; tools.mkdir()
            (tools / "getprop").write_text('#!/bin/sh\nprintf "%s" "$HAARU_TEST_BOOT_READY"\n')
            (tools / "sleep").write_text('#!/bin/sh\nexit 0\n')
            process = tools / "app_process"
            process.write_text('#!/bin/sh\nn=$(cat "$HAARU_TEST_COUNT" 2>/dev/null); n=${n:-0}; n=$((n+1)); echo "$n" > "$HAARU_TEST_COUNT"\nif [ "$HAARU_TEST_PROCESS_MODE" = crash ]; then awk \'BEGIN { for(i=0;i<2000;i++) print "synthetic framework crash line" }\' >&2; exit 1; fi\nif [ "$n" = 1 ]; then echo \'{"state":"pending","verified":false}\'; else echo \'{"state":"ready","verified":true}\'; fi\n')
            for file in tools.iterdir(): file.chmod(0o755)
            script = root / "install.sh"
            script.write_text((ROOT / "module/bin/install-companion.sh").read_text()
                              .replace("/system/bin/app_process", process.as_posix()).replace("sleep 15", "sleep 0"))
            counter = root / "calls"
            env = os.environ.copy(); env.update(PATH=str(tools) + os.pathsep + env["PATH"], HAARU_TEST_BOOT_READY="0", HAARU_TEST_COUNT=str(counter))
            pending = subprocess.run([BASH, script.as_posix(), module.as_posix(), "provision"], env=env, capture_output=True, text=True)
            self.assertEqual(pending.returncode, 3)
            self.assertFalse(counter.exists())
            self.assertEqual(json.loads((module / "data/companion-install.json").read_text())["state"], "pending")
            env["HAARU_TEST_BOOT_READY"] = "1"
            installed = subprocess.run([BASH, script.as_posix(), module.as_posix(), "boot"], env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(installed.returncode, 0, installed.stderr)
            self.assertEqual(counter.read_text().strip(), "2")
            counter.unlink(); (module / "disable").touch()
            stopped = subprocess.run([BASH, script.as_posix(), module.as_posix(), "boot"], env=env, capture_output=True, text=True)
            self.assertEqual(stopped.returncode, 0)
            self.assertFalse(counter.exists())
            retry = subprocess.run([BASH, script.as_posix(), module.as_posix(), "retry"], env=env, capture_output=True, text=True)
            self.assertEqual(retry.returncode, 2)
            self.assertFalse(counter.exists())
            (module / "disable").unlink(); env["HAARU_TEST_PROCESS_MODE"] = "crash"
            crashed = subprocess.run([BASH, script.as_posix(), module.as_posix(), "boot"], env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(crashed.returncode, 2)
            self.assertEqual(counter.read_text().strip(), "1", "permanent bootstrap crash must not retry 40 times")
            status = json.loads((module / "data/companion-install.json").read_text())
            self.assertEqual((status["state"], status["stage"], status["exitCode"]), ("error", "app-process", 1))
            stderr = module / "data/companion-install-stderr.log"
            self.assertLessEqual(stderr.stat().st_size, 16384)
            self.assertIn("synthetic framework crash", stderr.read_text())
            counter.unlink(); (module / "app/Haaru.apk").unlink()
            (module / "data/companion-install.json").write_text('{"state":"pending","message":"old pending","verified":false}')
            missing = subprocess.run([BASH, script.as_posix(), module.as_posix(), "retry"], env=env, capture_output=True, text=True)
            self.assertEqual(missing.returncode, 2)
            status = json.loads((module / "data/companion-install.json").read_text())
            self.assertEqual((status["state"], status["stage"]), ("error", "payload"))
            self.assertFalse(counter.exists(), "missing payload must fail before starting app_process")
            (module / "module.prop").unlink(); shutil.rmtree(module / "data")
            removed = subprocess.run([BASH, script.as_posix(), module.as_posix(), "retry"], env=env, capture_output=True, text=True)
            self.assertEqual(removed.returncode, 2)
            self.assertFalse((module / "data").exists(), "an absent module must not be recreated by the installer")


if __name__ == "__main__":
    unittest.main(verbosity=2)
