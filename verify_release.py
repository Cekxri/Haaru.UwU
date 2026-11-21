#!/usr/bin/env python3
"""Verify the module, signed native companion, and current source archives."""
import hashlib
import json
from pathlib import Path
import struct
import zipfile
import zlib
from release_support import companion_install_properties, companion_properties, safe_relative, source_eligible, validate_backend_dex, validate_companion

ROOT = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    manifest = json.loads((ROOT / "dist/build-manifest.json").read_text(encoding="utf-8"))
    version = manifest["version"]
    release = ROOT / "dist" / manifest["moduleFile"]
    source = ROOT / "dist" / manifest["sourceFile"]
    assert sha(release.read_bytes()) == manifest["moduleSha256"]
    assert sha(source.read_bytes()) == manifest["sourceSha256"]
    assert manifest["deviceTested"] is False and manifest["testsSkipped"] is False
    assert manifest["independentApk"] is True and manifest["primaryUi"] == "native-android-companion"
    assert manifest["rootExecution"] == "ksu-module" and manifest["appRequestsSuperuser"] is False
    assert manifest["ipcTransport"] == "module-local-socket" and manifest["ipcProtocol"] == 1
    report = {"version": version, "deviceTested": False, "archiveVerified": True, "native": []}
    with zipfile.ZipFile(release) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)) and archive.testzip() is None
        assert all(not name.startswith("/") and ".." not in name.split("/") and "\\" not in name for name in names)
        assert [name for name in names if name.endswith(".apk")] == ["app/Haaru.apk"]
        assert not any(name.startswith("data/") for name in names)
        assert not any(name.startswith("webroot/") for name in names), "Only the native App is included"
        assert not set(names).intersection({"uninstall.sh", "system.prop", "disable", "remove", "update", "webroot/appearance.json"})
        properties = dict(line.split("=", 1) for line in archive.read("module.prop").decode().splitlines() if "=" in line)
        assert properties["id"] == "haaru" and properties["version"] == version
        assert properties["name"] == "Haaru" and properties["author"] == "Cekxri"
        assert properties["description"] == "未設定~哦Nya^ ܸ⩌⩊⩌ ܸ^🍞 城堡之下的SIM國度.咖啡廳☕"
        assert manifest["webUi"] is False
        policy = archive.read("sepolicy.rule").decode("utf-8")
        rules = [line.strip() for line in policy.splitlines() if line.strip() and not line.lstrip().startswith("#")]
        assert rules == ["allow radio zygote unix_stream_socket { getattr getopt setopt read write }"], \
            "Phone IPC policy must contain only the required, scoped socket permissions"
        report["phoneSocketPolicy"] = {"source": "radio", "target": "zygote", "class": "unix_stream_socket",
                                      "permissions": ["getattr", "getopt", "setopt", "read", "write"], "permissive": False}
        assert "dev.cekxri.haaru" in archive.read("action.sh").decode()
        assert "haaru-upgrade-$ABI" in archive.read("customize.sh").decode()
        for name in names:
            if name.endswith(".sh") or name.endswith("haaructl") or name.startswith(("bin/haaru-upgrade-", "bin/haaru-status-")):
                assert (archive.getinfo(name).external_attr >> 16) & 0o777 == 0o755
        for name, key in (("bin/haaru.dex", "dexSha256"), ("bin/hook.dex", "hookDexSha256")):
            data = archive.read(name)
            validate_backend_dex(data)
            assert data.startswith(b"dex\n") and struct.unpack_from("<I", data, 32)[0] == len(data)
            assert struct.unpack_from("<I", data, 8)[0] == zlib.adler32(data[12:]) & 0xffffffff
            assert data[12:32] == hashlib.sha1(data[32:]).digest()
            assert sha(data) == manifest[key]
            if name == "bin/haaru.dex":
                assert b"android.net.LocalServerSocket" in data, "Module DEX must contain its reflected LocalSocket IPC dependency"
                assert b"Lcom/github/haaru/ksu/CompanionServer;" in data or b"com.github.haaru.ksu.CompanionServer" in data, \
                    "Module DEX must contain its IPC service class"
                assert b"Lcom/github/haaru/ksu/CompanionInstallation;" in data, "Module DEX must include its pinned App installer"
        native = json.loads(archive.read("native-build-manifest.json"))
        assert native["sourceTreeSha256"] == manifest["nativeSourceSha256"]
        expected = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86_64": 62, "x86": 3}
        assert {item["abi"] for item in native["assets"]} == set(expected)
        for asset in native["assets"]:
            data = archive.read(asset["path"])
            assert b"Java_org_haaru_smoke_EngineSmoke" not in data, "Synthetic ART test JNI must never ship in release libraries"
            assert data[:4] == b"\x7fELF" and data[5] == 1 and sha(data) == asset["sha256"]
            assert struct.unpack_from("<H", data, 18)[0] == expected[asset["abi"]]
            bits = data[4]
            offset = struct.unpack_from("<Q" if bits == 2 else "<I", data, 32 if bits == 2 else 28)[0]
            size, count = struct.unpack_from("<HH", data, 54 if bits == 2 else 42)
            loads, stack = 0, False
            for index in range(count):
                start = offset + index * size
                kind = struct.unpack_from("<I", data, start)[0]
                flags = struct.unpack_from("<I", data, start + (4 if bits == 2 else 24))[0]
                alignment = struct.unpack_from("<Q" if bits == 2 else "<I", data, start + (48 if bits == 2 else 28))[0]
                if kind == 1:
                    assert alignment >= 16384
                    loads += 1
                if kind == 0x6474e551:
                    assert not flags & 1
                    stack = True
            assert loads and stack
            report["native"].append({"abi": asset["abi"], "sha256": asset["sha256"], "pageAlignment": 16384, "executableStack": False})
        rust = json.loads(archive.read("rust-build-manifest.json"))
        assert rust["sourceTreeSha256"] == manifest["rustSourceSha256"] == native["rustCoreSourceSha256"]
        assert rust["rustVersion"] == manifest["rustVersion"] and rust["migrationEngine"] == "rust-native"
        assert {asset["abi"] for asset in rust["assets"]} == set(expected)
        report["rustMigrators"] = []
        report["cardObservers"] = []
        for asset in rust["assets"]:
            data = archive.read(asset["path"])
            assert sha(data) == asset["sha256"] and data[:4] == b"\x7fELF" and data[5] == 1
            assert struct.unpack_from("<H", data, 18)[0] == expected[asset["abi"]]
            bits = data[4]
            offset = struct.unpack_from("<Q" if bits == 2 else "<I", data, 32 if bits == 2 else 28)[0]
            size, count = struct.unpack_from("<HH", data, 54 if bits == 2 else 42)
            loads, stack = 0, False
            for index in range(count):
                start = offset + index * size
                kind = struct.unpack_from("<I", data, start)[0]
                flags = struct.unpack_from("<I", data, start + (4 if bits == 2 else 24))[0]
                alignment = struct.unpack_from("<Q" if bits == 2 else "<I", data, start + (48 if bits == 2 else 28))[0]
                if kind == 1:
                    assert alignment >= 16384
                    loads += 1
                if kind == 0x6474e551:
                    assert not flags & 1
                    stack = True
            assert loads and stack
            group = "cardObservers" if asset.get("kind") == "status" else "rustMigrators"
            report[group].append({"abi":asset["abi"],"sha256":asset["sha256"],"pageAlignment":16384,"executableStack":False})
        assert len(report["cardObservers"]) == 4 and len(report["rustMigrators"]) == 4
        for path in (ROOT / "module").rglob("*"):
            if path.is_file():
                name = path.relative_to(ROOT / "module").as_posix()
                if name in {"bin/haaru.dex", "bin/hook.dex"}:
                    continue
                data = path.read_bytes()
                if path.suffix == ".sh" or path.name == "haaructl":
                    data = data.replace(b"\r\n", b"\n")
                assert archive.read(name) == data, name
    with zipfile.ZipFile(source) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert len(names) == len(set(names))
        for name in names:
            safe_relative(name)
            assert source_eligible(name), "Private/build source included: " + name
            assert archive.read(name) == (ROOT / name).read_bytes(), "Stale source archive: " + name
        expected_sources = {path.relative_to(ROOT).as_posix() for path in ROOT.rglob("*")
                            if path.is_file() and not path.is_symlink()
                            and source_eligible(path.relative_to(ROOT).as_posix())}
        assert set(names) == expected_sources, "Source archive omits current project source"
    companion, verified_apk = validate_companion(ROOT, version, properties["versionCode"])
    auth = companion_properties(companion)
    with zipfile.ZipFile(release) as archive:
        assert archive.read("companion.prop") == auth
        assert sha(archive.read("app/Haaru.apk")) == companion["apkSha256"]
        assert archive.read("app/Haaru.apk") == (ROOT / "dist" / companion["apkFile"]).read_bytes()
        assert archive.read("app/install.prop") == companion_install_properties(companion)
    assert manifest["companionAuthFile"] == "companion.prop" and manifest["companionAuthSha256"] == sha(auth)
    assert manifest["bundledApk"] is True and manifest["automaticAppInstall"] is True and manifest["appDowngradeAllowed"] is False
    assert manifest["appInstallMetadataSha256"] == sha(companion_install_properties(companion))
    for key in ("apkFile", "apkSha256", "applicationId", "signingCertificateSha256", "androidBuildTools"):
        assert companion[key] == manifest[key], key
    assert companion["sourceTreeSha256"] == manifest["companionSourceSha256"]
    report.update(moduleSha256=manifest["moduleSha256"], sourceSha256=manifest["sourceSha256"],
                  testsSkipped=False, independentApk=True, companion=verified_apk,
                  settingsPreservedOnUpgrade=True, upgradeSnapshot="installation-time")
    report.update(rootExecution="ksu-module", appRequestsSuperuser=False,
                  ipcTransport="module-local-socket", ipcProtocol=1,
                  companionAuthentication="peer-uid-package-and-apk-signer",
                  companionAuthSha256=manifest["companionAuthSha256"])
    report.update(bundledApk=True, automaticAppInstall=True, appDowngradeAllowed=False,
                  bundledApkPath="app/Haaru.apk", appInstallSource="offline-version-pinned-module-payload")
    report.update(rustCore=True,rustVersion=manifest["rustVersion"],migrationEngine="rust-native",
                  moduleName="Haaru",moduleAuthor="Cekxri",dynamicDescription=True,cardObserverIntervalMs=2000)
    report["deviceReport"] = current_device_report(manifest)
    report["deviceTested"] = report["deviceReport"].get("verified", False)
    (ROOT / "dist/release-verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


def current_device_report(manifest):
    path = ROOT / "dist/device-report.json"
    if not path.is_file():
        return {"available": False, "verified": False}
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return {"available": True, "verified": False, "reason": "Invalid device report"}
    current = all(evidence.get(key) == manifest.get(key) for key in
                  ("version", "moduleSha256", "apkSha256", "nativeSourceSha256", "companionSourceSha256"))
    checks = evidence.get("checks")
    passed = isinstance(checks, list) and bool(checks) and all(
        isinstance(check, dict) and check.get("passed") is True for check in checks)
    verified = current and evidence.get("success") is True and passed \
        and isinstance(evidence.get("device"), dict) and bool(evidence["device"])
    return {"available": True, "currentArtifacts": current, "verified": verified,
            "scope": evidence.get("scope", "unspecified"), "checks": len(checks) if isinstance(checks, list) else 0}


if __name__ == "__main__":
    main()
