#!/usr/bin/env python3
"""Build the pinned Rust data/migration core and its Android executables."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path, PurePosixPath
import shutil
import ssl
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
CORE = ROOT / "rust-core"
TOOLS = ROOT / "tools/rust"
TARGETS = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7-linux-androideabi",
           "x86_64": "x86_64-linux-android", "x86": "i686-linux-android"}
CLANG_TARGETS = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi",
                 "x86_64": "x86_64-linux-android", "x86": "i686-linux-android"}


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def source_records():
    paths = [Path(__file__), CORE / "Cargo.toml", CORE / "Cargo.lock", CORE / "toolchain-pins.json"]
    paths.extend(path for path in (CORE / "src").rglob("*") if path.is_file())
    records, tree = [], hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.relative_to(ROOT).as_posix()):
        name, checksum = path.relative_to(ROOT).as_posix(), digest(path)
        records.append({"path": name, "sha256": checksum, "bytes": path.stat().st_size})
        tree.update(name.encode() + b"\0" + checksum.encode() + b"\n")
    return records, tree.hexdigest()


def download_tools():
    if os.name != "nt":
        raise SystemExit("Automatic Rust downloads are pinned Windows tools; supply --cargo on other hosts")
    TOOLS.mkdir(parents=True, exist_ok=True)
    prefix = (TOOLS / "toolchain").resolve()
    prefix.mkdir(exist_ok=True)
    pins = json.loads((CORE / "toolchain-pins.json").read_text(encoding="utf-8"))
    context = ssl.create_default_context()
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    for component in pins["components"]:
        archive = TOOLS / component["url"].rsplit("/", 1)[-1]
        if not archive.exists():
            print("Downloading official Rust:", archive.name, flush=True)
            temporary = archive.with_suffix(archive.suffix + ".part")
            with urllib.request.urlopen(component["url"], context=context, timeout=45) as source, temporary.open("wb") as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
            if digest(temporary) != component["sha256"]:
                raise SystemExit("Official Rust component SHA-256 mismatch")
            os.replace(temporary, archive)
        if digest(archive) != component["sha256"]:
            raise SystemExit("Pinned Rust component was modified: " + archive.name)
        with tarfile.open(archive, "r:xz") as package:
            for member in package:
                parts = PurePosixPath(member.name).parts
                # Official packages have package/component/{bin,lib,...} paths.
                if len(parts) < 3 or not member.isfile():
                    continue
                relative = PurePosixPath(*parts[2:])
                if relative.is_absolute() or ".." in relative.parts or relative.parts[0] not in {"bin", "lib", "share", "etc"}:
                    continue
                target = prefix.joinpath(*relative.parts)
                if not target.resolve().is_relative_to(prefix):
                    raise SystemExit("Rust package path escapes its tool directory")
                target.parent.mkdir(parents=True, exist_ok=True)
                with package.extractfile(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
        print("Verified and installed:", component["package"], component["target"], flush=True)


def environment(cargo):
    env = dict(os.environ)
    env["PATH"] = str(cargo.parent) + os.pathsep + env.get("PATH", "")
    env["CARGO_HOME"] = str((TOOLS / "cargo-home").resolve())
    env["RUSTC"] = str(cargo.with_name("rustc.exe" if os.name == "nt" else "rustc"))
    return env


def run(command, env):
    subprocess.run([str(argument) for argument in command], cwd=ROOT, env=env, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download-tools", action="store_true")
    parser.add_argument("--tools-only", action="store_true")
    parser.add_argument("--host-only", action="store_true")
    parser.add_argument("--cargo", type=Path)
    parser.add_argument("--ndk", type=Path, help="Use the existing NDK29 installation on this host")
    args = parser.parse_args()
    if args.download_tools:
        download_tools()
    cargo = (args.cargo or TOOLS / "toolchain/bin/cargo.exe").resolve()
    if not cargo.is_file():
        raise SystemExit("Missing Rust tools; run build_rust.py --download-tools")
    env = environment(cargo)
    version = subprocess.check_output([env["RUSTC"], "--version"], env=env, text=True).strip()
    pins = json.loads((CORE / "toolchain-pins.json").read_text(encoding="utf-8"))
    if not version.startswith("rustc " + pins["rustVersion"] + " "):
        raise SystemExit("Use the pinned Rust " + pins["rustVersion"] + " toolchain")
    print(version, flush=True)
    run([cargo, "--version"], env)
    if args.tools_only:
        return
    run([cargo, "generate-lockfile", "--manifest-path", CORE / "Cargo.toml"], env) if not (CORE / "Cargo.lock").exists() else None
    common = [cargo, "--locked", "--manifest-path", CORE / "Cargo.toml"]
    run([common[0], "test", *common[1:], "--target-dir", CORE / "target/host"], env)
    run([common[0], "build", *common[1:], "--release", "--target-dir", CORE / "target/host"], env)
    records, before = source_records()
    if args.host_only:
        print("Rust host core tests/build passed:", before)
        return
    ndk_root = (args.ndk or ROOT / "tools/native/ndk29").resolve()
    host = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    suffix = ".exe" if os.name == "nt" else ""
    ndk = ndk_root / "toolchains/llvm/prebuilt" / host / "bin"
    if not (ndk / ("clang" + suffix)).is_file():
        raise SystemExit("Set up NDK29 first with build_native.py --download-tools --tools-only, or pass --ndk")
    assets = []
    for abi, target in TARGETS.items():
        target_env = dict(env)
        # Use the existing pinned NDK 29 compiler driver and explicit API29 target.
        target_env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(ndk / ("clang" + suffix))
        target_env["CARGO_ENCODED_RUSTFLAGS"] = "\x1f".join([
            "-C", "link-arg=--target=" + CLANG_TARGETS[abi] + "29",
            "-C", "link-arg=-Wl,-z,max-page-size=16384", "-C", "link-arg=-Wl,-z,common-page-size=16384",
            "-C", "link-arg=-Wl,-z,noexecstack",
            "--remap-path-prefix=" + str(ROOT) + "=/haaru",
        ])
        run([common[0], "build", *common[1:], "--release", "--target", target, "--target-dir", CORE / "target/android",
             "--lib", "--bin", "haaru-upgrade", "--bin", "haaru-status"], target_env)
        output = CORE / "target/android" / target / "release"
        static = output / "libhaaru_core.a"
        if not static.is_file():
            raise SystemExit("Missing Rust Android static library")
        for executable, kind in (("haaru-upgrade", "upgrade"), ("haaru-status", "status")):
            destination = ROOT / "module/bin" / (executable + "-" + abi)
            shutil.copyfile(output / executable, destination)
            subprocess.run([str(ndk / ("llvm-strip" + suffix)), "--strip-all", str(destination)], check=True)
            assets.append({"abi": abi, "target": target, "kind": kind, "path": destination.relative_to(ROOT / "module").as_posix(),
                           "sha256": digest(destination), "bytes": destination.stat().st_size,
                           "staticLibrary": static.relative_to(ROOT).as_posix(), "staticSha256": digest(static)})
            print("Rust Android staged:", executable, abi, destination.stat().st_size, flush=True)
    if source_records()[1] != before:
        raise SystemExit("Rust source changed while building; rebuild before packaging")
    manifest = {"format": 1, "buildMode": "built-and-staged", "rustVersion": "1.100.0", "minAndroidApi": 29,
                "deviceTested": False, "panicStrategy": "abort", "sourceTreeSha256": before,
                "sources": records, "assets": assets, "dataCore": True, "migrationEngine": "rust-native"}
    (ROOT / "module/rust-build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Rust core source SHA-256:", before)


if __name__ == "__main__":
    main()
