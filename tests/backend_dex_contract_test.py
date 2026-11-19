#!/usr/bin/env python3
"""Compile adversarial Java 8 and the real backend through the release D8 pipeline."""
import hashlib
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from release_support import validate_backend_dex

WORK = ROOT / "tools/installer-diagnostic/dex-contract"
R8 = ROOT / "tools/r8-8.6.27.jar"


def run(args):
    values = [str(x.relative_to(ROOT)) if isinstance(x, Path) and x.is_relative_to(ROOT) else str(x) for x in args]
    subprocess.run(values, cwd=ROOT, check=True, stdout=subprocess.DEVNULL)


def compile_dex(folder, sources):
    classes = folder / "classes"; classes.mkdir(parents=True, exist_ok=True)
    run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-d", classes, *sources])
    jar = folder / "input.jar"
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")): archive.writestr(path.relative_to(classes).as_posix(), path.read_bytes())
    output = folder / "dex"; output.mkdir(exist_ok=True)
    run(["java", "-cp", R8, "com.android.tools.r8.D8", "--no-desugaring", "--min-api", "29", "--output", output, jar])
    return (output / "classes.dex").read_bytes()


def resign(data):
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def reject(data):
    try: validate_backend_dex(data)
    except ValueError: return
    raise AssertionError("Unsafe or malformed backend DEX was accepted")


def main():
    if hashlib.sha256(R8.read_bytes()).hexdigest() != "9007144728d47b5f11338c9292d5b74899b6579ad9ddc6942d419e3600c3d6d7":
        raise AssertionError("Pinned D8 changed")
    if WORK.exists():
        resolved = WORK.resolve()
        if not resolved.is_relative_to((ROOT / "tools/installer-diagnostic").resolve()) or resolved == ROOT:
            raise AssertionError("Unsafe compiler fixture directory")
        shutil.rmtree(resolved)
    WORK.mkdir(parents=True)
    negative = WORK / "adversarial"; negative.mkdir()
    source = negative / "AdversarialLambda.java"
    source.write_text('public class AdversarialLambda { public static java.util.concurrent.Callable<String> create() { return () -> "value"; } }', encoding="utf-8")
    bad = compile_dex(negative, [source])
    assert b"LambdaMetafactory" in bad, "The fixture must reproduce the actual released JVM dependency"
    reject(bad)
    good = compile_dex(WORK / "production", sorted((ROOT / "src").rglob("*.java")))
    validate_backend_dex(good)
    assert b"LambdaMetafactory" not in good
    changed = bytearray(good); struct.pack_into("<I", changed, 52, len(good) + 4); reject(resign(changed))
    changed = bytearray(good); map_offset = struct.unpack_from("<I", good, 52)[0]
    struct.pack_into("<I", changed, map_offset, 0xffffffff); reject(resign(changed))
    changed = bytearray(good); count, type_offset = struct.unpack_from("<II", good, 64)
    assert count; struct.pack_into("<I", changed, type_offset, 0xffffffff); reject(resign(changed))
    changed = bytearray(good); string_index = struct.unpack_from("<I", good, type_offset)[0]
    strings_offset = struct.unpack_from("<I", good, 60)[0]
    string_data = struct.unpack_from("<I", good, strings_offset + string_index * 4)[0]
    while changed[string_data] & 0x80: string_data += 1
    changed[string_data + 1] = ord("!"); reject(resign(changed))
    changed = bytearray(good); changed[-1] ^= 1; reject(bytes(changed))
    print("Backend DEX compiler contract: real Java8 lambda rejected; production anonymous tasks and 5 corruption regressions passed")


if __name__ == "__main__": main()
