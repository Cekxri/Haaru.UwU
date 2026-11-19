#!/usr/bin/env python3
"""Run the Rust protocol/codec and migrator against real Java host contracts.

The Java Controller produces the runtime JSON, Java Properties reads migrated
settings, and a separate JVM holds the settings lock while Rust attempts an
upgrade. These are host checks, not Android ART/JNI or device validation.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading


ROOT = Path(__file__).resolve().parents[1]
WORK = (ROOT / "build/rust-contract").resolve()
BOOT = "fixture-boot"


def run_json(command, *, stdin=None, timeout=30):
    completed = subprocess.run(command, cwd=ROOT, input=stdin, capture_output=True,
                               text=True, encoding="utf-8", timeout=timeout)
    assert completed.returncode == 0, completed.stderr or completed.stdout
    return json.loads(completed.stdout)


def raw_array_items(text):
    """Retain each Java JSON item's original bytes instead of reserialising it."""
    decoder = json.JSONDecoder()
    position = text.index("[") + 1
    items = []
    while True:
        while position < len(text) and text[position].isspace():
            position += 1
        if text[position] == "]":
            break
        value, end = decoder.raw_decode(text, position)
        items.append((value, text[position:end]))
        position = end
        while text[position].isspace():
            position += 1
        if text[position] == ",":
            position += 1
        else:
            assert text[position] == "]"
            break
    return items


def node_hashes(inputs):
    # An independent runtime hashes Buffer bytes. Known FNV-1a vectors below
    # also check this oracle before using it for Java's exact serialised JSON.
    code = """const fs=require('node:fs');
const inputs=JSON.parse(fs.readFileSync(0,'utf8'));
const hashes=inputs.map(input=>{let hash=0xcbf29ce484222325n;
for(const byte of (input.bytes ? Buffer.from(input.bytes) : Buffer.from(input.text,'utf8'))) {
hash=BigInt.asUintN(64,(hash^BigInt(byte))*0x100000001b3n);}
return hash.toString();});process.stdout.write(JSON.stringify(hashes));"""
    return run_json(["node", "-e", code], stdin=json.dumps(inputs, ensure_ascii=False))


def probe_contract(args):
    produced = subprocess.run([args.java, "-cp", args.classpath,
                               "com.github.haaru.ksu.NativeRuntimeFixture"], cwd=ROOT,
                              capture_output=True, text=True, encoding="utf-8", timeout=30)
    assert produced.returncode == 0, produced.stderr
    frames = raw_array_items(produced.stdout)
    assert len(frames) == 2, produced.stdout
    (active, active_raw), (removed, removed_raw) = frames
    assert active["enabled"] is True and active["readOnly"] is False
    assert removed["enabled"] is False and removed["readOnly"] is True
    cases = []

    def boolean(label, request, expected):
        cases.append((label, request, {"ok": True, "accepted": expected}))

    def result(label, request, expected):
        cases.append((label, request, {"ok": True, "result": expected}))

    def rejected(label, request):
        cases.append((label, request, {"ok": False}))

    def runtime(label, value, expected=False, boot=BOOT, raw=None):
        boolean(label, {"op": "runtime", "bootId": boot,
                        "runtimeRaw": raw if raw is not None else json.dumps(value, ensure_ascii=True)}, expected)

    runtime("actual active Java runtime is structurally valid", active, True, raw=active_raw)
    runtime("actual removed Java runtime is structurally valid but inactive", removed, True, raw=removed_raw)
    runtime("runtime from a different boot is rejected", active, boot="another-boot", raw=active_raw)
    runtime("empty boot identity is rejected", active, boot="", raw=active_raw)
    runtime("nonobject runtime is rejected", [])
    for field, values in {
        "protocol": (1, "2", True, 2.5),
        "global": (False, "true", 1),
        "engine": ("other-engine", None),
        "enabled": ("true", 1),
        "readOnly": ("false", 0),
        "profiles": (None, {}),
    }.items():
        for value in values:
            changed = copy.deepcopy(active)
            changed[field] = value
            runtime(f"runtime field {field} rejects {value!r}", changed)
    for field, values in {
        "slot": (-1, 8, True, "0", 0.5),
        "subId": (-1, True, "21", 21.5, 2147483648),
        "country": ("usa", "漢字", "u\n", None),
        "carrierName": ("x" * 81, "line\nbreak", "nul\0name", None),
        "weakIdentity": ("false", 1),
        "confirmedBoot": (False, 123),
    }.items():
        for value in values:
            changed = copy.deepcopy(active)
            changed["profiles"][0][field] = value
            runtime(f"profile field {field} rejects {value!r}", changed)
    changed = copy.deepcopy(active)
    changed["profiles"].append(copy.deepcopy(changed["profiles"][0]))
    runtime("duplicate SIM slot/subscription is rejected", changed)
    changed["profiles"][1]["slot"] = 1
    runtime("duplicate subscription in different slots is rejected", changed)
    changed = copy.deepcopy(active)
    changed["profiles"] = [dict(active["profiles"][0], slot=index, subId=index) for index in range(9)]
    runtime("more than eight profiles are rejected", changed)
    changed = copy.deepcopy(active)
    changed["profiles"][0].update(weakIdentity=True, confirmedBoot="another-boot")
    runtime("weak SIM identity cannot cross boot", changed)
    changed["profiles"][0]["confirmedBoot"] = BOOT
    runtime("explicit weak identity confirmation for current boot is accepted", changed, True)
    changed = copy.deepcopy(active)
    changed["profiles"][0]["carrierName"] = "國際電信 😀"
    runtime("Unicode scalar operator names are accepted", changed, True)
    changed["profiles"][0]["carrierName"] = "台" * 80
    runtime("operator limit counts Unicode scalars rather than UTF-8 bytes", changed, True)
    changed["profiles"][0]["carrierName"] = "\ud800"
    runtime("unpaired JSON Unicode surrogate is rejected", changed)
    runtime("duplicate runtime JSON keys are rejected", active, raw=active_raw[:-1] + ',"protocol":2}')
    runtime("escaped duplicate runtime JSON key is rejected", active, raw=active_raw[:-1] + ',"\\u0070rotocol":2}')
    rejected("oversized input is rejected before protocol evaluation", {
        "op": "runtime", "bootId": BOOT,
        "runtimeRaw": active_raw[:-1] + ',"padding":"' + "x" * 65536 + '"}',
    })

    for uid in (0, 1000, 1001, 10000, 89999, 1001001, 4294967295):
        boolean(f"phone UID gate for {uid}", {"op": "uid", "uid": uid}, uid == 1001)
    for protocol, read_only, enabled, boot_matches, expected in (
        (2, False, True, True, True), (1, False, True, True, False),
        (2, True, True, True, False), (2, False, False, True, False),
        (2, False, True, False, False),
    ):
        boolean("cache activation requires all independent gates", {
            "op": "cache", "protocol": protocol, "readOnly": read_only,
            "enabled": enabled, "bootMatches": boot_matches,
        }, expected)
    boolean("actual removed Java runtime never activates the cache", {
        "op": "cache", "protocol": removed["protocol"], "readOnly": removed["readOnly"],
        "enabled": removed["enabled"], "bootMatches": removed["bootId"] == BOOT,
    }, False)

    shadow = {"bootId": BOOT, "countryList": "tw,jp,", "nameList": "Local\\,SIM,Other,",
              "published": False, "revision": 7}
    boolean("valid escaped original-value shadow", {"op": "shadow", "bootId": BOOT, "shadow": shadow}, True)
    for field, value in (("bootId", "wrong"), ("countryList", "twn"),
                         ("countryList", "," * 8), ("nameList", "x" * 92),
                         ("nameList", "line\nbreak"), ("nameList", "台" * 31)):
        changed = dict(shadow, **{field: value})
        boolean(f"invalid shadow {field} is rejected", {"op": "shadow", "bootId": BOOT, "shadow": changed}, False)
    boolean("ninety UTF-8 bytes fit the property shadow", {
        "op": "shadow", "bootId": BOOT, "shadow": dict(shadow, nameList="台" * 30),
    }, True)

    for value, expected in (
        ("", [""]), ("tw,jp,", ["tw", "jp", ""]),
        ("A\\,B,C\\\\D,", ["A,B", "C\\D", ""]),
        ("one\\q,two\\", ["oneq", "two\\"]),
        ("台灣,é😀,", ["台灣", "é😀", ""]),
    ):
        result("StringList decoding preserves escaping and empty slots", {"op": "split", "value": value}, expected)
    for values, expected in ((["tw", "jp", ""], "tw,jp,"),
                             (["A,B", "C\\D", ""], "A\\,B,C\\\\D,"),
                             (["台灣", "é😀", ""], "台灣,é😀,")):
        result("StringList encoding preserves significant characters", {"op": "join", "values": values}, expected)
    result("omitted overlay preserves original noncanonical bytes", {
        "op": "overlay", "original": "one\\q,,", "profiles": [], "country": False,
    }, "one\\q,,")
    result("country overlay keeps other slots and trailing empty value", {
        "op": "overlay", "original": "tw,jp,", "profiles": [{"slot": 0, "country": "US", "name": ""}], "country": True,
    }, "us,jp,")
    result("operator overlay escapes comma and backslash", {
        "op": "overlay", "original": "Local,Other,",
        "profiles": [{"slot": 0, "country": "", "name": "AT&T, Pacific\\5G"}], "country": False,
    }, "AT&T\\, Pacific\\\\5G,Other,")
    result("logical phone slot seven retains sparse intervening slots", {
        "op": "overlay", "original": "tw", "profiles": [{"slot": 7, "country": "US", "name": ""}], "country": True,
    }, "tw" + "," * 7 + "us")
    result("ninety-one byte overlay is accepted", {
        "op": "overlay", "original": "", "profiles": [{"slot": 0, "country": "", "name": "x" * 91}], "country": False,
    }, "x" * 91)
    for profiles in ([{"slot": -1, "country": "us", "name": ""}],
                     [{"slot": 8, "country": "us", "name": ""}],
                     [{"slot": 0, "country": "us", "name": ""}] * 2,
                     [{"slot": 0, "country": "usa", "name": ""}],
                     [{"slot": 0, "country": "", "name": "x" * 92}],
                     [{"slot": 0, "country": "", "name": "台" * 31}],
                     [{"slot": 0, "country": "", "name": "nul\0name"}]):
        rejected("invalid or oversized property overlay fails", {
            "op": "overlay", "original": "", "profiles": profiles, "country": False,
        })
    hashes = node_hashes([{"text": ""}, {"text": "a"}, {"text": "hello"},
                          {"text": active_raw}, {"text": "台灣,é😀"}, {"bytes": [0, 255, 128, 1]}])
    assert hashes[:3] == ["14695981039346656037", "12638187200555641996", "11831194018420276491"]
    for value, expected in zip(("", "a", "hello", active_raw, "台灣,é😀"), hashes):
        result("FNV hashes exact UTF-8 bytes as the independent Node Buffer oracle", {"op": "fnv", "value": value}, expected)
    result("raw binary FNV input matches the independent byte oracle", {"op": "fnv", "bytes": [0, 255, 128, 1]}, hashes[-1])
    result("Java runtime UTF-8 bytes match its unchanged serialised string", {
        "op": "fnv", "bytes": list(active_raw.encode("utf-8")),
    }, hashes[3])

    completed = subprocess.run([str(args.probe)], cwd=ROOT,
                               input="\n".join(json.dumps(request, ensure_ascii=False) for _, request, _ in cases) + "\n",
                               capture_output=True, text=True, encoding="utf-8", timeout=30)
    assert completed.returncode == 0, completed.stderr or completed.stdout
    replies = [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]
    assert len(replies) == len(cases), (len(replies), len(cases), completed.stderr)
    for (label, request, expected), reply in zip(cases, replies):
        for field, value in expected.items():
            assert reply.get(field) == value, (label, request, expected, reply)
    print(f"PASS Rust protocol/codec: {len(cases)} real probe requests, including actual Java runtime JSON and byte hashes")
    return len(cases)


def checked(path):
    resolved = path.resolve()
    assert resolved.is_relative_to(WORK) and resolved != WORK
    return resolved


def fixture_write(path, data):
    checked(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def lock_contract(args):
    directory = Path(tempfile.mkdtemp(prefix="lock-", dir=WORK)).resolve()
    checked(directory)
    holder = None
    try:
        live = directory / "modules/haaru"
        stage = directory / "modules_update/haaru"
        for module in (live, stage):
            fixture_write(module / "module.prop", b"id=haaru\nversion=0.6.0\n")
        source_state = b"schema=5\nenabled=true\nprofile.0.country=us\nprofile.0.carrier=Caf\xe9 \\u570B\\u969B\\uD83D\\uDE00\nprofile.0.subId=21\nprofile.0.identity=simhash\nprofile.0.enabled=true\nprofile.0.confirmedBoot=old-boot\n"
        fixture_write(live / "data/state.properties", source_state)
        fixture_write(live / "data/lock", b"")
        fixture_write(live / "data/global-shadow.json", b"old live shadow is unchanged")
        fixture_write(stage / "disable", b"")
        java_source = directory / "HaaruFileLockHolder.java"
        fixture_write(java_source, b'''import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
public final class HaaruFileLockHolder {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        if ("properties".equals(args[0])) {
            Properties values = new Properties();
            try(InputStream input=Files.newInputStream(Paths.get(args[1]))) { values.load(input); }
            Map<String,String> result=new TreeMap<String,String>();
            for(String key:values.stringPropertyNames()) { result.put(key,values.getProperty(key)); }
            System.out.println(com.github.haaru.ksu.Json.encode(result)); return;
        }
        try(FileChannel channel=FileChannel.open(Paths.get(args[1]),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.lock()) {
            System.out.println("READY"); System.out.flush(); System.in.read();
        }
    }
}
''')
        classes = directory / "classes"
        classes.mkdir()
        subprocess.run([args.javac, "--release", "8", "-Xlint:-options", "-cp", args.classpath,
                        "-d", classes.relative_to(ROOT).as_posix(), java_source.relative_to(ROOT).as_posix()],
                       cwd=ROOT, check=True, timeout=30)
        classpath = args.classpath + os.pathsep + classes.relative_to(ROOT).as_posix()
        holder = subprocess.Popen([args.java, "-cp", classpath, "HaaruFileLockHolder", "lock",
                                   (live / "data/lock").relative_to(ROOT).as_posix()], cwd=ROOT,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, encoding="utf-8")
        ready = queue.Queue()
        threading.Thread(target=lambda: ready.put(holder.stdout.readline().strip()), daemon=True).start()
        assert ready.get(timeout=15) == "READY", "The real JVM did not acquire its settings lock"
        staged_before = {path.relative_to(stage).as_posix(): path.read_bytes() for path in stage.rglob("*") if path.is_file()}
        command = [str(args.upgrade), "--fixture", live.relative_to(ROOT).as_posix(), stage.relative_to(ROOT).as_posix()]
        blocked = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=15)
        failure = json.loads(blocked.stdout)
        assert blocked.returncode != 0 and failure.get("ok") is False, failure
        assert {path.relative_to(stage).as_posix(): path.read_bytes() for path in stage.rglob("*") if path.is_file()} == staged_before
        assert (live / "data/state.properties").read_bytes() == source_state
        holder.stdin.write("\n")
        holder.stdin.flush()
        assert holder.wait(timeout=15) == 0
        migrated = run_json(command)
        assert migrated.get("verified") is True and migrated.get("settingsPreserved") is True
        assert not (stage / "disable").exists()
        normalized = run_json([args.java, "-cp", classpath, "HaaruFileLockHolder", "properties",
                               (stage / "data/state.properties").relative_to(ROOT).as_posix()])
        assert normalized["profile.0.carrier"] == "Café 國際😀", normalized
        assert normalized["profile.0.country"] == "us" and normalized["profile.0.enabled"] == "true"
        assert (live / "data/state.properties").read_bytes() == source_state
        assert (live / "data/global-shadow.json").read_bytes() == b"old live shadow is unchanged"
        print("PASS Java/Rust host file-lock interoperability: locked migration is refused, release succeeds, Java reads Latin-1 and UTF-16 names correctly")
    finally:
        if holder is not None:
            if holder.poll() is None:
                holder.terminate()
                try:
                    holder.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    holder.kill()
                    holder.wait(timeout=10)
            for pipe in (holder.stdin, holder.stdout, holder.stderr):
                if pipe is not None:
                    pipe.close()
        shutil.rmtree(checked(directory))


def main():
    parser = argparse.ArgumentParser()
    executable_suffix = ".exe" if os.name == "nt" else ""
    parser.add_argument("--probe", type=Path, default=Path("rust-core/target/host/release/haaru-core-probe" + executable_suffix))
    parser.add_argument("--upgrade", type=Path, default=Path("rust-core/target/host/release/haaru-upgrade" + executable_suffix))
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--classpath", default="build/classes" + os.pathsep + "build/test-classes")
    args = parser.parse_args()
    for name in ("probe", "upgrade"):
        path = getattr(args, name)
        resolved = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
        assert resolved.is_file(), "Build the actual Rust host executable: " + str(resolved)
        setattr(args, name, resolved)
    assert WORK.is_relative_to(ROOT.resolve())
    WORK.mkdir(parents=True, exist_ok=True)
    count = probe_contract(args)
    lock_contract(args)
    print(f"Rust core host contract: {count} protocol/codec requests plus real Java lock/Properties interoperability passed; Android device tested: false")


if __name__ == "__main__":
    main()
