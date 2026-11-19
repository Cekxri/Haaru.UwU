#!/usr/bin/env python3
"""The module action launches the native App or safely retries its matching installer."""
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash") or "C:/Program Files/Git/bin/bash.exe"

class NativeActionTest(unittest.TestCase):
    def run_action(self, installed=False, disabled=False, install_ok=True):
        temporary = tempfile.TemporaryDirectory(prefix="haaru-native-action-")
        root = Path(temporary.name); module = root / "haaru"; tools = root / "tools"
        (module / "bin").mkdir(parents=True); tools.mkdir()
        state = root / "installed"; calls = root / "calls"
        if installed: state.touch()
        if disabled: (module / "disable").touch()
        action = module / "action.sh"
        action.write_text((ROOT / "module/action.sh").read_text(encoding="utf-8").replace("/system/bin/sh", shlex.quote(BASH.replace("\\", "/"))))
        pm = tools / "pm"; pm.write_text('#!/bin/sh\n[ -f "$HAARU_ACTION_STATE" ] || exit 1\necho package:/data/app/fixture/base.apk\n')
        am = tools / "am"; am.write_text('#!/bin/sh\necho "launch $*" >> "$HAARU_ACTION_CALLS"\n')
        install = module / "bin/install-companion.sh"
        install.write_text('#!/bin/sh\necho "install $2" >> "$HAARU_ACTION_CALLS"\n' +
                           ('touch "$HAARU_ACTION_STATE"\nexit 0\n' if install_ok else 'echo private-internal-json\nexit 2\n'))
        for file in [pm, am, install]: file.chmod(0o755)
        env = os.environ.copy(); env.update(PATH=str(tools) + os.pathsep + env['PATH'],
            HAARU_ACTION_STATE=str(state), HAARU_ACTION_CALLS=str(calls))
        result = subprocess.run([BASH, action.as_posix()], env=env, capture_output=True, text=True)
        text = calls.read_text() if calls.exists() else ""
        temporary.cleanup(); return result, text

    def test_installed_app_opens_without_an_install_or_json_console(self):
        result, calls = self.run_action(installed=True)
        self.assertEqual(result.returncode, 0); self.assertNotIn("install retry", calls)
        self.assertIn("dev.cekxri.haaru/.MainActivity", calls)

    def test_missing_app_retries_then_opens_native_activity(self):
        result, calls = self.run_action()
        self.assertEqual(result.returncode, 0); self.assertIn("install retry", calls)
        self.assertIn("launch", calls); self.assertIn("Preparing", result.stdout)

    def test_disabled_module_never_runs_the_installer(self):
        result, calls = self.run_action(disabled=True)
        self.assertNotEqual(result.returncode, 0); self.assertEqual(calls, "")

    def test_install_failure_is_bilingual_and_does_not_dump_internal_output(self):
        result, calls = self.run_action(install_ok=False)
        self.assertIn("install retry", calls)
        self.assertNotEqual(result.returncode, 0); self.assertNotIn("launch", calls)
        self.assertIn("App 尚未裝好", result.stdout); self.assertIn("not installed yet", result.stdout)
        self.assertNotIn("private-internal-json", result.stdout)

if __name__ == "__main__": unittest.main(verbosity=2)
