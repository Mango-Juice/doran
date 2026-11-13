#!/usr/bin/env python3
"""Exercise the official Pleos Virtual Console and capture real release UI evidence.

Uses the same one-second browser-session heartbeat as the bundled console page.
Only emulator devices are accepted. Does not inject app state or fake SDK output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "app.doran.car"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=8088)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expect-unpaired", action="store_true")
    parser.add_argument("--keep-running", action="store_true", help="Exercise an already running app without a cold restart")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    base = f"http://127.0.0.1:{args.port}"
    client = str(uuid.uuid4())
    stop = threading.Event()
    results: dict = {"started_at": datetime.now(timezone.utc).isoformat(), "serial": args.serial,
                     "scope": "Pleos emulator; Virtual Console signals; release UI only, no microphone or AI verification",
                     "checks": []}

    def adb(*command: str, timeout: int = 30, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=timeout, check=check)

    def request(path: str, method: str = "GET") -> dict:
        with urllib.request.urlopen(urllib.request.Request(base + path, method=method), timeout=5) as response:
            content = response.read().decode()
            return json.loads(content) if content else {}

    def heartbeat() -> None:
        while not stop.is_set():
            try:
                request(f"/browser/session?client={client}", "POST")
            except (OSError, ValueError):
                pass  # Readiness and every scenario assert actual bridge state separately.
            stop.wait(1)

    def save_json(name: str, value: object) -> None:
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))

    def capture(name: str, expected_text: str = "도란") -> tuple[bool, int, str]:
        state = request("/vehicle/state")
        save_json(f"{name}-vehicle.json", state)
        if not (state.get("gear", {}).get("bridge_ready")
                and state.get("controls", {}).get("emulator_active")
                and state.get("controls", {}).get("dds_ready", {}).get("all")):
            raise RuntimeError("Virtual Console bridge became inactive during verification")
        ux = adb("shell", "dumpsys", "car_service", "--services", "CarUxRestrictionsManagerService").stdout.decode()
        (args.output / f"{name}-ux.txt").write_text(ux)
        match = re.search(r"Display id: 0 UXR: DO: (true|false) UxR: (\d+)", ux)
        if not match:
            raise RuntimeError("CarUxRestrictions state unavailable")
        if not adb("shell", "pidof", PACKAGE, check=False).stdout.strip():
            raise RuntimeError("Doran process is not running")
        activity = adb("shell", "dumpsys", "activity", "activities").stdout.decode()
        (args.output / f"{name}-activity.txt").write_text(activity)
        if not any(PACKAGE in line for line in activity.splitlines() if "topResumedActivity" in line or "mResumedActivity" in line):
            raise RuntimeError("Doran is not the resumed activity")
        started = time.monotonic()
        attempt = 0
        while True:
            # A unique path prevents a failed dump from reusing a prior screen's XML.
            remote_xml = f"/sdcard/doran-verification-{client}-{name}-{attempt}.xml"
            try:
                adb("shell", "uiautomator", "dump", remote_xml)
                xml = adb("exec-out", "cat", remote_xml).stdout.decode()
                if "<hierarchy" not in xml:
                    raise RuntimeError("UIAutomator did not produce a new hierarchy")
            finally:
                adb("shell", "rm", "-f", remote_xml, check=False)
            if expected_text in xml or time.monotonic() - started > 30:
                break
            attempt += 1
            time.sleep(1)
        (args.output / f"{name}-ui.xml").write_text(xml)
        (args.output / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p").stdout)
        results.setdefault("ui_wait_seconds", {})[name] = round(time.monotonic() - started, 3)
        if expected_text not in xml:
            raise RuntimeError(f"Doran UI did not display expected text: {expected_text}")
        return match.group(1) == "true", int(match.group(2)), xml

    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise RuntimeError("This verification script accepts emulators only")
    fingerprint = adb("shell", "getprop", "ro.build.fingerprint").stdout.decode().strip()
    if "Pleos" not in fingerprint:
        raise RuntimeError("A Pleos system image is required")
    results["fingerprint"] = fingerprint
    adb("forward", f"tcp:{args.port}", "tcp:8088")
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        for _ in range(30):
            state = request("/vehicle/state")
            if state.get("gear", {}).get("bridge_ready") and state.get("controls", {}).get("dds_ready", {}).get("all"):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Virtual Console DDS bridge did not become ready")
        print("Virtual Console DDS bridge active", flush=True)
        request("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")
        if args.apk:
            results["apk_sha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
            install = adb("install", "-r", str(args.apk.resolve()), timeout=60)
            (args.output / "install.txt").write_bytes(install.stdout + install.stderr)
        if not args.keep_running:
            adb("shell", "am", "force-stop", PACKAGE)
            # Allow the OEM task organizer to complete removal before starting a new task.
            time.sleep(2)
        launch = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        (args.output / "launch.txt").write_bytes(launch.stdout + launch.stderr)
        time.sleep(4)
        driving, flags, park_xml = capture("park", "차량 연결 코드" if args.expect_unpaired else "도란")
        assert not driving and flags == 0, "Expected parked UX state"
        codes = re.findall(r'text="(\d{3}\s+\d{3})"', park_xml)
        if args.expect_unpaired:
            assert "연결 코드 받기" in park_xml, "Expected parked pairing control"
            assert codes, "Expected a real pairing code before testing its concealment"
        results["checks"].append({"name": "park", "passed": True})
        request("/vehicle/gear?gear=Drive", "PUT")
        request("/vehicle/controls?speed=20&accelerator=0&brake=0&steering=0", "PUT")
        time.sleep(1)
        driving, flags, drive_xml = capture("drive20", "정차 후 휴대폰을" if args.expect_unpaired else "도란")
        assert driving and flags & 64, "Expected driving NO_SETUP restriction"
        if args.expect_unpaired:
            assert "정차 후 휴대폰을" in drive_xml, "Expected parked-only pairing notice"
            assert "연결 코드 받기" not in drive_xml, "Pairing setup must be hidden while driving"
            assert "차량 연결 코드" not in drive_xml, "Pairing code must be absent from accessibility semantics"
            for code in codes:
                assert code not in drive_xml, "Pairing code must be hidden while driving"
        results["checks"].append({"name": "drive20", "passed": True, "active_restrictions": flags})
        request("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")
        time.sleep(1)
        driving, flags, restored_xml = capture("park-restored", "차량 연결 코드" if args.expect_unpaired else "도란")
        assert not driving and flags == 0, "Expected parked UX restoration"
        if args.expect_unpaired:
            assert "연결 코드 받기" in restored_xml, "Expected pairing control to return when parked"
            assert re.findall(r'text="(\d{3}\s+\d{3})"', restored_xml), "Expected pairing code to return when parked"
            assert "정차 후 휴대폰을" not in restored_xml, "Driving-only notice must disappear when parked"
        results["checks"].append({"name": "park_restored", "passed": True})
        results["passed"] = True
        print("Park → Drive 20 km/h → Park verified", flush=True)
    except Exception as error:
        results["passed"] = False
        results["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        # Always try to leave the virtual vehicle parked before closing our console session.
        for path in ("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "/vehicle/gear?gear=Park"):
            try:
                request(path, "PUT")
            except (OSError, ValueError) as error:
                results.setdefault("cleanup_errors", []).append(str(error))
        stop.set()
        worker.join(timeout=6)
        try:
            request(f"/browser/session?client={client}&closing=1", "POST")
        except (OSError, ValueError):
            pass
        results["finished_at"] = datetime.now(timezone.utc).isoformat()
        save_json("result.json", results)


if __name__ == "__main__":
    main()
