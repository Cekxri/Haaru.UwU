#!/usr/bin/env python3
"""Release regressions for stale source, signing secrets, and corrupt DEX."""
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from release_support import companion_install_properties, companion_properties, safe_relative, sha, source_eligible, source_tree_hash, validate_apk_ipc_metadata, validate_companion_transport, validate_dex, validate_sources


class ReleaseSupportTest(unittest.TestCase):
    def test_private_signing_and_machine_files_never_enter_source(self):
        for name in ("companion/release.jks", "companion/RELEASE.KEYSTORE", "companion/local.properties",
                     "companion/.gradle/cache.bin", "tools/companion-signing/release.jks",
                     "companion/keystore.properties", "companion/signing.properties",
                     "dist/Haaru.apk", "companion/build/private.jks"):
            self.assertFalse(source_eligible(name), name)
        for name in ("companion/build.gradle.kts", "companion/src/main/AndroidManifest.xml",
                     "companion/src/main/kotlin/MainActivity.kt", "build_companion.py", "RELEASE.md"):
            self.assertTrue(source_eligible(name), name)

    def test_current_source_and_missing_new_files(self):
        with tempfile.TemporaryDirectory(prefix="haaru-source-guard-") as temporary:
            root = Path(temporary)
            path = root / "companion/src/MainActivity.kt"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"current source")
            records = [{"path": "companion/src/MainActivity.kt", "sha256": sha(path.read_bytes()),
                        "bytes": path.stat().st_size}]
            validate_sources(root, records)
            path.write_bytes(b"changed source")
            with self.assertRaisesRegex(ValueError, "Stale companion"):
                validate_sources(root, records)
            path.write_bytes(b"current source")
            (path.parent / "RootBridge.kt").write_bytes(b"new security boundary")
            with self.assertRaisesRegex(ValueError, "omits current source"):
                validate_sources(root, records)

    def test_duplicate_paths_and_private_manifest_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="haaru-manifest-guard-") as temporary:
            root = Path(temporary)
            (root / "companion").mkdir()
            source = root / "companion/a.kt"
            source.write_bytes(b"source")
            record = {"path": "companion/a.kt", "sha256": sha(b"source"), "bytes": 6}
            with self.assertRaisesRegex(ValueError, "Duplicate or private"):
                validate_sources(root, [record, record])
            with self.assertRaisesRegex(ValueError, "Duplicate or private"):
                validate_sources(root, [{"path": "companion/release.jks", "sha256": "0" * 64, "bytes": 0}])

    def test_manifest_hash_is_order_independent_and_includes_content(self):
        records = [{"path": "companion/b.kt", "sha256": "a" * 64, "bytes": 1},
                   {"path": "companion/a.kt", "sha256": "b" * 64, "bytes": 2}]
        self.assertEqual(source_tree_hash(records), source_tree_hash(list(reversed(records))))
        modified = [dict(record) for record in records]
        modified[0]["sha256"] = "c" * 64
        self.assertNotEqual(source_tree_hash(records), source_tree_hash(modified))

    def test_release_paths_cannot_escape(self):
        for name in ("../secret.jks", "/outside.apk", "C:/secret.jks", "companion\\secret.jks", "a/./b", ""):
            with self.assertRaises(ValueError, msg=name):
                safe_relative(name)
        self.assertEqual(str(safe_relative("Haaru-0.6.2.apk")), "Haaru-0.6.2.apk")

    def test_dex_corruption_is_not_accepted_as_an_apk(self):
        dex = bytearray(112)
        dex[:8] = b"dex\n035\0"
        struct.pack_into("<I", dex, 32, len(dex))
        dex[12:32] = hashlib.sha1(dex[32:]).digest()
        struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xffffffff)
        validate_dex(bytes(dex))
        changed = bytearray(dex); changed[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            validate_dex(bytes(changed))
        changed = bytearray(dex); changed[12] ^= 1
        struct.pack_into("<I", changed, 8, zlib.adler32(changed[12:]) & 0xffffffff)
        with self.assertRaisesRegex(ValueError, "signature mismatch"):
            validate_dex(bytes(changed))
        with self.assertRaisesRegex(ValueError, "size mismatch"):
            validate_dex(bytes(dex) + b"extra")

    def test_public_ipc_auth_pin_matches_verified_apk_and_has_no_version_lock(self):
        manifest = {"applicationId": "dev.cekxri.haaru", "signingCertificateSha256": "a" * 64,
                    "versionCode": 10}
        pin = companion_properties(manifest)
        self.assertEqual(pin, b"protocol=1\npackage=dev.cekxri.haaru\ncertificateSha256=" + b"a" * 64 + b"\n")
        manifest["versionCode"] = 11
        self.assertEqual(companion_properties(manifest), pin)
        for changed in ({**manifest, "applicationId": "other.app"},
                        {**manifest, "signingCertificateSha256": "A" * 64},
                        {**manifest, "signingCertificateSha256": "a" * 64 + "\nextra=true"}):
            with self.assertRaises(ValueError):
                companion_properties(changed)

    def test_previous_su_companion_cannot_be_declared_as_module_ipc_release(self):
        with tempfile.TemporaryDirectory(prefix="haaru-app-transport-") as temporary:
            root = Path(temporary)
            source = root / "companion/src/main/kotlin/ModuleBridge.kt"
            source.parent.mkdir(parents=True)
            source.write_text('import android.net.LocalSocket\nval socket = LocalSocket()', encoding="utf-8")
            validate_companion_transport(root)
            source.write_text('val process = ProcessBuilder("su", "-c", "command")', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "LocalSocket"):
                validate_companion_transport(root)
            source.write_text('import android.net.LocalSocket\nval socket = LocalSocket()\nProcessBuilder("su")', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "superuser"):
                validate_companion_transport(root)

    def test_pinned_install_metadata_keeps_exact_old_zip_version(self):
        manifest = {"applicationId": "dev.cekxri.haaru", "signingCertificateSha256": "a" * 64,
                    "versionCode": 11, "version": "0.7.0", "apkSha256": "b" * 64, "apkBytes": 200}
        metadata = companion_install_properties(manifest).decode("ascii")
        self.assertIn("version=0.7.0\nversionCode=11\n", metadata)
        self.assertIn("apkSha256=" + "b" * 64, metadata)
        self.assertNotIn("latest", metadata)
        changed = dict(manifest); changed["version"] = "0.7.0\nother=true"
        with self.assertRaises(ValueError): companion_install_properties(changed)

    def test_actual_compiled_ipc_metadata_requires_integer_protocol(self):
        prefix = 'E: meta-data (line=1)\n A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.cekxri.haaru.IPC_PROTOCOL"\n'
        for value in ('1', '0x00000001', '(type 0x10)0x1'):
            validate_apk_ipc_metadata(prefix + ' A: http://schemas.android.com/apk/res/android:value(0x01010024)=' + value)
        for value in ('2', '0x10', '"1"'):
            with self.assertRaises(ValueError):
                validate_apk_ipc_metadata(prefix + ' A: http://schemas.android.com/apk/res/android:value(0x01010024)=' + value)


if __name__ == "__main__":
    unittest.main(verbosity=2)
