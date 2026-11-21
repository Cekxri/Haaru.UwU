#!/usr/bin/env python3
"""Offline-by-default Java8 -> DEX -> reproducible KernelSU ZIP build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tomllib
import urllib.request
import zipfile
from release_support import companion_install_properties, companion_properties, source_eligible, validate_backend_dex, validate_companion

ROOT = Path(__file__).resolve().parent
R8_VERSION = "8.6.27"
R8_SHA256 = "9007144728d47b5f11338c9292d5b74899b6579ad9ddc6942d419e3600c3d6d7"
R8_URL = f"https://dl.google.com/dl/android/maven2/com/android/tools/r8/{R8_VERSION}/r8-{R8_VERSION}.jar"

def run(args):
    # Relative project arguments also work with Windows Java launchers whose
    # argv conversion cannot represent a Unicode workspace path.
    argv = []
    for arg in args:
        if isinstance(arg, Path) and arg.is_relative_to(ROOT):
            arg = arg.relative_to(ROOT)
        argv.append(str(arg))
    subprocess.run(argv, cwd=ROOT, check=True)

def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def empty_owned(path):
    resolved = path.resolve()
    if not resolved.is_relative_to(ROOT) or resolved == ROOT:
        raise RuntimeError("Refusing to clear a directory outside the project")
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)

def archive(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data, mode in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100000 | mode) << 16
            z.writestr(info, data)

def validate_module_distribution(directory):
    """Never ship a developer's live settings or pending lifecycle markers."""
    directory = Path(directory)
    for path in directory.rglob("*"):
        if path.is_symlink():
            raise ValueError("Module distribution must not contain symbolic links: " + str(path))
        if not path.is_file():
            continue
        relative = path.relative_to(directory)
        name = relative.as_posix()
        if path.suffix.lower() == ".apk":
            raise ValueError("App APK is injected from the verified dist artefact; do not stage another APK: " + name)
        if relative.parts[0] == "data" or name == "webroot/appearance.json" or name in {"disable", "remove", "update"} \
                or path.name == "lock" or path.name.endswith(".tmp"):
            raise ValueError("Mutable settings or lifecycle files cannot be packaged: " + name)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download-tools", action="store_true", help="Fetch hash-pinned official D8 if absent")
    parser.add_argument("--skip-tests", action="store_true", help="Developer-only: skip verification; manifest records it")
    args = parser.parse_args()
    properties = dict(line.split("=", 1) for line in (ROOT / "module/module.prop").read_text(encoding="utf-8").splitlines() if "=" in line)
    version = properties["version"]
    cargo_version = tomllib.loads((ROOT / "rust-core/Cargo.toml").read_text(encoding="utf-8"))["package"]["version"]
    if (version + ".0" if version.count(".") == 1 else version) != cargo_version:
        raise SystemExit("Module and observer build versions must agree")
    validate_module_distribution(ROOT / "module")
    companion_manifest, companion_verified = validate_companion(ROOT, version, properties["versionCode"])
    companion_auth = companion_properties(companion_manifest)
    companion_auth_path = ROOT / "module/companion.prop"
    if not companion_auth_path.is_file() or companion_auth_path.read_bytes() != companion_auth:
        companion_auth_path.write_bytes(companion_auth)
    install_metadata = companion_install_properties(companion_manifest)
    install_metadata_path = ROOT / "module/app/install.prop"
    install_metadata_path.parent.mkdir(parents=True, exist_ok=True)
    install_metadata_path.write_bytes(install_metadata)
    if (ROOT / "module/webroot").exists():
        raise SystemExit("Native-only releases must not include a KernelSU WebUI directory")
    native_manifest = json.loads((ROOT / "module/native-build-manifest.json").read_text(encoding="utf-8"))
    rust_manifest = json.loads((ROOT / "module/rust-build-manifest.json").read_text(encoding="utf-8"))
    if rust_manifest.get("buildMode") != "built-and-staged" or native_manifest.get("rustCoreSourceSha256") != rust_manifest.get("sourceTreeSha256"):
        raise SystemExit("Build the Rust core and its native bridge before packaging")
    for record in rust_manifest["sources"]:
        path = ROOT / record["path"]
        if not path.is_file() or hash_file(path) != record["sha256"]:
            raise SystemExit("Stale Rust core build: " + record["path"])
    if {asset["abi"] for asset in rust_manifest["assets"]} != {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}:
        raise SystemExit("Release requires all four Rust migrator ABIs")
    for asset in rust_manifest["assets"]:
        if hash_file(ROOT / "module" / asset["path"]) != asset["sha256"]:
            raise SystemExit("Rust migrator asset SHA-256 mismatch")
    if native_manifest.get("buildMode") != "built-and-staged" or native_manifest.get("zygiskApi") != 4:
        raise SystemExit("Build the read-only native hooks using build_native.py")
    for record in native_manifest["sources"]:
        p = ROOT / record["path"]
        if not p.is_file() or hash_file(p) != record["sha256"]:
            raise SystemExit("Stale native build; rerun build_native.py: " + record["path"])
    if {a["abi"] for a in native_manifest["assets"]} != {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}:
        raise SystemExit("Release requires all four native ABIs")
    for asset in native_manifest["assets"]:
        if hash_file(ROOT / "module" / asset["path"]) != asset["sha256"]:
            raise SystemExit("Native asset SHA-256 mismatch")
    tools = ROOT / "tools"
    tools.mkdir(exist_ok=True)
    r8 = tools / f"r8-{R8_VERSION}.jar"
    if not r8.exists() and args.download_tools:
        urllib.request.urlretrieve(R8_URL, r8)
    if not r8.exists():
        raise SystemExit("Missing D8. Run build.py --download-tools (requires network), or place the pinned jar in tools.")
    if hash_file(r8) != R8_SHA256:
        raise SystemExit("D8 SHA-256 mismatch")
    build = ROOT / "build"
    empty_owned(build)
    classes = build / "classes"
    classes.mkdir()
    sources = sorted((ROOT / "src").rglob("*.java"))
    run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-d", classes, *sources])
    if not args.skip_tests:
        test_classes = build / "test-classes"
        test_classes.mkdir()
        core_tests = sorted((ROOT / "tests" / "com").rglob("*.java"))
        run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", classes,
             "-d", test_classes, *core_tests])
        cp = "build/classes" + os.pathsep + "build/test-classes"
        run(["java", "-cp", cp, "com.github.haaru.ksu.ControllerTest"])
        run(["java", "-cp", cp, "com.github.haaru.ksu.UpgradeTest"])
        run(["java", "-cp", cp, "com.github.haaru.ksu.CompanionProtocolTest"])
        run(["java", "-cp", cp, "com.github.haaru.ksu.CompanionInstallationTest"])
        run(["python", "tests/installer_upgrade_test.py", "--java-oracle"])
        run(["python", "tests/distribution_guard_test.py"])
        run(["python", "tests/release_support_test.py"])
        run(["python", "tests/privacy_guard_test.py"])
        run(["python", "tests/sdk_setup_test.py"])
        run(["python", "tests/companion_lifecycle_test.py"])
        run(["python", "tests/native_action_test.py"])
        run(["python", "tests/backend_dex_contract_test.py"])
        fixtures = ROOT / "tests" / "fixtures-current"
        if fixtures.exists():
            adapter_classes = build / "adapter-tests"
            adapter_classes.mkdir()
            adapter = sorted(fixtures.rglob("*.java")) + sorted((ROOT / "tests" / "adapter").rglob("*.java"))
            run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", classes,
                 "-d", adapter_classes, *adapter])
            run(["java", "-cp", "build/classes" + os.pathsep + "build/adapter-tests",
                 "com.github.haaru.ksu.AndroidBackendTest"])
            run(["java", "-cp", "build/classes" + os.pathsep + "build/adapter-tests",
                 "com.github.haaru.ksu.CompanionAndroidPlatformTest"])
            legacy_classes = build / "adapter-legacy"
            legacy_classes.mkdir()
            common = [p for p in fixtures.rglob("*.java") if p.name != "ICarrierConfigLoader.java"]
            legacy = sorted((ROOT / "tests" / "fixtures-legacy").rglob("*.java"))
            legacy += sorted((ROOT / "tests" / "adapter-legacy").rglob("*.java"))
            run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", classes,
                 "-d", legacy_classes, *common, *legacy])
            run(["java", "-cp", "build/classes" + os.pathsep + "build/adapter-legacy",
                 "com.github.haaru.ksu.AndroidLegacyTest"])
        run(["python", "tests/preset_parity_test.py"])
        hook_tests = build / "hook-tests"
        hook_tests.mkdir()
        hook_sources = sorted((ROOT / "hook/src").rglob("*.java"))
        run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", hook_tests,
             *hook_sources, *sorted((ROOT / "hook/tests").rglob("*.java"))])
        run(["java", "-cp", hook_tests, "org.haaru.hook.HooksTest"])
        run(["java", "-cp", hook_tests, "org.haaru.hook.GlobalHooksTest"])
        run(["java", "-cp", hook_tests, "org.haaru.hook.GlobalHooksCompatibilityTest"])
        run(["python", "tests/global_properties_host_test.py"])
        run(["python", "tests/removal_invariant_test.py"])
        run(["node", "tests/native_contract_test.mjs"])
        bash = shutil.which("bash")
        if not bash and Path("C:/Program Files/Git/bin/bash.exe").exists():
            bash = "C:/Program Files/Git/bin/bash.exe"
        if not bash:
            raise SystemExit("bash is required for shell syntax verification")
        for script in sorted((ROOT / "module").rglob("*")):
            if script.is_file() and (script.suffix == ".sh" or script.name == "haaructl"):
                run([bash, "-n", script])
    jar = build / "haaru.jar"
    archive(jar, [(p.relative_to(classes).as_posix(), p.read_bytes(), 0o644)
                  for p in classes.rglob("*.class")])
    dex_dir = build / "dex"
    dex_dir.mkdir()
    run(["java", "-cp", r8, "com.android.tools.r8.D8", "--no-desugaring", "--min-api", "29",
         "--output", dex_dir, jar])
    dex = dex_dir / "classes.dex"
    validate_backend_dex(dex.read_bytes())
    hook_classes = build / "hook-classes"
    hook_classes.mkdir()
    run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", hook_classes,
         *sorted((ROOT / "hook/src").rglob("*.java"))])
    hook_jar = build / "hook.jar"
    archive(hook_jar, [(p.relative_to(hook_classes).as_posix(), p.read_bytes(), 0o644)
                       for p in hook_classes.rglob("*.class")])
    hook_dex_dir = build / "hook-dex"
    hook_dex_dir.mkdir()
    run(["java", "-cp", r8, "com.android.tools.r8.D8", "--no-desugaring", "--min-api", "29",
         "--output", hook_dex_dir, hook_jar])
    hook_dex = hook_dex_dir / "classes.dex"
    validate_backend_dex(hook_dex.read_bytes())
    entries = []
    for p in (ROOT / "module").rglob("*"):
        if not p.is_file():
            continue
        name = p.relative_to(ROOT / "module").as_posix()
        script = p.suffix == ".sh" or p.name == "haaructl"
        executable = script or p.name.startswith(("haaru-upgrade-", "haaru-status-"))
        data = p.read_bytes()
        if script:
            data = data.replace(b"\r\n", b"\n")
        entries.append((name, data, 0o755 if executable else 0o644))
    entries.append(("bin/haaru.dex", dex.read_bytes(), 0o644))
    entries.append(("bin/hook.dex", hook_dex.read_bytes(), 0o644))
    entries.append(("app/Haaru.apk", (ROOT / "dist" / companion_manifest["apkFile"]).read_bytes(), 0o644))
    for name in ("LICENSE", "NOTICE", "README.md", "MERGED_CHANGES.md", "UI_BUILD.md", "FEATURE_PARITY.md", "CHANGELOG.md", "RELEASE.md", "COMPATIBILITY.md"):
        p = ROOT / name
        if not p.exists():
            raise SystemExit(f"Missing required distribution document: {name}")
        entries.append((name, p.read_bytes(), 0o644))
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    output = dist / f"Haaru-{version}.zip"
    archive(output, entries)
    with zipfile.ZipFile(output) as z:
        if z.testzip() is not None or "module.prop" not in z.namelist() or "bin/haaru.dex" not in z.namelist():
            raise SystemExit("Module archive validation failed")
    source = dist / f"Haaru-{version}-source.zip"
    archive(source, [(p.relative_to(ROOT).as_posix(), p.read_bytes(), 0o644)
                     for p in ROOT.rglob("*") if p.is_file()
                     and not p.is_symlink()
                     and source_eligible(p.relative_to(ROOT).as_posix())])
    manifest = {
        "version": version, "minAndroidApi": 29, "deviceTested": False,
        "moduleName": properties["name"], "moduleAuthor": properties["author"],
        "moduleFile": output.name, "sourceFile": source.name,
        "testsSkipped": args.skip_tests, "r8Version": R8_VERSION, "r8Sha256": R8_SHA256,
        "presetSourceRevision": "6c05f224dc802d0df952db93a6d2852a91c9edb0",
        "moduleSha256": hash_file(output), "sourceSha256": hash_file(source), "dexSha256": hash_file(dex),
        "hookDexSha256": hash_file(hook_dex), "nativeSourceSha256": native_manifest["sourceTreeSha256"],
        "zygiskApi": 4, "systemGlobal": True, "engine": "central-phone-volatile",
        "persistentTelephonyWrites": False, "volatileSystemProperties": ["gsm.sim.operator.iso-country", "gsm.sim.operator.alpha"],
        "settingsStorage": "module-directory", "nativeAbis": [a["abi"] for a in native_manifest["assets"]],
        "settingsPreservedOnUpgrade": True, "upgradeSnapshot": "installation-time",
        "migratedFiles": ["data/state.properties", "data/appearance.json"],
        "rustCore": True, "rustVersion": rust_manifest["rustVersion"], "rustSourceSha256": rust_manifest["sourceTreeSha256"],
        "migrationEngine": "rust-native", "retainedUiLanguage": "Kotlin Compose Android",
        "dynamicDescription": True, "cardObserverIntervalMs": 2000,
        "primaryUi": "native-android-companion", "independentApk": True,
        "companionSourceSha256": companion_manifest["sourceTreeSha256"],
        "apkFile": companion_manifest["apkFile"], "apkSha256": companion_manifest["apkSha256"],
        "applicationId": companion_manifest["applicationId"],
        "signingCertificateSha256": companion_manifest["signingCertificateSha256"],
        "androidBuildTools": companion_manifest["androidBuildTools"],
        "uiStack": companion_manifest["libraries"], "companionArtifactVerified": companion_verified,
        "webUi": False,
        "rootExecution": "ksu-module", "appRequestsSuperuser": False,
        "ipcTransport": "module-local-socket", "ipcProtocol": 1,
        "companionAuthentication": "peer-uid-package-and-apk-signer",
        "companionAuthFile": "companion.prop", "companionAuthSha256": hashlib.sha256(companion_auth).hexdigest(),
        "bundledApk": True, "bundledApkPath": "app/Haaru.apk", "automaticAppInstall": True,
        "appInstallSource": "offline-version-pinned-module-payload", "appDowngradeAllowed": False,
        "appInstallMetadataSha256": hashlib.sha256(install_metadata).hexdigest()
    }
    (dist / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (dist / "SHA256SUMS.txt").write_text(
        f"{hash_file(output)}  {output.name}\n{companion_manifest['apkSha256']}  {companion_manifest['apkFile']}\n"
        f"{hash_file(source)}  {source.name}\n", encoding="ascii")
    print(json.dumps(manifest, indent=2))
    print(f"Built: {output}")

if __name__ == "__main__":
    main()

