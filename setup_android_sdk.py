#!/usr/bin/env python3
"""Set up the hash-pinned Windows SDK used by the native Haaru build."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import ssl
import stat
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
SDK = ROOT / "tools/android-sdk"
BASE_URL = "https://dl.google.com/android/repository/"
XSI = "http://www.w3.org/2001/XMLSchema-instance"
LICENSES = {
    "android-sdk-license": "9002c006f4b8d9a16e715a9fa4df30ddb8abf9d9",
    "android-sdk-preview-license": "79120722343a6f314e0719f863036c702b0e6b2a",
}
PACKAGES = [
    {"path": "platforms;android-37.2", "destination": "platforms/android-37.2", "root": "android-37.2",
     "archive": "platform-37.2_r02.zip", "bytes": 68094624,
     "sha1": "d52b3062af96f7c4dfdca5a479cd1db33ae83762",
     "sha256": "17af98ec1427a1fed9ea278184aba71e53b87eca103655602f9fc64ef11ea4d3",
     "revision": [2], "displayName": "Android SDK Platform 37.2", "license": "android-sdk-license"},
    {"path": "build-tools;36.0.0", "destination": "build-tools/36.0.0", "root": "android-16",
     "archive": "build-tools_r36_windows.zip", "bytes": 58699878,
     "sha1": "f16ccffd34de8790dede813a6c7d8e2c11a27b50",
     "sha256": "aa1095cb14d83e483818a748a2c06faaeb8e601561b06a356a119a1b2ca280d3",
     "revision": [36, 0, 0], "displayName": "Android SDK Build-Tools 36", "license": "android-sdk-license"},
    {"path": "platform-tools", "destination": "platform-tools", "root": "platform-tools",
     "archive": "platform-tools_r37.0.3-win.zip", "bytes": 8188595,
     "sha1": "b3fde5239ad89229d5a9178375b81c9887a24172",
     "sha256": "b5d2015f5d5b7a24a2ac646a245f20f63fb501e4174c61863c74bed1df6de4bd",
     "revision": [37, 0, 3], "displayName": "Android SDK Platform-Tools 37.0.3", "license": "android-sdk-preview-license"},
]


def digest_file(path, algorithm="sha256"):
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inside_sdk(path):
    resolved = path.resolve()
    sdk = SDK.resolve()
    if not sdk.is_relative_to(ROOT.resolve()) or SDK.is_symlink() \
            or getattr(SDK, "is_junction", lambda: False)():
        raise ValueError("SDK directory must remain inside this workspace")
    if resolved == sdk or not resolved.is_relative_to(sdk):
        raise ValueError("SDK operation escaped its package directory")
    return resolved


def license_text(name):
    text = (ROOT / "sdk-setup" / (name + ".txt")).read_text(encoding="utf-8").strip()
    if hashlib.sha1(text.encode("utf-8")).hexdigest() != LICENSES[name]:
        raise ValueError("Pinned SDK licence text differs: " + name)
    return text


def metadata(package):
    ET.register_namespace("xsi", XSI)
    root = ET.Element("sdk:sdk-repository", {
        "xmlns:sdk": "http://schemas.android.com/sdk/android/repo/repository2/03",
        "xmlns:common": "http://schemas.android.com/repository/android/common/02",
        "xmlns:generic": "http://schemas.android.com/repository/android/generic/02",
    })
    ET.SubElement(root, "license", {"id": package["license"], "type": "text"}).text = license_text(package["license"])
    local = ET.SubElement(root, "localPackage", {"path": package["path"]})
    platform = package["path"].startswith("platforms;")
    details = ET.SubElement(local, "type-details", {"{" + XSI + "}type":
                         "sdk:platformDetailsType" if platform else "generic:genericDetailsType"})
    if platform:
        for name, value in (("api-level", "37.2"), ("codename", ""), ("extension-level", "24"),
                            ("base-extension", "true")):
            ET.SubElement(details, name).text = value
        ET.SubElement(details, "layoutlib", {"api": "15"})
    revision = ET.SubElement(local, "revision")
    for name, value in zip(("major", "minor", "micro"), package["revision"]):
        ET.SubElement(revision, name).text = str(value)
    ET.SubElement(local, "display-name").text = package["displayName"]
    ET.SubElement(local, "uses-license", {"ref": package["license"]})
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def archive_path(package, offline):
    path = SDK / package["archive"]
    if not path.is_file():
        if offline:
            raise ValueError("Missing cached SDK archive: " + path.name)
        temporary = path.with_suffix(".download")
        inside_sdk(temporary)
        context = ssl.create_default_context()
        # Work around this Windows host's TLS 1.3 interception failure.
        context.maximum_version = ssl.TLSVersion.TLSv1_2
        try:
            with urllib.request.urlopen(BASE_URL + package["archive"], timeout=45, context=context) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if temporary.stat().st_size != package["bytes"] or digest_file(temporary) != package["sha256"]:
                raise ValueError("Downloaded SDK archive differs from pinned official content")
            temporary.replace(path)
        finally:
            if temporary.exists():
                temporary.unlink()
    if path.stat().st_size != package["bytes"] or digest_file(path) != package["sha256"] \
            or digest_file(path, "sha1") != package["sha1"]:
        raise ValueError("Cached SDK archive hash differs: " + path.name)
    return path


def relative_entry(info, package):
    name = info.filename
    path = PurePosixPath(name)
    if "\\" in name or ":" in name or path.is_absolute() or any(part in {".", ".."} for part in name.split("/")) \
            or not path.parts or path.parts[0] != package["root"] \
            or stat.S_ISLNK(info.external_attr >> 16):
        raise ValueError("Unsafe SDK archive entry: " + name)
    return Path(*path.parts[1:])


def installed_matches(archive, package, destination):
    if not destination.is_dir() or destination.is_symlink() \
            or getattr(destination, "is_junction", lambda: False)():
        return False
    with zipfile.ZipFile(archive) as source:
        if source.testzip() is not None:
            raise ValueError("SDK archive CRC failed")
        for info in source.infolist():
            relative = relative_entry(info, package)
            if info.is_dir() or relative.name == "package.xml":
                continue
            current = destination / relative
            if not current.is_file() or current.is_symlink() or current.stat().st_size != info.file_size:
                return False
            digest = hashlib.sha256()
            with source.open(info) as original:
                for chunk in iter(lambda: original.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest_file(current) != digest.hexdigest():
                return False
    try:
        root = ET.fromstring((destination / "package.xml").read_bytes())
        local = next(node for node in root if node.tag.split("}")[-1] == "localPackage")
        revision = next(node for node in local if node.tag.split("}")[-1] == "revision")
        actual = [int(node.text) for node in revision]
        if local.attrib.get("path") != package["path"] or actual != package["revision"]:
            return False
        if package["path"].startswith("platforms;"):
            details = next(node for node in local if node.tag.split("}")[-1] == "type-details")
            level = next(node.text for node in details if node.tag.split("}")[-1] == "api-level")
            if level != "37.2":
                return False
        return True
    except (OSError, ValueError, StopIteration, ET.ParseError):
        return False


def install(archive, package, destination, repair):
    inside_sdk(destination)
    if destination.exists() and not repair:
        raise ValueError("SDK package differs; rerun with --repair while no build is running: " + package["path"])
    stage = Path(tempfile.mkdtemp(prefix=".haaru-sdk-stage-", dir=SDK))
    inside_sdk(stage)
    try:
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                relative = relative_entry(info, package)
                target = stage / relative
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(info) as input_file, target.open("wb") as output:
                    shutil.copyfileobj(input_file, output)
        (stage / "package.xml").write_bytes(metadata(package))
        if not installed_matches(archive, package, stage):
            raise ValueError("Extracted SDK package verification failed")
        if destination.exists():
            inside_sdk(destination)
            shutil.rmtree(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        stage.replace(destination)
    finally:
        if stage.exists():
            inside_sdk(stage)
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use existing pinned archive downloads")
    parser.add_argument("--verify-only", action="store_true", help="Read and verify installed SDK without mutation or download")
    parser.add_argument("--accept-licenses", action="store_true", help="Accept the bundled official SDK and preview licence texts")
    parser.add_argument("--repair", action="store_true", help="Replace a differing package; do not use during a build")
    args = parser.parse_args()
    if os.name != "nt":
        raise SystemExit("This setup uses pinned Windows SDK archives; use matching SDK packages on other hosts")
    inside_sdk(SDK / ".haaru-path-check")
    if not args.verify_only:
        SDK.mkdir(parents=True, exist_ok=True)
    for name, expected in LICENSES.items():
        license_text(name)
        marker = SDK / "licenses" / name
        accepted = expected in marker.read_text(encoding="utf-8").splitlines() if marker.is_file() else False
        if not accepted:
            if args.verify_only or not args.accept_licenses:
                raise SystemExit("Read sdk-setup/" + name + ".txt and pass --accept-licenses to accept it")
            marker.parent.mkdir(parents=True, exist_ok=True)
            previous = marker.read_text(encoding="utf-8") if marker.is_file() else ""
            marker.write_text(previous.rstrip() + "\n" + expected + "\n", encoding="utf-8")
    for package in PACKAGES:
        archive = archive_path(package, args.offline or args.verify_only)
        destination = SDK / package["destination"]
        if not installed_matches(archive, package, destination):
            if args.verify_only:
                raise SystemExit("Installed SDK differs: " + package["path"])
            install(archive, package, destination, args.repair)
        print("Verified", package["path"], package["sha256"])


if __name__ == "__main__":
    main()
