#!/usr/bin/env python3
"""Read existing Android logs; never change logging settings or control the app.

Run with uv run --no-project scripts/collect_logcat.py --adb /path/to/adb --output /path/to/evidence
Each reconnect retains the available buffer in a new file; replayed lines are not deduplicated.
"""
import argparse
import datetime
import json
import pathlib
import selectors
import signal
import subprocess
import threading
import time


def collect(adb, serial, directory, stop):
    directory.mkdir(parents=True, exist_ok=True)
    state = {"serial": serial, "status": "starting", "bytes": 0, "lastDeviceLine": None,
             "lastDataHostUtc": None, "lastReceivedDeviceLine": None}
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")

    def record(event, **fields):
        state.update(fields, status=event, hostUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
        snapshot = json.dumps(state, ensure_ascii=False) + "\n"
        with (directory / "capture-events.jsonl").open("a") as output:
            output.write(snapshot)
        temporary = directory / "capture-status.tmp"
        temporary.write_text(snapshot)
        temporary.replace(directory / "capture-status.json")

    process = None
    attempt = 0
    try:
        while not stop.is_set():
            attempt += 1
            record("connecting", attempt=attempt, possibleGap=True)
            # connect only restores the user-supplied ADB transport; it does not restart adbd.
            try:
                connected = subprocess.run([adb, "connect", serial], capture_output=True, timeout=12)
                state["connectResult"] = (connected.stdout + connected.stderr).decode(errors="replace").strip()[:300]
                online = connected.returncode == 0 and subprocess.run(
                    [adb, "-s", serial, "get-state"], capture_output=True, timeout=12,
                ).stdout.strip() == b"device"
            except subprocess.TimeoutExpired:
                state["connectResult"] = "timeout"
                online = False
            if stop.is_set():
                break
            if not online:
                record("disconnected", possibleGap=True)
                stop.wait(15)
                continue
            name = f"logcat-{run}-{attempt:04d}.txt"
            with (directory / name).open("xb") as output, (directory / (name + ".stderr")).open("xb") as errors:
                process = subprocess.Popen([adb, "-s", serial, "logcat", "-b", "all", "-v", "threadtime"],
                                           stdout=subprocess.PIPE, stderr=errors)
                record("awaiting-data", file=name, bytes=0, lastDeviceLine=None, bufferReplay=True)
                pending = b""
                heartbeat = time.monotonic()
                last_data = None
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    while not stop.is_set():
                        if selector.select(timeout=1):
                            data = process.stdout.read1(65536)
                            if not data:
                                break
                            output.write(data)
                            output.flush()
                            state["bytes"] += len(data)
                            last_data = time.monotonic()
                            state["lastDataHostUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                            lines = (pending + data).split(b"\n")
                            pending = lines.pop()[-4096:]
                            for line in lines:
                                # Only timestamps enter the manifest, never message contents.
                                if len(line) >= 18 and line[2:3] == b"-" and line[5:6] == b" ":
                                    state["lastDeviceLine"] = line[:18].decode(errors="replace")
                                    state["lastReceivedDeviceLine"] = state["lastDeviceLine"]
                            if state["status"] != "receiving":
                                record("receiving")
                        if time.monotonic() - heartbeat >= 30:
                            record("awaiting-data" if last_data is None else
                                   "idle" if time.monotonic() - last_data >= 30 else "receiving")
                            heartbeat = time.monotonic()
                process.stdout.close()
                if process.poll() is None:
                    process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                record("disconnected", exitCode=process.returncode, possibleGap=True)
                process = None
            stop.wait(15)
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            if process.stdout is not None:
                process.stdout.close()
        record("stopped", possibleGap=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    stopped = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    collect(args.adb, args.serial, args.output, stopped)
