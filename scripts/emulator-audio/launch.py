#!/usr/bin/env python3
"""Prepare one Pleos VM for explicit gRPC audio use; never start audio or an app."""

import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid


def properties(path):
    return dict(
        (key.strip(), value.strip())
        for line in path.read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
        for key, value in [line.split("=", 1)]
    )


def command(emulator, avd, port, grpc_port):
    return [
        str(emulator), "-avd", avd, "-port", str(port), "-memory", "4096",
        "-cores", "4", "-no-snapshot", "-no-boot-anim", "-no-window",
        "-gpu", "host", "-feature", "-Vulkan", "-vsync-rate", "30",
        "-grpc", str(grpc_port), "-grpc-use-token",
    ]


def run(args, timeout=10, check=True):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode})")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get(
        "ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk")))
    parser.add_argument("--avd-home", type=Path, default=Path.home() / ".android/avd")
    parser.add_argument("--avd", default="Doran_Verification")
    parser.add_argument("--port", type=int, default=5554)
    parser.add_argument("--grpc-port", type=int, default=8554)
    parser.add_argument("--console-port", type=int, default=8088)
    parser.add_argument("--boot-timeout", type=int, default=180)
    parser.add_argument("--output", type=Path, help="New private directory; must not exist")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This verified launch profile is for macOS Apple Silicon")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.avd):
        parser.error("Invalid AVD name")
    ports = [args.port, args.port + 1, args.grpc_port, args.console_port]
    if args.port % 2 or not 5554 <= args.port <= 5682 or len(set(ports)) != 4:
        parser.error("Use an even emulator port (5554..5682) and distinct service ports")
    if not all(1024 <= port <= 65535 for port in ports) or args.boot_timeout < 1:
        parser.error("Invalid port or timeout")

    os.umask(0o077)
    state_root = Path.home() / ".local/state/doran/emulator-audio"
    state_root.mkdir(parents=True, exist_ok=True)
    lock = (state_root / "launcher.lock").open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.error("Another audio launcher owns the VM slot")
    sdk, avd_home = args.sdk.expanduser().resolve(), args.avd_home.expanduser().resolve()
    emulator, adb = sdk / "emulator/emulator", sdk / "platform-tools/adb"
    if not emulator.is_file() or not adb.is_file():
        parser.error("SDK emulator/adb not found")
    if properties(sdk / "emulator/source.properties").get("Pkg.Revision") != "37.2.13":
        parser.error("This profile was verified with Emulator 37.2.13; review version first")
    avd_path = Path(properties(avd_home / f"{args.avd}.ini")["path"])
    config = properties(avd_path / "config.ini")
    if "pleos_car_emulator" not in config.get("image.sysdir.1", ""):
        parser.error("Selected AVD is not the verified Pleos image")
    if config.get("hw.audioInput") != "yes" or config.get("hw.audioOutput") != "yes":
        parser.error("Expected existing virtual audioInput/audioOutput=yes; no config was changed")
    process_text = run(["ps", "-axo", "command="]).stdout
    if any(re.search(r"(?:^|/)qemu-system-|(?:^|/)emulator\s+-avd", line)
           for line in process_text.splitlines()):
        parser.error("A VM is already running; shut it down normally before using this slot")
    if re.search(r"\b(GradleDaemon|GradleWorkerMain|K2JVMCompiler|KotlinCompileDaemon|xcodebuild)\b",
                 process_text):
        parser.error("A native build/compiler process is present; finish it before using this slot")
    if run([str(adb), "devices"]).stdout.strip().splitlines()[1:]:
        parser.error("An adb device is present; this launcher requires a single empty VM slot")
    ios = run(["xcrun", "simctl", "list", "devices", "booted", "--json"], check=False)
    if ios.returncode != 0:
        parser.error("Cannot confirm iOS simulators are off; no VM was started")
    if any(
        device.get("state") == "Booted"
        for devices in json.loads(ios.stdout).get("devices", {}).values() for device in devices
    ):
        parser.error("An iOS simulator is booted; shut it down before using this slot")
    for port in ports:
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                parser.error(f"Required local port {port} is occupied")

    now = lambda: dt.datetime.now(dt.timezone.utc).isoformat()
    output = (args.output or state_root / dt.datetime.now(dt.timezone.utc).strftime(
        "%Y%m%dT%H%M%S.%fZ")).expanduser().resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    stop_file, result_file = output / "stop", output / "state.json"
    stop, heartbeat_stop = threading.Event(), threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    serial = f"emulator-{args.port}"
    env = {key: value for key, value in os.environ.items() if not key.startswith("QEMU_")}
    env.update(ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(avd_home),
               QEMU_AUDIO_DRV="none", QEMU_AUDIO_DAC_FIXED_FREQ="48000")
    launch = command(emulator, args.avd, args.port, args.grpc_port)
    state = {"started_at": now(), "status": "starting", "serial": serial,
             "avd": args.avd, "command": launch, "audio_driver": "none",
             "dac_hz": 48000, "virtual_input_present": True,
             "host_microphone_capture_started": False, "playback_started": False,
             "app_launched": False, "config_changed": False, "stop_file": str(stop_file)}
    save = lambda: result_file.write_text(json.dumps(state, indent=2) + "\n")
    save()
    proc, heartbeat, owns_forward, verified_guest = None, None, False, False
    client = uuid.uuid4().hex
    local_http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def guest(*parts, check=True, timeout=10):
        return run([str(adb), "-s", serial, *parts], check=check, timeout=timeout)

    def request(path, method="GET"):
        req = urllib.request.Request(f"http://127.0.0.1:{args.console_port}{path}", method=method)
        with local_http.open(req, timeout=3) as response:
            return json.loads(response.read() or b"{}")

    def park():
        request("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")

    def maintain():
        while not heartbeat_stop.is_set():
            try:
                request(f"/browser/session?client={client}", "POST")
            except Exception:
                pass
            heartbeat_stop.wait(1)

    def wait_until(predicate, timeout, label):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if stop.is_set() or stop_file.exists():
                raise InterruptedError("Stop requested")
            if proc.poll() is not None:
                raise RuntimeError("Emulator exited before readiness")
            try:
                if predicate():
                    return
            except subprocess.TimeoutExpired:
                pass
            stop.wait(1)
        raise RuntimeError(f"Timed out waiting for {label}")

    try:
        with (output / "emulator.log").open("w") as log:
            proc = subprocess.Popen(launch, env=env, stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
        state["launcher_pid"] = proc.pid
        save()
        wait_until(lambda: guest("shell", "getprop", "sys.boot_completed", check=False,
                                 timeout=5).stdout.strip() == "1", args.boot_timeout, "boot")
        if guest("emu", "avd", "name").stdout.splitlines()[0].strip() != args.avd:
            raise RuntimeError("Serial does not identify the selected AVD")
        verified_guest = True
        guest("forward", "--no-rebind", f"tcp:{args.console_port}", "tcp:8088")
        owns_forward = True
        heartbeat = threading.Thread(target=maintain, daemon=True)
        heartbeat.start()

        def console_ready():
            try:
                vehicle = request("/vehicle/state")
                return (vehicle.get("gear", {}).get("bridge_ready") and
                        vehicle.get("controls", {}).get("emulator_active") and
                        vehicle.get("controls", {}).get("dds_ready", {}).get("all"))
            except Exception:
                return False

        wait_until(console_ready, 45, "Virtual Console")
        park()
        ux = guest("shell", "dumpsys", "car_service", "--services",
                   "CarUxRestrictionsManagerService").stdout
        if "Display id: 0 UXR: DO: false UxR: 0" not in ux:
            raise RuntimeError("Park/UXR=0 was not observed")
        listeners = run(["lsof", "-nP", f"-iTCP:{args.grpc_port}", "-sTCP:LISTEN", "-Fpn"]).stdout
        addresses = [line[1:] for line in listeners.splitlines() if line.startswith("n")]
        if not addresses or any(address not in (f"127.0.0.1:{args.grpc_port}",
                                                f"[::1]:{args.grpc_port}") for address in addresses):
            raise RuntimeError("gRPC listener is not exclusively loopback")
        pids = {int(line[1:]) for line in listeners.splitlines() if line.startswith("p")}
        if any(os.getpgid(pid) != proc.pid for pid in pids):
            raise RuntimeError("gRPC listener is not owned by this launcher process group")
        discovery_root = Path.home() / "Library/Caches/TemporaryItems/avd/running"
        candidates = [discovery_root / f"pid_{pid}.ini" for pid in pids]
        matches = [path for path in candidates if path.is_file() and
                   properties(path).get("grpc.port") == str(args.grpc_port) and
                   properties(path).get("grpc.token")]
        if len(matches) != 1:
            raise RuntimeError("Fresh authenticated emulator discovery file was not found")
        state.update(status="ready", ready_at=now(), discovery=str(matches[0]),
                     grpc_loopback_only=True, grpc_token_present=True, park_uxr_zero=True,
                     guest_user=int(guest("shell", "am", "get-current-user").stdout.strip()))
        save()
        print(f"READY: {result_file}\nDISCOVERY: {matches[0]}\nSTOP: Ctrl-C or create {stop_file}", flush=True)
        print("No app, playback, capture or microphone has been started.", flush=True)
        while not stop.wait(1) and not stop_file.exists():
            if proc.poll() is not None:
                raise RuntimeError("Emulator exited during the prepared session")
    except InterruptedError:
        state["stop_requested_at"] = now()
    except Exception as error:
        state["error"] = str(error)
        print(f"Preparation stopped: {error}", file=sys.stderr)
    finally:
        if heartbeat:
            try:
                park()
            except Exception:
                state["park_cleanup_confirmed"] = False
            else:
                state["park_cleanup_confirmed"] = True
            heartbeat_stop.set()
            heartbeat.join(timeout=4)
            try:
                request(f"/browser/session?client={client}&closing=1", "POST")
            except Exception:
                pass
        if owns_forward:
            try:
                removed = guest("forward", "--remove", f"tcp:{args.console_port}", check=False)
                state["console_forward_removed"] = removed.returncode == 0
            except Exception:
                state["console_forward_removed"] = False
        if proc is not None:
            if proc.poll() is None:
                try:
                    if verified_guest:
                        guest("emu", "kill", check=False)
                    else:
                        os.killpg(proc.pid, signal.SIGTERM)
                except Exception:
                    state["shutdown_request_failed"] = True
                try:
                    proc.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    state["shutdown_requires_attention"] = True
            state["emulator_exit_code"] = proc.poll()
        state.update(status="stopped" if proc is None or proc.poll() is not None else "shutdown_pending",
                     stopped_at=now())
        save()
        print(f"STATE: {result_file}", flush=True)
    return 1 if ("error" in state or state.get("shutdown_requires_attention") or
                 state.get("emulator_exit_code") not in (None, 0)) else 0


if __name__ == "__main__":
    sys.exit(main())
