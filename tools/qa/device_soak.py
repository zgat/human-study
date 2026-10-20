#!/usr/bin/env python3
"""Verify installed debug APKs, then sample a scoped SurfaceView gesture soak.

No installation, reboot, global setting, display-size change, or automatic app restart.
Run from any directory; paths are resolved against the current shell directory.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.zga.meridianatlas"
TEST_PACKAGE = PACKAGE + ".test"
TEST_CLASS = PACKAGE + ".ResponsiveUiDeviceTest#soakModelGestures"
STOP_ACTION = PACKAGE + ".qa.STOP_SOAK"
LONG_MAX = 9223372036854775807


class SoakFailure(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_latency(raw: str) -> dict:
    """SurfaceFlinger --latency columns: desired, actual, frame-ready timestamps."""
    refresh = None
    actual = []
    for line in raw.splitlines():
        columns = line.split()
        if len(columns) == 1 and columns[0].isdigit() and refresh is None:
            refresh = int(columns[0])
        elif len(columns) == 3 and all(value.isdigit() for value in columns):
            present = int(columns[1])
            if 0 < present < LONG_MAX:
                actual.append(present)
    return {"refreshPeriodNs": refresh, "validPresentCount": len(actual),
            "latestActualPresentNs": max(actual) if actual else None}


def parse_surface_layers(raw: str) -> list[str]:
    """Accept legacy names and Android RequestedLayerState diagnostics."""
    layers = []
    for line in raw.splitlines():
        if PACKAGE not in line or "surfaceview" not in line.lower():
            continue
        if "RequestedLayerState{" in line:
            match = re.search(r"SurfaceView\[[^\]]+\](?:\([^)]*\))?#\d+", line)
            if match:
                layers.append(match.group(0))
        else:
            layers.append(line.strip())
    return list(dict.fromkeys(layers))


def parse_memory(raw: str) -> dict:
    def number(pattern: str):
        match = re.search(pattern, raw, re.MULTILINE)
        return int(match.group(1).replace(",", "")) if match else None
    return {
        "totalPssKiB": number(r"TOTAL PSS:\s*([\d,]+)") or number(r"^\s*TOTAL\s+([\d,]+)\s"),
        "totalRssKiB": number(r"TOTAL RSS:\s*([\d,]+)"),
    }


def status_records(raw: str, key: str) -> list[dict]:
    result = []
    for match in re.finditer(r"^INSTRUMENTATION_STATUS:\s*" + re.escape(key) + r"=(.+)$", raw, re.MULTILINE):
        try:
            record = json.loads(match.group(1))
            if isinstance(record, dict):
                result.append(record)
        except json.JSONDecodeError:
            continue
    return result


class Device:
    def __init__(self, logs: Path):
        # The existing identity selector is called exactly once. Afterwards every
        # command uses its recorded transport; a lost connection is a failure.
        source = ROOT / "tools/device.py"
        selected = subprocess.run([sys.executable, str(source), "--", "get-state"],
                                  capture_output=True, text=True, timeout=90)
        (logs / "device-selection.txt").write_text(selected.stdout + selected.stderr)
        if selected.returncode or selected.stdout.strip() != "device":
            raise SoakFailure("tools/device.py 未识别出已绑定设备；没有启动测试。")
        spec = importlib.util.spec_from_file_location("atlas_paired_device", source)
        module = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        spec.loader.exec_module(module)
        self.adb = module.ADB
        self.env = module.env
        self.identity = json.loads(module.STATE.read_text())
        self.transport = self.identity["last_transport"]
        self.base = [self.adb, "-s", self.transport]

    def run(self, *args: str, timeout=20, binary=False, check=True):
        result = subprocess.run(self.base + list(args), env=self.env, capture_output=True,
                                text=not binary, timeout=timeout)
        if check and result.returncode:
            error = result.stderr.decode(errors="replace") if binary else result.stderr
            raise SoakFailure(f"ADB 命令失败 ({result.returncode}): {' '.join(args[:3])}: {error.strip()}")
        return result

    def shell(self, command: str, **kwargs) -> str:
        return self.run("shell", command, **kwargs).stdout

    def foreground(self) -> tuple[bool, str]:
        windows = self.shell("dumpsys window windows", timeout=12)
        focus = re.search(r"mCurrentFocus=(.*)", windows)
        if not focus:
            # Newer window dumps keep the authoritative focus in the per-display section.
            displays = self.shell("dumpsys window displays", timeout=12)
            focus = re.search(r"mCurrentFocus=(.*)", displays)
        if focus:
            line = focus.group(0).strip()
            return bool(re.search(r"\b" + re.escape(PACKAGE) + r"/", line)), line
        activities = self.shell("dumpsys activity activities", timeout=12)
        resumed = re.search(r"(?:topResumedActivity|mResumedActivity):?\s*=?(.*)", activities)
        line = resumed.group(0).strip() if resumed else "No foreground window evidence"
        return bool(re.search(r"\b" + re.escape(PACKAGE) + r"/", line)), line

    def pids(self) -> list[int]:
        raw = self.shell("pidof " + PACKAGE, check=False).strip()
        return [int(value) for value in raw.split() if value.isdigit()]

    def verify_apk(self, package: str, expected: Path) -> dict:
        paths = [line.removeprefix("package:").strip() for line in
                 self.shell("pm path " + package).splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].endswith("/base.apk"):
            raise SoakFailure(f"{package} 的单 APK 安装路径不符合预期: {paths}")
        remote = self.shell("sha256sum " + shlex.quote(paths[0])).split()[0].lower()
        local = sha256(expected)
        if remote != local:
            raise SoakFailure(f"{package} 已安装 SHA-256 与指定 APK 不同: installed={remote}, expected={local}")
        return {"package": package, "expectedApk": str(expected), "installedPath": paths[0], "sha256": local}

    def screenshot(self, output: Path) -> dict:
        before, evidence = self.foreground()
        if not before:
            return {"saved": False, "reason": "app_not_foreground", "foreground": evidence}
        result = self.run("exec-out", "screencap", "-p", binary=True)
        after, evidence = self.foreground()
        if not after:
            return {"saved": False, "reason": "foreground_changed_during_capture", "foreground": evidence}
        if not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            raise SoakFailure("本应用前台截图没有返回 PNG。")
        output.write_bytes(result.stdout)
        return {"saved": True, "path": str(output), "sha256": sha256(output)}

    def surface_sample(self, directory: Path) -> dict:
        raw = self.shell("dumpsys SurfaceFlinger --list", timeout=20)
        layers = parse_surface_layers(raw)
        # Do not retain an inventory of unrelated applications' surfaces.
        (directory / "surfaceview-layers.txt").write_text("\n".join(layers) + "\n")
        samples = []
        for index, layer in enumerate(layers):
            result = self.run("shell", "dumpsys SurfaceFlinger --latency " + shlex.quote(layer), check=False, timeout=20)
            (directory / f"surfaceview-{index:02d}-latency.txt").write_text(result.stdout + result.stderr)
            samples.append({"layer": layer, "returnCode": result.returncode, **parse_latency(result.stdout)})
        latest = max((item["latestActualPresentNs"] for item in samples
                      if item["latestActualPresentNs"] is not None), default=None)
        return {"layers": samples, "latestActualPresentNs": latest,
                "timestampSource": "SurfaceFlinger --latency actualPresentTime (column 2), SurfaceView layers only"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-apk", required=True, type=Path, help="本次实际运行的未混淆 debug APK")
    parser.add_argument("--expected-test-apk", required=True, type=Path, help="与该 debug APK 同次构建的 androidTest APK")
    parser.add_argument("--minutes", type=int, default=10)
    parser.add_argument("--quality", choices=("standard", "low"), default="standard")
    parser.add_argument("--mode", choices=("muscles", "overview", "meridians"), default="muscles")
    parser.add_argument("--label", default="debug-standard-muscles")
    parser.add_argument("--frametimeline", action="store_true", help="同步临时采集32MB FrameTimeline并分析实际SurfaceView呈现")
    parser.add_argument("--timestats", action="store_true", help="不清零地采集精确模型SurfaceView的实际呈现间隔")
    parser.add_argument("--timestats-original-state", choices=("infer-empty", "disabled", "enabled"),
                        default="infer-empty", help="已核验的原TimeStats开关；非空dump无法自动判断")
    args = parser.parse_args()
    if not 1 <= args.minutes <= 120:
        parser.error("--minutes 必须在 1..120 之间")
    args.expected_apk = args.expected_apk.resolve(strict=True)
    args.expected_test_apk = args.expected_test_apk.resolve(strict=True)
    label = re.sub(r"[^A-Za-z0-9_-]+", "-", args.label).strip("-")[:64] or "soak"
    run_id = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d-%H%M%S") + "-" + label
    output = ROOT / "outputs/qa" / run_id
    logs = ROOT / "logs/qa" / run_id
    output.mkdir(parents=True, exist_ok=False)
    logs.mkdir(parents=True, exist_ok=False)
    summary = {"runId": run_id, "buildVariant": "debug", "requestedMinutes": args.minutes,
               "mode": args.mode, "quality": args.quality, "status": "failed",
               "outputDirectory": str(output), "logDirectory": str(logs), "samples": [],
               "limits": ["debug 构建性能不能当作 release 性能", "不以 Compose framestats 代替 SurfaceView 呈现数据"]}
    events = (logs / "events.jsonl").open("a")
    def event(kind: str, **fields):
        record = {"time": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(), "event": kind, **fields}
        events.write(json.dumps(record, ensure_ascii=False) + "\n"); events.flush()
        print(json.dumps(record, ensure_ascii=False), flush=True)
    device = None
    process = None
    instrument_log = logs / "instrumentation.txt"
    instrument_stream = None
    stop_sent = False
    failure = None
    frame_capture = None
    time_stats_capture = None
    pid = None
    try:
        if args.frametimeline and not (ROOT / ".tools/perfetto/trace_processor_shell").is_file():
            raise SoakFailure("先运行 tools/qa/bootstrap_perfetto.py 准备项目内trace_processor。")
        device = Device(logs)
        summary["device"] = device.identity
        summary["apks"] = [device.verify_apk(PACKAGE, args.expected_apk),
                           device.verify_apk(TEST_PACKAGE, args.expected_test_apk)]
        package_dump = device.shell("dumpsys package " + PACKAGE)
        (logs / "target-package.txt").write_text(package_dump)
        if "DEBUGGABLE" not in package_dump:
            raise SoakFailure("实际目标不是 debuggable 构建；不能用 debug 测试 APK 调用混淆 release。")
        command = device.base + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
            "-e", "soak_seconds", str(args.minutes * 60 + 60), "-e", "soak_quality", args.quality,
            "-e", "soak_mode", args.mode, TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"]
        instrument_stream = instrument_log.open("w")
        process = subprocess.Popen(command, env=device.env, stdout=instrument_stream, stderr=subprocess.STDOUT)
        event("instrumentation_started", className=TEST_CLASS)
        startup = time.monotonic()
        ready = None
        while time.monotonic() - startup < 240:
            raw = instrument_log.read_text(errors="replace")
            records = status_records(raw, "atlas_soak_ready")
            if records:
                ready = records[-1]; break
            if process.poll() is not None:
                raise SoakFailure("测试未进入手势循环就退出；见 instrumentation.txt。")
            time.sleep(.5)
        if not ready:
            raise SoakFailure("240 秒内没有收到实际 SurfaceView 就绪信号。")
        if ready.get("package") != PACKAGE or ready.get("quality") != args.quality.upper():
            raise SoakFailure(f"手势驱动报告的包/精度不匹配: {ready}")
        summary["driverReady"] = ready
        pid = int(ready["pid"])
        if args.timestats:
            from timestats import Capture as TimeStatsCapture
            original = {"infer-empty": None, "disabled": False, "enabled": True}[args.timestats_original_state]
            time_stats_capture = TimeStatsCapture(device, logs, output, original_enabled=original)
            summary["timeStatsCapture"] = time_stats_capture.start(pid)
            event("timestats_started", **summary["timeStatsCapture"])
        if args.frametimeline:
            from frametimeline import Capture
            frame_capture = Capture(device, logs, output, run_id, args.minutes * 60)
            try:
                summary["frameTimelineCapture"] = frame_capture.start()
                event("frametimeline_started", **summary["frameTimelineCapture"])
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                summary["frameTimelineCaptureError"] = str(error)
                event("frametimeline_unavailable", reason=str(error))
        event("soak_ready", **ready)
        started = time.monotonic()
        duration = args.minutes * 60
        next_sample = 0
        previous_present = None
        stalled = 0
        unavailable = False
        while True:
            elapsed = time.monotonic() - started
            if process.poll() is not None:
                raise SoakFailure("耐久区间结束前 instrumentation 已退出。")
            foreground, evidence = device.foreground()
            if not foreground:
                event("foreground_lost", foreground=evidence)
                raise SoakFailure("应用失去前台，停止手势耐久测试并保留证据。")
            pids = device.pids()
            if pids != [pid]:
                raise SoakFailure(f"应用 PID 改变或消失: baseline={pid}, current={pids}")
            if elapsed >= next_sample:
                minute = next_sample // 60
                sample_dir = logs / f"minute-{minute:03d}"
                sample_dir.mkdir()
                meminfo = device.shell("dumpsys meminfo " + PACKAGE, timeout=30)
                (sample_dir / "meminfo.txt").write_text(meminfo)
                (sample_dir / "pid.txt").write_text(" ".join(map(str, pids)) + "\n")
                surface = device.surface_sample(sample_dir)
                time_stats_sample = time_stats_capture.sample() if time_stats_capture is not None else None
                latest = surface["latestActualPresentNs"]
                progressed = None if previous_present is None or latest is None else latest > previous_present
                model_progressed = progressed
                if time_stats_sample is not None and "newlyPresentedIntervals" in time_stats_sample:
                    model_progressed = None if minute == 0 else time_stats_sample["newlyPresentedIntervals"] > 0
                if latest is None:
                    unavailable = True
                if model_progressed is False:
                    stalled += 1
                elif model_progressed is True:
                    stalled = 0
                if latest is not None:
                    previous_present = latest
                record = {"minute": minute, "elapsedSeconds": round(time.monotonic() - started, 3),
                          "pid": pid, **parse_memory(meminfo), "surface": surface,
                          "surfaceTimestampProgressed": progressed,
                          "timeStats": time_stats_sample, "modelPresentationProgressed": model_progressed,
                          "screenshot": device.screenshot(output / f"minute-{minute:03d}.png")}
                if record["totalPssKiB"] is None:
                    unavailable = True
                summary["samples"].append(record)
                (sample_dir / "sample.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
                event("minute_sample", minute=minute, pid=pid, pssKiB=record["totalPssKiB"],
                      actualPresentNs=latest, progressed=model_progressed,
                      timeStatsNewIntervals=time_stats_sample.get("newlyPresentedIntervals") if time_stats_sample else None)
                if stalled >= 2:
                    raise SoakFailure("连续两个分钟区间未观察到 SurfaceView 实际呈现时间戳前进。")
                next_sample += 60
            if elapsed >= duration:
                summary["measuredSeconds"] = round(time.monotonic() - started, 3)
                summary["status"] = "inconclusive" if unavailable else "passed"
                break
            raw = instrument_log.read_text(errors="replace")
            heartbeat = status_records(raw, "atlas_soak_heartbeat")
            if elapsed > 35 and (not heartbeat or elapsed - float(heartbeat[-1].get("elapsedSeconds", 0)) > 35):
                raise SoakFailure("超过 35 秒未收到实际手势驱动心跳。")
            time.sleep(min(2, max(.1, duration - elapsed)))
    except (RuntimeError, OSError, subprocess.TimeoutExpired, KeyboardInterrupt, ValueError) as error:
        failure = str(error) or type(error).__name__
        summary["failure"] = failure
        summary["status"] = "failed"
        event("failure", message=failure)
    finally:
        # The Activity still owns its model surface here. Snapshot before the test rule closes it;
        # Capture.finish restores only a profiling switch that this capture enabled, even on error.
        if time_stats_capture is not None:
            try:
                summary["timeStats"] = time_stats_capture.finish(args.minutes * 60)
                measured = summary["timeStats"]["status"] == "measured"
                memory_complete = bool(summary["samples"]) and all(row["totalPssKiB"] is not None for row in summary["samples"])
                if measured and summary["status"] == "inconclusive" and memory_complete:
                    summary["status"] = "passed"
                if measured:
                    ts = summary["timeStats"]
                    summary["presentationMeasurement"] = {
                        "method": "SurfaceFlinger TimeStats presentation fences on the exact model SurfaceView",
                        "layerName": ts["exactLayerName"], "intervalCount": ts["intervalCount"],
                        "snapshotWindowSecondsBounds": ts["snapshotWindowSecondsBounds"],
                        "presentedIntervalsPerWallSecond": ts["presentedIntervalsPerWallSecond"],
                        "histogramEstimatedFps": ts["histogramEstimatedFps"],
                        "precision": "直方图FPS为整数毫秒分桶近似；帧间隔计数/主机单调时钟窗口为另一统计量，均非精确逐帧时间戳"}
            except (RuntimeError, OSError, subprocess.TimeoutExpired, ValueError) as error:
                summary["timeStats"] = {"status": "inconclusive", "reason": str(error)}
                if time_stats_capture.enabled_by_us:
                    summary["status"] = "failed"
                    summary["cleanupWarning"] = "TimeStats原关闭状态未确认恢复: " + str(error)
        if process is not None and process.poll() is None and device is not None:
            try:
                stopped = device.run("shell", "am broadcast -a " + STOP_ACTION + " -p " + PACKAGE, timeout=15, check=False)
                (logs / "stop-broadcast.txt").write_text(stopped.stdout + stopped.stderr)
                stop_sent = stopped.returncode == 0
                process.wait(timeout=40)
            except (OSError, subprocess.TimeoutExpired) as error:
                summary["cleanupWarning"] = "限定本应用的停止请求未确认完成；手势驱动有自身时限: " + str(error)
                # Do not force-stop, restart, reboot, or alter device state.
                summary["status"] = "failed"
        summary["stopBroadcastSent"] = stop_sent
        if instrument_stream is not None:
            instrument_stream.close()
        if process is not None:
            raw = instrument_log.read_text(errors="replace")
            complete = status_records(raw, "atlas_soak_complete")
            summary["driverComplete"] = complete[-1] if complete else None
            summary["instrumentationExitCode"] = process.poll()
            succeeded = process.poll() == 0 and re.search(r"OK \(1 test\)", raw) is not None
            summary["instrumentationPassed"] = succeeded
            if not succeeded:
                summary["status"] = "failed"
                summary.setdefault("failure", "仪器测试未返回 OK (1 test)，详见原始 instrumentation.txt。")
            if complete and (complete[-1].get("rotations", 0) < 1 or complete[-1].get("pinches", 0) < 1):
                summary["status"] = "failed"
                summary["failure"] = "没有完成实际旋转与双指缩放。"
            if device is not None and succeeded:
                try:
                    still_foreground, evidence = device.foreground()
                    summary["afterTestForeground"] = evidence
                    summary["activityClosedByRule"] = not still_foreground
                    if still_foreground:
                        summary["status"] = "failed"
                        summary["failure"] = "测试 rule 结束后本应用仍在前台，Activity 关闭未确认。"
                except (SoakFailure, subprocess.TimeoutExpired) as error:
                    summary["cleanupWarning"] = str(error)
                    summary["status"] = "failed"
        if frame_capture is not None:
            try:
                summary["frameTimeline"] = frame_capture.finish(app_pid=pid)
                measured = summary["frameTimeline"]["status"] == "measured"
                memory_complete = bool(summary["samples"]) and all(row["totalPssKiB"] is not None for row in summary["samples"])
                if measured and summary["status"] == "inconclusive" and memory_complete:
                    summary["status"] = "passed"
                if measured and "presentationMeasurement" not in summary:
                    summary["presentationMeasurement"] = "Perfetto FrameTimeline SurfaceView -> DisplayFrame actual present"
            except (RuntimeError, OSError, subprocess.TimeoutExpired, ValueError) as error:
                summary["frameTimeline"] = {"status": "inconclusive", "reason": str(error)}
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        event("finished", status=summary["status"], summary=str(output / "summary.json"))
        events.close()
    return {"passed": 0, "failed": 2, "inconclusive": 3}[summary["status"]]


if __name__ == "__main__":
    sys.exit(main())
