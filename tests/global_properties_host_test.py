#!/usr/bin/env python3
"""Execute the actual native property codec on the build host, without Android claims."""
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
output = ROOT / "build/property-host"
output.mkdir(parents=True, exist_ok=True)
source = ROOT / "native/tests/global_properties_test.cpp"
env = dict(os.environ)
if os.name == "nt":
    compiler = shutil.which("cl.exe")
    if not compiler:
        installations = Path("C:/Program Files (x86)/Microsoft Visual Studio")
        candidates = sorted(installations.glob("*/BuildTools/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe"))
        if not candidates:
            raise SystemExit("MSVC Build Tools are required for the Windows property host test")
        compiler = candidates[-1]
        msvc = compiler.parents[3]
        kits = Path("C:/Program Files (x86)/Windows Kits/10")
        versions = sorted(p for p in (kits / "Include").iterdir() if (p / "ucrt").is_dir())
        if not versions:
            raise SystemExit("Windows SDK headers are required for the property host test")
        sdk = versions[-1].name
        env["PATH"] = str(compiler.parent) + os.pathsep + env.get("PATH", "")
        env["INCLUDE"] = os.pathsep.join(map(str, [msvc / "include", *[kits / "Include" / sdk / p for p in ("ucrt", "um", "shared")]]))
        env["LIB"] = os.pathsep.join(map(str, [msvc / "lib/x64", *[kits / "Lib" / sdk / p / "x64" for p in ("ucrt", "um")]]))
    binary = output / "global-properties-test.exe"
    rust_core = ROOT / "rust-core/target/host/release/haaru_core.lib"
    if not rust_core.is_file():
        raise SystemExit("Build the Rust host core before the C++/Rust property contract test")
    command = [str(compiler), "/nologo", "/std:c++17", "/EHsc", "/MD", "/W4", "/Fe:" + str(binary), "/Fo:" + str(output / "property.obj"), str(source), str(rust_core),
               "/link", "ws2_32.lib", "advapi32.lib", "userenv.lib", "bcrypt.lib", "ntdll.lib", "kernel32.lib", "synchronization.lib"]
else:
    compiler = shutil.which("clang++") or shutil.which("g++")
    if not compiler:
        raise SystemExit("A host C++ compiler is required for the native property test")
    binary = output / "global-properties-test"
    rust_core = ROOT / "rust-core/target/host/release/libhaaru_core.a"
    command = [compiler, "-std=c++17", "-Wall", "-Wextra", str(source), str(rust_core), "-lpthread", "-ldl", "-lm", "-o", str(binary)]
subprocess.run(command, cwd=ROOT, env=env, check=True)
subprocess.run([str(binary)], cwd=ROOT, env=env, check=True)
