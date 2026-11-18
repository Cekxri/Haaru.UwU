#!/usr/bin/env python3
"""Build and integrity-stage read-only Zygisk libraries from pinned sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import urllib.request
import zipfile
from privacy_guard import publishable

ROOT = Path(__file__).resolve().parent
NATIVE = ROOT / "native"
TOOLS = ROOT / "tools/native"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sources():
    files = [Path(__file__).resolve(), NATIVE / "CMakeLists.txt", NATIVE / "toolchain-pins.json"]
    for directory in (NATIVE / "src", NATIVE / "vendor", ROOT / "hook/src"):
        files.extend(p for p in directory.rglob("*") if p.is_file()
                     and not p.is_relative_to(NATIVE / "vendor/dobby")
                     and publishable(p.relative_to(ROOT).as_posix()))
    for directory in (ROOT / "rust-core/src",):
        files.extend(p for p in directory.rglob("*") if p.is_file())
    files.extend(ROOT / name for name in ("build_rust.py", "rust-core/Cargo.toml", "rust-core/Cargo.lock", "rust-core/toolchain-pins.json"))
    return sorted(set(files), key=lambda p: p.relative_to(ROOT).as_posix())


def fingerprint():
    records, tree = [], hashlib.sha256()
    for p in sources():
        name, sha = p.relative_to(ROOT).as_posix(), digest(p)
        records.append({"path": name, "sha256": sha, "bytes": p.stat().st_size})
        tree.update(name.encode() + b"\0" + sha.encode() + b"\n")
    return records, tree.hexdigest()


def download_tools():
    if os.name != "nt":
        raise SystemExit("Automatic tool downloads are pinned Windows tools; provide --ndk/--cmake/--ninja on other hosts")
    TOOLS.mkdir(parents=True, exist_ok=True)
    pins = json.loads((NATIVE / "toolchain-pins.json").read_text())
    context = ssl.create_default_context()
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    for name, pin in pins.items():
        archive = TOOLS / pin["filename"]
        if not archive.exists():
            with urllib.request.urlopen(pin["url"], context=context, timeout=60) as source, archive.open("wb") as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
        if digest(archive) != pin["sha256"]:
            raise SystemExit("Pinned tool SHA-256 mismatch: " + name)
        out = TOOLS / ("ndk29" if name == "ndk" else name)
        out.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            for item in z.infolist():
                parts = item.filename.split("/")
                if name == "ndk":
                    parts = parts[1:]
                if not parts or not parts[-1]:
                    continue
                target = out.joinpath(*parts)
                if not target.resolve().is_relative_to(out.resolve()):
                    raise ValueError("Archive path escapes its tool directory")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(item))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download-tools", action="store_true")
    parser.add_argument("--tools-only", action="store_true", help="Verify/download the fixed NDK/CMake/Ninja without compiling")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--cmake", type=Path)
    parser.add_argument("--ninja", type=Path)
    parser.add_argument("--abis", nargs="+", choices=ABIS, default=list(ABIS))
    args = parser.parse_args()
    if args.download_tools:
        download_tools()
    ndk = (args.ndk or TOOLS / "ndk29").resolve()
    cmake = args.cmake or TOOLS / "cmake/cmake/data/bin/cmake.exe"
    ninja = args.ninja or TOOLS / "ninja/ninja-1.11.1.4.data/scripts/ninja.exe"
    for p in (cmake, ninja, ndk / "build/cmake/android.toolchain.cmake"):
        if not p.is_file():
            raise SystemExit("Missing native tool: " + str(p))
    properties = (ndk / "source.properties").read_text()
    if "Pkg.Revision = 29.0.14206865" not in properties:
        raise SystemExit("Use pinned NDK 29.0.14206865")
    if args.tools_only:
        print("Pinned native tools verified")
        return
    records, before = fingerprint()
    rust_manifest = json.loads((ROOT / "module/rust-build-manifest.json").read_text(encoding="utf-8"))
    if rust_manifest.get("buildMode") != "built-and-staged":
        raise SystemExit("Build all Android Rust core assets using build_rust.py first")
    rust_assets = {asset["abi"]: asset for asset in rust_manifest["assets"]}
    out = ROOT / "module/zygisk"
    out.mkdir(exist_ok=True)
    artifacts = []
    for abi in args.abis:
        if abi not in rust_assets:
            raise SystemExit("Missing Rust asset for native ABI: " + abi)
        rust_archive = ROOT / rust_assets[abi]["staticLibrary"]
        if not rust_archive.is_file() or digest(rust_archive) != rust_assets[abi]["staticSha256"]:
            raise SystemExit("Rust static library integrity mismatch: " + abi)
        build = NATIVE / "build" / abi
        log = TOOLS / (abi + "-build.log")
        log.parent.mkdir(parents=True, exist_ok=True)
        commands = [
            [str(cmake.resolve()), "-S", str(NATIVE), "-B", str(build), "-G", "Ninja",
             "-DCMAKE_MAKE_PROGRAM=" + str(ninja.resolve()),
             "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
             "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-29", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release"],
            [str(cmake.resolve()), "--build", str(build), "--target", "haaru", "--parallel", "6"],
        ]
        commands[0].append("-DHAARU_RUST_CORE_ARCHIVE=" + str(rust_archive.resolve()))
        with log.open("w", encoding="utf-8") as f:
            for command in commands:
                result = subprocess.run(command, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT)
                if result.returncode:
                    f.flush()
                    raise SystemExit(log.read_text(encoding="utf-8")[-12000:])
        binary = build / "libhaaru.so"
        destination = out / (abi + ".so")
        shutil.copyfile(binary, destination)
        host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
        suffix = ".exe" if os.name == "nt" else ""
        bin_dir = ndk / "toolchains/llvm/prebuilt" / host / "bin"
        subprocess.run([str(bin_dir / ("llvm-strip" + suffix)), "--strip-all", str(destination)], check=True)
        info = subprocess.check_output([str(bin_dir / ("llvm-readelf" + suffix)), "--program-headers", "--dyn-syms", str(destination)], text=True)
        if "zygisk_module_entry" not in info or "zygisk_companion_entry" not in info or "GNU_STACK" not in info:
            raise SystemExit("Invalid native library export/header set")
        artifacts.append({"path": destination.relative_to(ROOT / "module").as_posix(), "sha256": digest(destination), "bytes": destination.stat().st_size, "abi": abi})
        print("Native staged:", abi, destination.stat().st_size, flush=True)
    if fingerprint()[1] != before:
        raise SystemExit("Native source changed during compilation; rebuild before packaging")
    manifest = {"format": 2, "buildMode": "built-and-staged", "architecture": "system-global-central-phone-volatile",
                "zygiskApi": 4, "minAndroidApi": 29, "ndk": "29.0.14206865", "stl": "c++_static",
                "pageAlignment": 16384, "deviceTested": False, "sources": records,
                "sourceTreeSha256": before, "assets": artifacts,
                "dependencies": json.loads((NATIVE / "vendor/pins.json").read_text()),
                "rustCoreSourceSha256": rust_manifest["sourceTreeSha256"], "rustVersion": rust_manifest["rustVersion"]}
    (ROOT / "module/native-build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Native source SHA-256:", before)


if __name__ == "__main__":
    main()
