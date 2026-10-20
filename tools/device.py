#!/usr/bin/env python3
"""Find the paired test device by identity; refresh wireless addresses through mDNS."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / ".tools/device.json"

def adb_path():
    candidates = [os.environ.get("ADB"), shutil.which("adb"),
        str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb"),
        str(ROOT / ".tools/android-sdk/platform-tools/adb"),
        str(ROOT.parent.parent / ".tools/android-sdk/platform-tools/adb")]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise RuntimeError("找不到 adb；请设置 ADB 或 ANDROID_HOME。")

ADB = adb_path()
env = dict(os.environ)
env["ANDROID_USER_HOME"] = str(ROOT / ".tools/android-home")
existing_key = ROOT.parent.parent / ".tools/adb-home/.android/adbkey"
if existing_key.is_file():
    env.setdefault("ADB_VENDOR_KEYS", str(existing_key))

def adb(*args, timeout=15):
    return subprocess.run([ADB, *args], env=env, capture_output=True, text=True, timeout=timeout)

def short_run(command, seconds=3):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        output, _ = process.communicate(timeout=seconds)
    except subprocess.TimeoutExpired:
        process.terminate()
        output, _ = process.communicate(timeout=3)
    return output

def transports():
    result = adb("devices", "-l")
    return [line.split()[0] for line in result.stdout.splitlines()[1:]
        if len(line.split()) > 1 and line.split()[1] == "device"]

def identity(transport):
    def prop(name):
        result = adb("-s", transport, "shell", "getprop", name, timeout=6)
        return result.stdout.strip() if result.returncode == 0 else ""
    serial = prop("ro.serialno")
    return {"serial": serial, "model": prop("ro.product.model"), "sdk": prop("ro.build.version.sdk")}

def discover(expected_serial):
    addresses = []
    result = adb("mdns", "services", timeout=8)
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 3 and "_adb-tls-connect" in line and expected_serial.lower() in fields[0].lower():
            addresses.append((fields[0], fields[-1]))
    # Bonjour is authoritative on macOS; libadbmdns can miss services present in mDNSResponder.
    if shutil.which("dns-sd"):
        output = short_run(["dns-sd", "-B", "_adb-tls-connect._tcp", "local"], 3)
        instances = set()
        for line in output.splitlines():
            if " Add " in line and "_adb-tls-connect._tcp." in line:
                name = line.split("_adb-tls-connect._tcp.", 1)[1].strip()
                if expected_serial.lower() in name.lower():
                    instances.add(name)
        for name in sorted(instances):
            resolved = short_run(["dns-sd", "-L", name, "_adb-tls-connect._tcp", "local"], 3)
            for host, port in re.findall(r"can be reached at (\S+):(\d+)", resolved):
                try:
                    for address in socket.getaddrinfo(host.rstrip("."), int(port), socket.AF_INET, socket.SOCK_STREAM):
                        addresses.append((name, f"{address[4][0]}:{port}"))
                except OSError:
                    pass
    return list(dict.fromkeys(addresses))

def connect(address):
    result = adb("connect", address, timeout=12)
    if "connected to" not in result.stdout and "already connected" not in result.stdout:
        return False
    return adb("-s", address, "get-state", timeout=5).stdout.strip() == "device"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", help="首次绑定或显式更换设备时提供的当前地址")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    saved = json.loads(STATE.read_text()) if STATE.exists() else {}
    selected = None
    device = None
    if args.address:
        if not connect(args.address):
            raise RuntimeError("当前调试地址无法连接；请确认同一局域网、无线调试与配对授权。")
        selected = args.address
        device = identity(selected)
        if not device["serial"]:
            raise RuntimeError("设备身份读取失败；未保存自动连接目标。")
    else:
        serial = saved.get("serial")
        if not serial:
            raise RuntimeError("尚未绑定测试设备，请先用 --address 当前IP:端口 连接一次。")
        for transport in transports():
            candidate = identity(transport)
            if candidate["serial"] == serial:
                selected, device = transport, candidate
                break
        if not selected:
            for service, address in discover(serial):
                if connect(address):
                    candidate = identity(address)
                    if candidate["serial"] == serial:
                        selected, device = address, candidate
                        saved["service"] = service
                        break
        if not selected:
            raise RuntimeError("没有发现已绑定手机的当前调试服务。请打开无线调试并保持同一局域网；也可使用 USB。未尝试其他设备。")
    saved.update(device, last_transport=selected)
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(saved, ensure_ascii=False, indent=2) + "\n")
    print(f"已识别 {device['model']} / Android API {device['sdk']} / {selected}", file=sys.stderr, flush=True)
    command = args.command
    if command[:1] == ["--"]:
        command = command[1:]
    if command:
        return subprocess.call([ADB, "-s", selected, *command], env=env)
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
