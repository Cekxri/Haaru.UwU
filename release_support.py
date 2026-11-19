"""Shared release checks for the separately installed native Android companion."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import zipfile
import zlib
from privacy_guard import publishable

SOURCE_EXCLUDED_PARTS = {
    "build", "dist", "tools", "target", "test-classes", "__pycache__", ".gradle",
    ".kotlin", "node_modules", "kotlin-js-store", ".git",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_relative(value):
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ValueError("Invalid release path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {".", ".."} for part in value.split("/")):
        raise ValueError("Release path escapes the project: " + value)
    return path


def source_eligible(path):
    path = PurePosixPath(path)
    return publishable(path.as_posix()) and not any(part.lower() in SOURCE_EXCLUDED_PARTS for part in path.parts) \
        and path.name.lower() not in {"local.properties", "keystore.properties", "signing.properties"} \
        and path.suffix.lower() not in {".jks", ".keystore"} \
        and not str(path).startswith("native/vendor/dobby/")


def source_tree_hash(records):
    return sha(json.dumps(sorted(records, key=lambda item: item["path"]),
                          sort_keys=True, separators=(",", ":")).encode("utf-8"))


def companion_properties(manifest):
    package = manifest.get("applicationId")
    certificate = manifest.get("signingCertificateSha256", "")
    if package != "dev.cekxri.haaru" or not re.fullmatch(r"[0-9a-f]{64}", certificate):
        raise ValueError("Companion auth pin requires the verified package and signer certificate")
    return ("protocol=1\npackage=" + package + "\ncertificateSha256=" + certificate + "\n").encode("ascii")


def companion_install_properties(manifest):
    companion_properties(manifest)
    if not isinstance(manifest.get("versionCode"), int) or manifest["versionCode"] <= 0 \
            or not isinstance(manifest.get("apkBytes"), int) or not 0 < manifest["apkBytes"] <= 134217728 \
            or not isinstance(manifest.get("version"), str) or not re.fullmatch(r"[0-9A-Za-z.+_-]+", manifest["version"]) \
            or not re.fullmatch(r"[0-9a-f]{64}", manifest.get("apkSha256", "")):
        raise ValueError("Companion install metadata requires the exact verified APK version and hash")
    values = [("protocol", 1), ("package", manifest["applicationId"]), ("version", manifest["version"]),
              ("versionCode", manifest["versionCode"]), ("certificateSha256", manifest["signingCertificateSha256"]),
              ("apkSha256", manifest["apkSha256"]), ("apkBytes", manifest["apkBytes"])]
    return "".join(str(key) + "=" + str(value) + "\n" for key, value in values).encode("ascii")


def validate_sources(root, records):
    if not isinstance(records, list) or not records:
        raise ValueError("Companion manifest must record current sources")
    seen = set()
    for record in records:
        name = safe_relative(record["path"]).as_posix()
        if name in seen or not source_eligible(name):
            raise ValueError("Duplicate or private companion source: " + name)
        seen.add(name)
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Companion source is unavailable: " + name)
        data = path.read_bytes()
        if sha(data) != record["sha256"] or len(data) != record["bytes"]:
            raise ValueError("Stale companion build; rerun build_companion.py: " + name)
    current = {path.relative_to(root).as_posix() for path in (root / "companion").rglob("*")
               if path.is_file() and source_eligible(path.relative_to(root).as_posix())}
    if not current.issubset(seen):
        raise ValueError("Companion build omits current source files: " + ", ".join(sorted(current - seen)))


def validate_companion_transport(root):
    sources = list((root / "companion/src/main/kotlin").rglob("*.kt"))
    text = "\n".join(path.read_text(encoding="utf-8") for path in sources)
    if "android.net.LocalSocket" not in text or "LocalSocket(" not in text:
        raise ValueError("Companion must use the module LocalSocket IPC transport")
    if any(token in text for token in ("ProcessBuilder", "Runtime.getRuntime", "com.topjohnwu.superuser", "REQUEST_SUPERUSER")):
        raise ValueError("Companion cannot request superuser or execute shell processes")


def validate_apk_ipc_metadata(tree):
    if not re.search(r'E: meta-data\b(?:(?!\n\s*E:)[\s\S])*android:name[^\n]*"dev\.cekxri\.haaru\.IPC_PROTOCOL"'
                     r'(?:(?!\n\s*E:)[\s\S])*android:value[^\n]*=(?:\(type 0x10\))?(?:0x0*1|1)\b', tree):
        raise ValueError("Companion APK must declare integer IPC protocol 1 in Android application metadata")


def validate_dex(data):
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Invalid DEX header")
    if struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("DEX file size mismatch")
    if struct.unpack_from("<I", data, 8)[0] != zlib.adler32(data[12:]) & 0xffffffff:
        raise ValueError("DEX checksum mismatch")
    if data[12:32] != hashlib.sha1(data[32:]).digest():
        raise ValueError("DEX signature mismatch")


def validate_backend_dex(data):
    """The SDK-free root backend must not depend on JVM bootstrap facilities."""
    validate_dex(data)
    if data[4:7] not in {b"035", b"037", b"038", b"039", b"040"} or data[7] != 0:
        raise ValueError("Unsupported backend DEX version")
    if struct.unpack_from("<II", data, 36) != (112, 0x12345678):
        raise ValueError("Invalid backend DEX header or byte order")
    data_size, data_offset = struct.unpack_from("<II", data, 104)
    if data_offset < 112 or data_offset > len(data) or data_size != len(data) - data_offset:
        raise ValueError("Invalid backend DEX data bounds")
    map_offset = struct.unpack_from("<I", data, 52)[0]
    if map_offset < data_offset or map_offset % 4 or map_offset > len(data) - 4:
        raise ValueError("Invalid backend DEX map offset")
    map_count = struct.unpack_from("<I", data, map_offset)[0]
    if not map_count or map_count > (len(data) - map_offset - 4) // 12:
        raise ValueError("Invalid backend DEX map bounds")
    fixed = {0: 112, 1: 4, 2: 4, 3: 12, 4: 8, 5: 8, 6: 32, 7: 4, 8: 8}
    allowed = set(fixed) | {0x1000, 0x1001, 0x1002, 0x1003, 0x2000, 0x2001, 0x2002,
                            0x2003, 0x2004, 0x2005, 0x2006, 0xF000}
    entries, previous = {}, -1
    for index in range(map_count):
        kind, unused, count, offset = struct.unpack_from("<HHII", data, map_offset + 4 + index * 12)
        if kind not in allowed or unused or kind in entries or not count or offset <= previous or offset >= len(data):
            raise ValueError("Invalid backend DEX map entry")
        if kind in fixed and (offset % 4 or count > (len(data) - offset) // fixed[kind]):
            raise ValueError("Invalid backend DEX fixed table bounds")
        entries[kind] = (count, offset); previous = offset
    if entries.get(0) != (1, 0) or entries.get(0x1000) != (1, map_offset):
        raise ValueError("Backend DEX header/map declaration is inconsistent")
    for kind, header in ((1, 56), (2, 64), (3, 72), (4, 80), (5, 88), (6, 96)):
        count, offset = struct.unpack_from("<II", data, header)
        if count == 0:
            if offset or kind in entries: raise ValueError("Inconsistent empty backend DEX table")
        elif entries.get(kind) != (count, offset):
            raise ValueError("Backend DEX table/map declaration is inconsistent")
    if 7 in entries or 8 in entries:
        raise ValueError("Backend DEX contains unsupported call-site/method-handle bootstrap tables")
    string_count, strings_offset = struct.unpack_from("<II", data, 56)
    type_count, types_offset = struct.unpack_from("<II", data, 64)
    if entries.get(0x2002, (0, 0))[0] != string_count:
        raise ValueError("Backend DEX string-data count is inconsistent")
    strings, string_lengths = [], []
    for index in range(string_count):
        position = struct.unpack_from("<I", data, strings_offset + index * 4)[0]
        if not data_offset <= position < len(data): raise ValueError("Invalid backend DEX string reference")
        declared_length = 0
        for byte_index in range(5):
            if position >= len(data): raise ValueError("Truncated backend DEX string length")
            value = data[position]; position += 1
            if byte_index == 4 and value & 0xF0: raise ValueError("Invalid backend DEX string length")
            declared_length |= (value & 0x7F) << (byte_index * 7)
            if not value & 0x80: break
        else: raise ValueError("Invalid backend DEX string length")
        end = data.find(b"\x00", position)
        if end < 0: raise ValueError("Unterminated backend DEX string")
        strings.append(data[position:end])
        string_lengths.append(declared_length)
    previous_index = -1
    forbidden = {b"Ljava/lang/invoke/LambdaMetafactory;", b"Ljava/lang/invoke/StringConcatFactory;"}
    for index in range(type_count):
        string_index = struct.unpack_from("<I", data, types_offset + index * 4)[0]
        if string_index <= previous_index or string_index >= string_count:
            raise ValueError("Invalid backend DEX type reference")
        descriptor = strings[string_index]; previous_index = string_index
        text = descriptor.decode("utf-8", errors="surrogatepass")
        if len(text.encode("utf-16le", errors="surrogatepass")) // 2 != string_lengths[string_index]:
            raise ValueError("Invalid backend DEX type string length")
        if descriptor in forbidden: raise ValueError("Backend DEX depends on a JVM-only bootstrap factory")
        base = descriptor.lstrip(b"[")
        primitive = base in {b"V", b"Z", b"B", b"S", b"C", b"I", b"J", b"F", b"D"}
        reference = base.startswith(b"L") and base.endswith(b";") and len(base) > 2
        if descriptor.startswith(b"[") and base == b"V": primitive = False
        if reference:
            name = base[1:-1]
            reference = not (name.startswith(b"/") or name.endswith(b"/") or b"//" in name
                             or any(byte in name for byte in (b".", b";", b"[", b" ", b"\n", b"\r")))
        if not primitive and not reference: raise ValueError("Invalid backend DEX type descriptor")


def build_tools(root, manifest):
    version = manifest.get("androidBuildTools")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Companion manifest has no pinned Android build-tools version")
    candidates = []
    recorded = manifest.get("buildToolsPath")
    if recorded:
        path = Path(recorded)
        candidates.append(path if path.is_absolute() else root / safe_relative(recorded))
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]) / "build-tools" / version)
    for candidate in candidates:
        aapt = candidate / ("aapt2.exe" if os.name == "nt" else "aapt2")
        signer = candidate / ("apksigner.bat" if os.name == "nt" else "apksigner")
        if aapt.is_file() and signer.is_file():
            return aapt, signer
    raise ValueError("Official Android build-tools " + version + " are required to verify the APK")


def run_tool(root, executable, arguments):
    prefix = [str(executable)]
    if os.name == "nt" and executable.name.lower() == "apksigner.bat":
        # The batch file expands its absolute non-ASCII location into a Java
        # argument, which Windows Java launchers can turn into '?'. Invoke the
        # same official signer jar using a relative project argument instead.
        jar = executable.parent / "lib/apksigner.jar"
        java = shutil.which("java")
        if not java or not jar.is_file():
            raise ValueError("Java and the official apksigner jar are required")
        jar_arg = jar.relative_to(root) if jar.is_relative_to(root) else jar
        prefix = [java, "-jar", str(jar_arg)]
    args = prefix + [str(arg.relative_to(root)) if isinstance(arg, Path)
                              and arg.is_relative_to(root) else str(arg) for arg in arguments]
    completed = subprocess.run(args, cwd=root, check=True, capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
    return completed.stdout + completed.stderr


def validate_companion(root, version, version_code):
    path = root / "dist/companion-build-manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("buildMode") != "built-and-staged" or manifest.get("version") != version \
            or manifest.get("versionCode") != int(version_code) \
            or manifest.get("applicationId") != "dev.cekxri.haaru":
        raise ValueError("Build the matching Haaru companion before packaging")
    validate_sources(root, manifest["sources"])
    validate_companion_transport(root)
    if manifest.get("sourceTreeSha256") != source_tree_hash(manifest["sources"]):
        raise ValueError("Companion source manifest hash mismatch")
    name = safe_relative(manifest["apkFile"])
    if len(name.parts) != 1 or name.suffix != ".apk":
        raise ValueError("Companion APK must be a dist basename")
    apk = root / "dist" / name
    if sha(apk.read_bytes()) != manifest["apkSha256"]:
        raise ValueError("Companion APK SHA-256 mismatch")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError("Companion APK ZIP integrity failed")
        for entry in names:
            safe_relative(entry.rstrip("/"))
        if archive.read("AndroidManifest.xml")[:4] != b"\x03\x00\x08\x00":
            raise ValueError("Companion APK needs a compiled Android manifest")
        dex_names = [entry for entry in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", entry)]
        if not dex_names:
            raise ValueError("Companion APK has no Android DEX")
        for entry in dex_names:
            validate_dex(archive.read(entry))
    aapt, signer = build_tools(root, manifest)
    badging = run_tool(root, aapt, ["dump", "badging", apk])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != (manifest["applicationId"], str(version_code), version):
        raise ValueError("Actual APK manifest package/version differs from release")
    if "android.permission.REQUEST_SUPERUSER" in badging:
        raise ValueError("Companion APK cannot request independent superuser permission")
    tree = run_tool(root, aapt, ["dump", "xmltree", apk, "--file", "AndroidManifest.xml"])
    validate_apk_ipc_metadata(tree)
    signing = run_tool(root, signer, ["verify", "--verbose", "--print-certs", apk])
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signing, re.M)
    certificate = manifest.get("signingCertificateSha256", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", certificate) or [value.lower() for value in certificates] != [certificate]:
        raise ValueError("Actual APK signing certificate differs from companion manifest")
    if not isinstance(manifest.get("libraries"), dict) or not manifest["libraries"]:
        raise ValueError("Companion manifest must record library version pins")
    return manifest, {"apkFile": name.as_posix(), "apkSha256": manifest["apkSha256"],
                      "applicationId": manifest["applicationId"], "dexFiles": len(dex_names),
                      "binaryManifestVerified": True, "signatureVerified": True,
                      "signingCertificateSha256": certificate,
                      "androidBuildTools": manifest["androidBuildTools"],
                      "companionSourceSha256": manifest["sourceTreeSha256"],
                      "appIpcSourceVerified": True, "appRequestsSuperuser": False}
