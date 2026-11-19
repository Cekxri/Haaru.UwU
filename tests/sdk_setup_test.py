#!/usr/bin/env python3
"""SDK reproduction checks without modifying the real SDK or accepting licences."""
import hashlib
from pathlib import Path
import stat
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import setup_android_sdk as setup


class SdkSetupTest(unittest.TestCase):
    def test_api_minor_and_license_metadata(self):
        for package in setup.PACKAGES:
            root = ET.fromstring(setup.metadata(package))
            license_node = root.find("license")
            self.assertEqual(hashlib.sha1(license_node.text.strip().encode()).hexdigest(),
                             setup.LICENSES[package["license"]])
            local = root.find("localPackage")
            self.assertEqual(local.get("path"), package["path"])
            self.assertEqual([int(node.text) for node in local.find("revision")], package["revision"])
            self.assertEqual(local.find("uses-license").get("ref"), package["license"])
            if package["path"].startswith("platforms;"):
                self.assertEqual(local.find("type-details/api-level").text, "37.2")
                self.assertEqual(local.find("type-details/extension-level").text, "24")
                self.assertEqual(local.find("type-details").get("{" + setup.XSI + "}type"),
                                 "sdk:platformDetailsType")

    def test_installed_files_and_api_metadata_must_both_match(self):
        package = setup.PACKAGES[0]
        with tempfile.TemporaryDirectory(prefix="haaru-sdk-fixture-") as temporary:
            root = Path(temporary)
            archive = root / "platform.zip"
            destination = root / "installed"
            destination.mkdir()
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("android-37.2/android.jar", b"actual compiled API content")
                z.writestr("android-37.2/source.properties", b"AndroidVersion.ApiLevel=37.2\n")
            with zipfile.ZipFile(archive) as z:
                for name in z.namelist():
                    (destination / Path(name).name).write_bytes(z.read(name))
            (destination / "package.xml").write_bytes(setup.metadata(package))
            self.assertTrue(setup.installed_matches(archive, package, destination))
            (destination / "android.jar").write_bytes(b"different API content")
            self.assertFalse(setup.installed_matches(archive, package, destination))
            (destination / "android.jar").write_bytes(b"actual compiled API content")
            wrong = setup.metadata(package).replace(b"<api-level>37.2</api-level>", b"<api-level>37</api-level>")
            (destination / "package.xml").write_bytes(wrong)
            self.assertFalse(setup.installed_matches(archive, package, destination))

    def test_archive_entry_cannot_escape_or_redirect_install(self):
        package = setup.PACKAGES[0]
        for name in ("android-37.2/../../secret", "/outside/android.jar", "C:/outside", "wrong-root/android.jar", "android-37.2\\file"):
            info = zipfile.ZipInfo(name)
            info.filename = name
            with self.assertRaises(ValueError, msg=name):
                setup.relative_entry(info, package)
        link = zipfile.ZipInfo("android-37.2/android.jar")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(ValueError):
            setup.relative_entry(link, package)
        self.assertEqual(setup.relative_entry(zipfile.ZipInfo("android-37.2/android.jar"), package), Path("android.jar"))

    def test_destructive_target_stays_within_sdk_package(self):
        with self.assertRaises(ValueError):
            setup.inside_sdk(setup.SDK)
        with self.assertRaises(ValueError):
            setup.inside_sdk(ROOT / "outside-sdk")
        self.assertEqual(setup.inside_sdk(setup.SDK / "platforms/android-37.2"),
                         (setup.SDK / "platforms/android-37.2").resolve())


if __name__ == "__main__":
    unittest.main(verbosity=2)
