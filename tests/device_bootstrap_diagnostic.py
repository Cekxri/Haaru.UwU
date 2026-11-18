#!/usr/bin/env python3
"""Read Haaru's native bootstrap receipt from an explicitly selected ADB phone.

No installation, configuration changes, log clearing, process changes or reboot.
The receipt check establishes native acknowledgement, not application behavior.
"""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULE = "/data/adb/modules/haaru"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", type=Path, default=ROOT / "tools/platform-tools/adb.exe")
    parser.add_argument("--output", type=Path, help="Optional local JSON report")
    args = parser.parse_args()

    def shell(command, root=False):
        argv = [str(args.adb), "-s", args.serial, "shell"]
        argv.extend(["su", "-c", command] if root else [command])
        result = subprocess.run(argv, capture_output=True, encoding="utf-8", errors="replace", timeout=15)
        if result.returncode:
            raise RuntimeError((result.stderr.strip() or result.stdout.strip() or "ADB command failed")[:1024])
        return result.stdout.strip()

    result = {"readOnly": True, "serial": args.serial}
    try:
        sdk = int(shell("getprop ro.build.version.sdk"))
        boot = shell("cat /proc/sys/kernel/random/boot_id")
        phone_pids = [int(value) for value in shell("pidof com.android.phone").split()]
        now = int(shell("date +%s")) * 1000
        raw = shell(f"cat {MODULE}/data/global-status.json", root=True)
        ack = json.loads(raw)
        if not isinstance(ack, dict):
            raise ValueError("Native acknowledgement is not an object")
        receipt = {key: ack.get(key) for key in (
            "ok", "initialized", "applied", "hooks", "sdk", "bootId", "configHash", "timestampMs",
            "phonePid", "stage", "error", "connected", "requiredHooks", "coverageComplete", "supportsCountry", "supportsCarrierName")}
        integer = lambda value: type(value) is int
        timestamp = ack.get("timestampMs")
        fresh = integer(timestamp) and -5000 <= now - timestamp <= 15000
        acknowledged = (
            ack.get("initialized") is True and integer(ack.get("hooks")) and integer(ack.get("requiredHooks")) and ack["requiredHooks"] >= 3 and ack["hooks"] == ack["requiredHooks"] and ack.get("coverageComplete") is True
            and integer(ack.get("sdk")) and ack["sdk"] == sdk and ack.get("bootId") == boot
            and integer(ack.get("phonePid")) and ack["phonePid"] in phone_pids
            and ack.get("connected") is not False and fresh
        )
        result.update(deviceSdk=sdk, deviceBootId=boot, phonePids=phone_pids,
                      receipt=receipt, fresh=fresh, nativeAcknowledged=acknowledged)
        logs = shell("logcat -d -b all -v brief -s LSPlant Haaru Haaru HaaruGlobal", root=True)
        result["nativeLogTail"] = logs.splitlines()[-60:]
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        result["diagnosticError"] = str(error)[:2048]
        result["nativeAcknowledged"] = False
    report = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    print(report, end="")


if __name__ == "__main__":
    main()
