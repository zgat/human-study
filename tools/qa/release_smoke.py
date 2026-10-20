#!/usr/bin/env python3
"""Install the specified release bytes and verify local loading and background/resume."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import shlex
import time
import xml.etree.ElementTree as ET

from device_soak import Device, PACKAGE, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve(strict=True)
    logs = ROOT / "logs/release-smoke"
    output = ROOT / "outputs/release-smoke"
    logs.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    result = {"collectedAt": datetime.now().astimezone().isoformat(), "status": "failed"}
    try:
        device = Device(logs)
        result["device"] = {
            "model": device.shell("getprop ro.product.model").strip(),
            "sdk": device.shell("getprop ro.build.version.sdk").strip(),
            "abi": device.shell("getprop ro.product.cpu.abi").strip(),
            "displaySize": device.shell("wm size").strip(),
            "memoryPageBytes": device.shell("getconf PAGESIZE").strip(),
        }
        memory = re.search(r"^MemTotal:\s*(\d+)\s*kB", device.shell("cat /proc/meminfo"), re.MULTILINE)
        result["device"]["memTotalKiB"] = int(memory.group(1)) if memory else None
        remote = "/data/local/tmp/meridian-atlas-release.apk"
        pushed = device.run("push", "-z", "any", str(apk), remote, timeout=120)
        (logs / "push.txt").write_text(pushed.stdout + pushed.stderr)
        installed = device.shell("pm install -r " + shlex.quote(remote), timeout=120)
        (logs / "install.txt").write_text(installed)
        assert "Success" in installed, installed
        result["installedApk"] = device.verify_apk(PACKAGE, apk)
        package = device.shell("dumpsys package " + PACKAGE)
        (logs / "package.txt").write_text(package)
        assert "DEBUGGABLE" not in package, "Expected the release build"
        launch = device.shell("am start -W -n " + PACKAGE + "/.MainActivity")
        (logs / "launch.txt").write_text(launch)
        assert "Status: ok" in launch
        remote_xml = "/data/local/tmp/meridian-release-window.xml"
        deadline = time.monotonic() + 90
        while True:
            foreground, focus = device.foreground()
            if foreground:
                device.shell("uiautomator dump " + remote_xml)
                raw = device.shell("cat " + remote_xml)
                root = ET.fromstring(raw)
                own = [node for node in root.iter("node") if node.get("package") == PACKAGE]
                texts = [node.get("text", "") for node in own]
                assert not any("请检查模型资源" in value for value in texts), "Model error shown"
                if own and not any("正在载入" in value for value in texts):
                    (logs / "ready-window.xml").write_text(raw)
                    result["localUiReady"] = True
                    break
            if time.monotonic() >= deadline:
                raise RuntimeError("Release did not reach the locally loaded UI")
            time.sleep(.5)
        result["firstScreenshot"] = device.screenshot(output / "release-loaded.png")
        before = device.pids()
        assert len(before) == 1
        device.shell("input keyevent KEYCODE_HOME")
        time.sleep(2)
        away, _ = device.foreground()
        assert not away, "Home did not leave the app"
        resumed = device.shell("am start -W -n " + PACKAGE + "/.MainActivity")
        (logs / "resume.txt").write_text(resumed)
        time.sleep(1)
        foreground, focus = device.foreground()
        assert foreground and device.pids() == before, "App process did not survive resume"
        result["resume"] = {"samePid": before[0], "foreground": focus}
        result["resumedScreenshot"] = device.screenshot(output / "release-resumed.png")
        assert result["firstScreenshot"]["saved"] and result["resumedScreenshot"]["saved"]
        result["status"] = "passed"
    except Exception as error:
        result["failure"] = str(error)
    (ROOT / "manifests/release-device.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
