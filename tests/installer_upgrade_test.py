#!/usr/bin/env python3
"""Exercise the actual Rust migration across the KSU 3.3.1 update lifecycle.

KSU clears modules_update before customization, clears the live disable/remove
flags after customization, and deletes the old live directory before renaming
the staged directory at boot. Customization protects migration with a staged
disable flag until the helper completes. This host fixture models those
filesystem steps; it does not execute Android, ksud, or the phone engine.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
WORK = (ROOT / "build/upgrade-integration").resolve()
BOOT_FILES = (
    "data/runtime.json",
    "data/global-shadow.json",
    "data/global-status.json",
    "data/global-transaction.json",
    "data/lock",
)
PAYLOAD = {
    "module.prop": b"id=haaru\nversion=0.6.0\nversionCode=8\n",
    "skip_mount": b"",
    "bin/haaru.dex": b"new helper fixture payload\n",
    "bin/hook.dex": b"new hook fixture payload\n",
    "bin/haaru-upgrade": b"new Rust migrator fixture payload\n",
}
READER = None


def within_work(path):
    resolved = path.resolve()
    if not resolved.is_relative_to(WORK) or resolved == WORK:
        raise AssertionError("Fixture operation escapes its checked workspace")
    return resolved


def remove_tree(path):
    resolved = within_work(path)
    if resolved.exists():
        shutil.rmtree(resolved)


def write(path, data):
    within_work(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def tree(directory):
    return {
        path.relative_to(directory).as_posix(): path.read_bytes()
        for path in directory.rglob("*") if path.is_file()
    }


def settings(country="us", carrier="AT&T"):
    values = {
        "schema": "5",
        "boot": "old-boot",
        "enabled": "true",
        "profile.0.country": country,
        "profile.0.carrier": carrier,
        "profile.0.subId": "10",
        "profile.0.identity": "simhashzero",
        "profile.0.enabled": "true",
        "profile.0.confirmedBoot": "old-boot",
        "profile.1.country": "jp",
        "profile.1.carrier": "NTT DOCOMO",
        "profile.1.subId": "11",
        "profile.1.identity": "simhashone",
        "profile.1.enabled": "false",
        "profile.1.confirmedBoot": "old-boot",
        # Old controller/runtime ownership data must not become durable input.
        "baseline.10.country": "tw",
        "pending.10.carrier": "discard pending ownership",
        "last.10.country": "us",
        "targetPackages": "historical.app.scope",
    }
    return ("\n".join(f"{key}={value}" for key, value in values.items()) + "\n").encode("ascii")


def appearance(palette="MINT"):
    return json.dumps({
        "style": "MIUIX", "appearance": "LIGHT", "palette": palette,
        "glass": True, "reduceMotion": False,
    }, separators=(",", ":")).encode("utf-8")


def installed(case, disabled=False, durable=True):
    live = case / "modules/haaru"
    write(live / "module.prop", b"id=haaru\nversion=0.5.0\nversionCode=6\n")
    write(live / "bin/haaru.dex", b"old live helper payload\n")
    if durable:
        write(live / "data/state.properties", settings())
        write(live / "webroot/appearance.json", appearance())
        for name in BOOT_FILES:
            write(live / name, b"" if name.endswith("/lock") else ("old boot " + name).encode("ascii"))
    if disabled:
        write(live / "disable", b"")
    return live


def fresh_extract(case):
    stage = case / "modules_update/haaru"
    remove_tree(stage)
    stage.mkdir(parents=True)
    archive = case / "update.zip"
    with zipfile.ZipFile(archive, "w") as output:
        for name, data in PAYLOAD.items():
            output.writestr(name, data)
    # Every fixture ZIP name is a fixed relative path owned by this test.
    with zipfile.ZipFile(archive) as source:
        source.extractall(stage)
    return stage


def migrate(args, live, stage, expected_success=True):
    assert (stage / "disable").is_file(), "Migration must begin with its staged disable guard"
    oracle = stage.parents[1] / "java-oracle/haaru"
    if args.java_oracle:
        remove_tree(oracle)
        within_work(oracle)
        oracle.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(stage, oracle)
    command = [str(args.rust_exe), "--fixture", live.relative_to(ROOT).as_posix(),
               stage.relative_to(ROOT).as_posix()]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                               encoding="utf-8", timeout=30)
    try:
        result = json.loads(completed.stdout.strip())
    except json.JSONDecodeError as error:
        raise AssertionError(f"Rust migration did not return JSON: {completed.stdout}\n{completed.stderr}") from error
    if expected_success:
        assert completed.returncode == 0, result
        assert result.get("ok") is True and result.get("verified") is True, result
        assert result.get("verification") == "module-files", result
        assert result.get("rebootRequired") is True, result
    else:
        assert completed.returncode != 0 and result.get("ok") is False, result
    if args.java_oracle:
        compared = subprocess.run([args.java, "-cp", args.classpath, "com.github.haaru.ksu.UpgradeFixture",
                                   live.relative_to(ROOT).as_posix(), oracle.relative_to(ROOT).as_posix()],
                                  cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=30)
        java_result = json.loads(compared.stdout.strip())
        assert (compared.returncode == 0) is expected_success, java_result
        for field in ("ok", "verified", "settingsPreserved", "appearancePreserved", "disabledPreserved", "rebootRequired"):
            assert result.get(field) == java_result.get(field), (field, result, java_result)
        if expected_success:
            for relative in ("data/state.properties", "data/appearance.json", "disable"):
                rust_file, java_file = stage / relative, oracle / relative
                assert rust_file.exists() is java_file.exists(), relative
                if not rust_file.exists():
                    continue
                if relative.endswith(".properties"):
                    assert read_properties(rust_file) == read_properties(java_file)
                elif relative.endswith(".json"):
                    assert json.loads(rust_file.read_text(encoding="utf-8")) == json.loads(java_file.read_text(encoding="utf-8"))
                else:
                    assert rust_file.read_bytes() == java_file.read_bytes()
    return result


def read_properties(path):
    # Java's own Properties.load is the independent format oracle, including
    # escaped spaces and UTF-16 Unicode escapes produced by the Rust codec.
    java, classpath = READER
    completed = subprocess.run([java, "-cp", classpath, "UpgradePropertiesReader",
                                path.relative_to(ROOT).as_posix()], cwd=ROOT,
                               capture_output=True, text=True, encoding="utf-8", timeout=15)
    assert completed.returncode == 0, completed.stderr
    return json.loads(completed.stdout)


def prepare_properties_reader(args):
    global READER
    source = WORK / "reader/UpgradePropertiesReader.java"
    classes = WORK / "reader-classes"
    write(source, b'''import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class UpgradePropertiesReader {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) { values.load(in); }
        Map<String,String> normalized = new TreeMap<String,String>();
        for (String key : values.stringPropertyNames()) { normalized.put(key, values.getProperty(key)); }
        System.out.println(com.github.haaru.ksu.Json.encode(normalized));
    }
}
''')
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([args.javac, "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", args.classpath,
                    "-d", classes.relative_to(ROOT).as_posix(), source.relative_to(ROOT).as_posix()],
                   cwd=ROOT, check=True, timeout=30)
    READER = (args.java, args.classpath + os.pathsep + classes.relative_to(ROOT).as_posix())


def assert_durable(stage, country="us", carrier="AT&T", palette="MINT", disabled=False):
    state = read_properties(stage / "data/state.properties")
    expected = read_properties_from_bytes(settings(country, carrier))
    for key, value in expected.items():
        if key.startswith("profile.") or key in ("schema", "enabled"):
            assert state.get(key) == value, (key, state.get(key), value)
    assert not any(key.startswith(("baseline.", "pending.", "last.")) for key in state)
    assert "targetPackages" not in state
    assert json.loads((stage / "data/appearance.json").read_text(encoding="utf-8")) == json.loads(appearance(palette))
    assert not (stage / "webroot").exists(), "Native-only migration created Webroot"
    assert (stage / "disable").exists() is disabled
    for name in BOOT_FILES:
        assert not (stage / name).exists(), "Old boot state was copied: " + name
    expected_files = set(PAYLOAD) | {"data/state.properties", "data/appearance.json"}
    if disabled:
        expected_files.add("disable")
    assert set(tree(stage)) == expected_files, set(tree(stage)) - expected_files


def read_properties_from_bytes(data):
    return dict(line.split("=", 1) for line in data.decode("ascii").splitlines() if "=" in line)


def finish_install(live, stage):
    # Official installer.sh:447-451 and module.rs:752-758.
    for flag in ("remove", "disable"):
        (live / flag).unlink(missing_ok=True)
    write(live / "update", b"")
    write(live / "module.prop", (stage / "module.prop").read_bytes())


def reboot_swap(live, stage):
    # handle_updated_modules preserves only live flags still present at boot;
    # it never clears an already staged disable flag.
    disabled = (live / "disable").exists()
    removed = (live / "remove").exists()
    remove_tree(live)
    within_work(stage)
    within_work(live)
    stage.rename(live)
    if removed:
        write(live / "remove", b"")
    elif disabled:
        write(live / "disable", b"")
    return live


def run_case(label, operation):
    case = Path(tempfile.mkdtemp(prefix="case-", dir=WORK)).resolve()
    within_work(case)
    try:
        operation(case)
        print("PASS upgrade lifecycle: " + label)
    finally:
        remove_tree(case)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--classpath", default="build/classes" + os.pathsep + "build/test-classes")
    parser.add_argument("--rust-exe", type=Path, default=Path("rust-core/target/host/release/haaru-upgrade" + (".exe" if os.name == "nt" else "")))
    parser.add_argument("--java-oracle", action="store_true", help="Compare durable outputs with the previous Java migrator")
    args = parser.parse_args()
    args.rust_exe = (ROOT / args.rust_exe).resolve() if not args.rust_exe.is_absolute() else args.rust_exe.resolve()
    assert args.rust_exe.is_file(), "Build the actual Rust host migrator before running this contract"
    assert WORK.is_relative_to(ROOT.resolve())
    WORK.mkdir(parents=True, exist_ok=True)
    installer = (ROOT / "module/customize.sh").read_text(encoding="utf-8")
    assert installer.index('touch "$MODPATH/disable"') < installer.index("HAARU_RESULT=$("), \
        "The actual installer must create its staged disable guard before invoking migration"
    prepare_properties_reader(args)

    def ordinary(case):
        live = installed(case)
        unrelated = case / "modules/other_module/user-setting"
        write(unrelated, b"unrelated settings must survive")
        original = tree(live)
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        result = migrate(args, live, stage)
        assert result["settingsPreserved"] and result["appearancePreserved"]
        assert result["disabledPreserved"] is False
        assert tree(live) == original, "Migration changed the still-running old module"
        assert_durable(stage)
        finish_install(live, stage)
        for name in BOOT_FILES:
            assert (live / name).read_bytes() == original[name]
        reboot_swap(live, stage)
        assert not stage.exists()
        assert_durable(live)
        remove_tree(live)
        assert not live.exists() and unrelated.read_bytes() == b"unrelated settings must survive"

    def disabled(case):
        live = installed(case, disabled=True)
        original = tree(live)
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        result = migrate(args, live, stage)
        assert result["disabledPreserved"] is True
        assert tree(live) == original
        finish_install(live, stage)
        assert not (live / "disable").exists(), "Fixture did not model KSU's flag clearing"
        assert (stage / "disable").exists()
        reboot_swap(live, stage)
        assert_durable(live, disabled=True)

    def repeated(case):
        live = installed(case)
        original_cache = {name: (live / name).read_bytes() for name in BOOT_FILES}
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        migrate(args, live, stage)
        finish_install(live, stage)
        # The advertised module.prop is already new, although the live helper is old.
        assert (live / "module.prop").read_bytes() == PAYLOAD["module.prop"]
        assert (live / "bin/haaru.dex").read_bytes() != PAYLOAD["bin/haaru.dex"]
        write(live / "data/state.properties", settings("gb", "Vodafone"))
        write(live / "webroot/appearance.json", appearance("ROSE"))
        write(stage / "data/global-status.json", b"stale pending update ACK")
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        migrate(args, live, stage)
        assert_durable(stage, country="gb", carrier="Vodafone", palette="ROSE")
        for name, data in original_cache.items():
            assert (live / name).read_bytes() == data
        finish_install(live, stage)
        reboot_swap(live, stage)
        assert_durable(live, country="gb", carrier="Vodafone", palette="ROSE")

    def removed(case):
        live = installed(case)
        write(live / "remove", b"")
        original = tree(live)
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        staged = tree(stage)
        result = migrate(args, live, stage, expected_success=False)
        assert "remov" in result.get("error", "").lower(), result
        assert tree(live) == original and tree(stage) == staged
        assert (stage / "disable").is_file(), "Failed migration removed its staged activation guard"
        # Installer abort discards only the new staging tree, retaining removal intent.
        remove_tree(stage)
        assert tree(live) == original and (live / "remove").exists()

    def empty(case):
        live = installed(case, durable=False)
        original = tree(live)
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        result = migrate(args, live, stage)
        assert result["settingsPreserved"] is False and result["appearancePreserved"] is False
        assert tree(live) == original and tree(stage) == PAYLOAD
        finish_install(live, stage)
        reboot_swap(live, stage)
        assert tree(live) == PAYLOAD, "Empty settings created an implicit profile or runtime"

    def interrupted(case):
        live = installed(case)
        original = tree(live)
        stage = fresh_extract(case)
        write(stage / "disable", b"")
        assert tree(live) == original
        # Power is lost before the Rust helper starts. KSU can still discover
        # the partial stage at the next boot; it must stay disabled. A failed
        # installation has no guarantee that old settings were imported.
        reboot_swap(live, stage)
        assert tree(live) == {**PAYLOAD, "disable": b""}
        assert not (live / "data/state.properties").exists()
        assert not (live / "data/appearance.json").exists()

    run_case("settings survive boot swap and uninstall removes only this module", ordinary)
    run_case("staged disable survives the official installer's live flag clearing", disabled)
    run_case("repeated fresh extraction imports the newest live settings", repeated)
    run_case("scheduled removal abort leaves the old module unchanged", removed)
    run_case("empty settings stay empty after migration and boot swap", empty)
    run_case("interruption before migration leaves the partial stage disabled at boot", interrupted)
    print("Installer upgrade integration: 6 lifecycle scenarios passed using the actual Rust migrator; Android device tested: false")


if __name__ == "__main__":
    main()
