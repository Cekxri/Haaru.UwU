#!/usr/bin/env python3
"""Publication must reject private data even when its filename looks harmless."""
import sys
from pathlib import Path
import tempfile
import unittest
import io
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from privacy_guard import audit, check_bytes, publishable, scan_nested

class PrivacyTest(unittest.TestCase):
    def test_private_outputs_are_not_publishable(self):
        for name in ["tools/signing/password.txt", "dist/device-report.json", "companion/local.properties",
                     "module/data/state.properties", "module/webroot/appearance.json", "companion/private.jks",
                     "companion/build/classes.dex", "ui/src/old.kt", "upstream/source.kt"]:
            self.assertFalse(publishable(name), name)
        self.assertTrue(publishable("companion/src/main/AndroidManifest.xml"))
        self.assertTrue(publishable("gradle/wrapper/gradle-wrapper.jar"))
        self.assertTrue(publishable("companion/src/main/kotlin/haaru/ui/Models.kt"))

    def test_secrets_inside_normal_source_are_rejected(self):
        values = [b"-----BEGIN " + b"PRIVATE KEY-----", b"ghp_" + b"A" * 40,
                  b"C:" + b"/Us" + b"ers/PrivatePerson/project", b"/home/" + b"PrivatePerson/config"]
        for value in values: self.assertTrue(check_bytes("src/file.txt", value, []))

    def test_capture_and_private_marker_are_rejected_without_exposing_value(self):
        value = b"codex-" + b"clipboard-00000000-0000-0000-0000-000000000000"
        self.assertTrue(check_bytes("docs/example.md", value, []))
        secret = b"synthetic-sensitive-marker"
        result = check_bytes("src/test.txt", secret, [secret])
        self.assertEqual(result, [{"file": "src/test.txt", "kind": "private-local-marker"}])

    def test_tree_scans_eligible_content_and_excludes_runtime(self):
        with tempfile.TemporaryDirectory(prefix="haaru-private-audit-") as temporary:
            root = Path(temporary)
            (root / "src").mkdir(); (root / "src/a.txt").write_text("public source")
            (root / "module/data").mkdir(parents=True)
            (root / "module/data/state.properties").write_bytes(b"ghp_" + b"A" * 40)
            self.assertTrue(audit(root)["passed"])
            (root / "src/a.txt").write_bytes(b"ghp_" + b"A" * 40)
            self.assertFalse(audit(root)["passed"])

    def test_nested_release_apk_cannot_hide_private_binary_content(self):
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", b"ghp_" + b"A" * 40)
        module = io.BytesIO()
        with zipfile.ZipFile(module, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("app/Haaru.apk", apk.getvalue())
        result = scan_nested("module.zip", module.getvalue(), [])
        self.assertTrue(any(item["file"] == "module.zip!app/Haaru.apk!classes.dex" for item in result))

if __name__ == "__main__": unittest.main(verbosity=2)
