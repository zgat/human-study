#!/usr/bin/env python3
"""SurfaceFlinger presentation-fence statistics for one verified model SurfaceView.

This collector never clears statistics. A nonempty dump does not expose the enabled
flag, so callers must supply its known original state (or start from an empty dump).
Only this package's SurfaceView records and necessary global fields reach disk.
"""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import re
import time

PACKAGE = "com.zga.meridianatlas"
SOURCE_COMMIT = "2827a4a16b0340ecd07c2d5a6c89991799b362bb"


def is_model_layer(name: str, package: str = PACKAGE) -> bool:
    return (not name.startswith("Background for ") and
            bool(re.search(r"SurfaceView\[" + re.escape(package) + r"/[^\]]+\]", name)) and
            bool(re.search(r"\(BLAST\)#\d+$", name)))


def model_layer_from_dump(raw: str, app_pid: int, package: str = PACKAGE) -> tuple[str, str]:
    """Use the actual composed buffer layer, with its owner PID; reject container layers."""
    starts = list(re.finditer(r"(?m)^\s{2}Layer \[\d+\] (.+)$", raw))
    names = []
    for index, match in enumerate(starts):
        name = match.group(1).strip()
        body = raw[match.end():starts[index + 1].start() if index + 1 < len(starts) else len(raw)]
        owner = re.search(r"\bownerPid=(\d+)\b", body)
        if is_model_layer(name, package) and owner and int(owner.group(1)) == app_pid:
            names.append(name)
    names = sorted(set(names))
    if len(names) != 1:
        raise RuntimeError(f"不能唯一核验 ownerPid={app_pid} 的模型 BLAST SurfaceView: {names}")
    mini = re.search(r"TimeStats miniDump:\n(?:Number of layers[^\n]*\n){2}", raw)
    return names[0], mini.group(0) if mini else "TimeStats miniDump not available\n"


def parse_dump(raw: str, package: str = PACKAGE) -> dict:
    result = {"global": {}, "layers": []}
    parts = re.split(r"(?=^displayRefreshRate = )", raw, flags=re.MULTILINE)
    for line in parts[0].splitlines():
        match = re.fullmatch(r"(statsStart|statsEnd|totalFrames|displayOnTime) = (\d+)(?: ms)?", line)
        if match:
            result["global"][match.group(1)] = int(match.group(2))
    for block in parts[1:]:
        fields = {}
        histograms = {}
        histogram = None
        for line in block.splitlines():
            hist = re.fullmatch(r"(\w+) histogram is as below:", line)
            if hist:
                histogram = hist.group(1)
                histograms[histogram] = {}
                continue
            buckets = re.findall(r"(\d+)ms=(\d+)", line)
            if buckets and histogram:
                histograms[histogram].update({int(ms): int(count) for ms, count in buckets})
                continue
            if " = " in line:
                key, value = line.split(" = ", 1)
                # The first totalFrames belongs to this layer, not its jank payload.
                if key in {"layerName", "uid", "displayRefreshRate", "renderRate", "gameMode",
                           "totalFrames", "droppedFrames", "averageFPS"} and key not in fields:
                    fields[key] = value
        if not is_model_layer(fields.get("layerName", ""), package):
            continue
        for key in ("uid", "displayRefreshRate", "renderRate", "totalFrames", "droppedFrames"):
            if key in fields:
                fields[key] = int(fields[key].split()[0])
        if "averageFPS" in fields:
            fields["averageFPS"] = float(fields["averageFPS"])
        fields["histograms"] = histograms
        result["layers"].append(fields)
    return result


def layer_key(layer: dict) -> tuple:
    return tuple(layer.get(key) for key in ("layerName", "uid", "displayRefreshRate", "renderRate", "gameMode"))


def interval_delta(before: dict, after: dict, layer_name: str, uid: int) -> dict:
    """Subtract cumulative buckets; no averages are ever subtracted or treated as exact FPS."""
    def own(snapshot):
        return {layer_key(layer): layer for layer in snapshot["layers"]
                if layer.get("layerName") == layer_name and layer.get("uid") == uid}
    old, new = own(before), own(after)
    if not new:
        raise ValueError("精确模型层的 TimeStats 记录消失或 UID 改变")
    if old.keys() - new.keys():
        raise ValueError("模型统计分桶消失，可能被系统拉取清零或层已重建")
    if (before["global"].get("statsStart") is not None and
            before["global"].get("statsStart") != after["global"].get("statsStart")):
        raise ValueError("TimeStats 起始时间变化，不能合并统计窗口")
    histogram = {}
    frames = dropped = 0
    for key, current in new.items():
        previous = old.get(key, {})
        count = current.get("totalFrames", 0) - previous.get("totalFrames", 0)
        drop = current.get("droppedFrames", 0) - previous.get("droppedFrames", 0)
        if count < 0 or drop < 0:
            raise ValueError("累计帧计数倒退，统计窗口无效")
        a = previous.get("histograms", {}).get("present2present", {})
        b = current.get("histograms", {}).get("present2present", {})
        delta = {bucket: b.get(bucket, 0) - a.get(bucket, 0) for bucket in a.keys() | b.keys()}
        if any(value < 0 for value in delta.values()):
            raise ValueError("present2present 累计桶倒退，可能被系统拉取清零")
        if sum(delta.values()) != count:
            raise ValueError("模型 totalFrames 与呈现间隔直方图数量不符")
        for bucket, value in delta.items():
            histogram[bucket] = histogram.get(bucket, 0) + value
        frames += count
        dropped += drop
    weighted_ms = sum(bucket * count for bucket, count in histogram.items())
    def percentile(fraction):
        needed, cumulative = max(1, int(frames * fraction + .999999)), 0
        for bucket in sorted(histogram):
            cumulative += histogram[bucket]
            if cumulative >= needed:
                return bucket
        return None
    return {"intervalCount": frames, "droppedFrames": dropped,
            "present2presentHistogram": {str(k): v for k, v in sorted(histogram.items()) if v},
            "histogramEstimatedFps": frames * 1000 / weighted_ms if weighted_ms > 0 else None,
            "intervalBucketMs": {"p50": percentile(.5), "p90": percentile(.9),
                                 "p95": percentile(.95), "p99": percentile(.99),
                                 "max": max((k for k, v in histogram.items() if v), default=None)}}


class Capture:
    def __init__(self, device, logs: Path, output: Path, original_enabled: bool | None = None):
        self.device = device
        self.logs = Path(logs) / "timestats"
        self.output = Path(output) / "timestats"
        self.original_enabled = original_enabled
        self.enabled_by_us = False
        self.finished = False
        self.started = False
        self.baseline = self.previous = None
        self.invalid_reason = None
        self.sequence = 0
        self.control = {"clearIssued": False, "sourceCommit": SOURCE_COMMIT}

    def _command(self, suffix: str) -> str:
        return self.device.shell("dumpsys SurfaceFlinger --timestats " + suffix, timeout=25)

    def _save_control(self):
        (self.logs / "control.json").write_text(json.dumps(self.control, ensure_ascii=False, indent=2) + "\n")

    def _read(self, label: str) -> dict:
        start = time.monotonic_ns()
        raw = self._command("-dump")
        end = time.monotonic_ns()
        parsed = parse_dump(raw)
        # Exact-name scoping also avoids saving a second SurfaceView belonging to this app.
        if hasattr(self, "layer_name"):
            parsed["layers"] = [x for x in parsed["layers"] if x["layerName"] == self.layer_name]
        parsed.update({"readStartedMonotonicNs": start, "readCompletedMonotonicNs": end,
                       "wallTime": datetime.now().astimezone().isoformat(), "rawEmpty": not raw.strip()})
        (self.logs / (label + ".json")).write_text(json.dumps(parsed, ensure_ascii=False, indent=2) + "\n")
        return parsed

    def start(self, app_pid: int) -> dict:
        if self.started or self.finished:
            raise RuntimeError("TimeStats Capture 不能重复启动")
        self.logs.mkdir(parents=True, exist_ok=True)
        self.output.mkdir(parents=True, exist_ok=True)
        self.app_pid = int(app_pid)
        full = self.device.shell("dumpsys SurfaceFlinger", timeout=25)
        self.layer_name, mini = model_layer_from_dump(full, self.app_pid)
        (self.logs / "before-minidump.txt").write_text(mini)
        packages = self.device.shell("cmd package list packages -U " + PACKAGE, timeout=10)
        match = re.search(r"(?m)^package:" + re.escape(PACKAGE) + r" uid:(\d+)\s*$", packages)
        if not match:
            raise RuntimeError("无法核验本包 UID")
        self.uid = int(match.group(1))
        before = self._read("before")
        if self.original_enabled is None:
            if not before["rawEmpty"]:
                raise RuntimeError("非空 dump 不显示 enabled 状态；请显式指定已核验的原开关，不能猜测后 disable")
            self.original_enabled = False
            basis = "empty_dump_matches_AOSP_statsStart_zero"
        else:
            basis = "explicit_known_original_state"
        self.control.update({"originalEnabled": self.original_enabled, "originalStateEvidence": basis,
                             "appPid": self.app_pid, "uid": self.uid, "exactLayerName": self.layer_name})
        self._save_control()
        try:
            if not self.original_enabled:
                # Set ownership first: if ADB times out after enabling, cleanup still restores it.
                self.enabled_by_us = True
                self._command("-enable")
                self.control["temporaryProfilingEnabled"] = True
                # Discard at least two new presents, including a possible interval across the
                # disabled period. Existing cumulative records are retained unchanged.
                old_count = sum(x.get("totalFrames", 0) for x in before["layers"] if x.get("uid") == self.uid)
                deadline = time.monotonic() + 3
                while True:
                    time.sleep(.1)
                    baseline = self._read("warmup")
                    count = sum(x.get("totalFrames", 0) for x in baseline["layers"] if x.get("uid") == self.uid)
                    if count >= old_count + 2:
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError("暂启后未得到至少两条新的模型呈现间隔，未建立采样基线")
            else:
                baseline = self._read("baseline")
            if not any(x.get("uid") == self.uid and x.get("histograms", {}).get("present2present")
                       for x in baseline["layers"]):
                raise RuntimeError("模型层没有可用的 present2present 基线")
            self.baseline = self.previous = baseline
            (self.logs / "baseline.json").write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + "\n")
            self.started = True
            self._save_control()
            return {"exactLayerName": self.layer_name, "appPid": self.app_pid, "uid": self.uid,
                    "originalEnabled": self.original_enabled, "temporaryProfiling": self.enabled_by_us,
                    "baselineMonotonicNs": (baseline["readStartedMonotonicNs"] + baseline["readCompletedMonotonicNs"]) // 2}
        except BaseException:
            self.restore()
            raise

    def sample(self) -> dict:
        if not self.started or self.finished:
            raise RuntimeError("TimeStats 尚未启动或已经结束")
        self.sequence += 1
        current = self._read(f"sample-{self.sequence:03d}")
        try:
            recent = interval_delta(self.previous, current, self.layer_name, self.uid)
            total = interval_delta(self.baseline, current, self.layer_name, self.uid)
            if self.invalid_reason:
                raise ValueError(self.invalid_reason)
            a, b = self.baseline, current
            lower = (b["readStartedMonotonicNs"] - a["readCompletedMonotonicNs"]) / 1e9
            upper = (b["readCompletedMonotonicNs"] - a["readStartedMonotonicNs"]) / 1e9
            elapsed = (lower + upper) / 2
            result = {"status": "measured" if total["intervalCount"] and total["histogramEstimatedFps"] else "inconclusive",
                      **total, "newlyPresentedIntervals": recent["intervalCount"], "elapsedSeconds": elapsed,
                      "snapshotWindowSecondsBounds": [max(0, lower), upper],
                      "presentedIntervalsPerWallSecond": total["intervalCount"] / elapsed if elapsed > 0 else None}
        except ValueError as error:
            self.invalid_reason = str(error)
            result = {"status": "inconclusive", "reason": str(error)}
        self.previous = current
        return result

    def restore(self):
        if self.enabled_by_us:
            self._command("-disable")
            self.enabled_by_us = False
            self.control["restoredOriginalDisabled"] = True
            self._save_control()

    def finish(self, expected_seconds: float = 600) -> dict:
        if self.finished:
            return self.result
        result = {"status": "inconclusive", "reason": "未建立 TimeStats 基线"}
        try:
            if self.started:
                result = self.sample()
                sufficient = result.get("snapshotWindowSecondsBounds", [0])[0] >= max(1, expected_seconds - 3)
                result["coverageSufficient"] = sufficient
                if not sufficient:
                    result["status"] = "inconclusive"
                    result.setdefault("reason", "单调时钟窗口不足指定耐久时长")
        finally:
            try:
                self.restore()
            finally:
                self.finished = True
        result.update({"exactLayerName": getattr(self, "layer_name", None), "uid": getattr(self, "uid", None),
                       "requestedSeconds": expected_seconds, "source": "SurfaceFlinger per-layer presentation fences",
                       "limits": ["totalFrames 在 AOSP 为完成的相邻呈现间隔数，初始一帧用于建前值",
                                  "直方图先截断为整数毫秒再分桶；histogramEstimatedFps 和百分位桶均为近似",
                                  "presentedIntervalsPerWallSecond 使用主机单调时钟快照窗口，包含边界和ADB读取时差",
                                  "帧计数/桶倒退或桶消失判为无效；两次采样之间清零又追平旧计数不能完全排除",
                                  "TimeStats 为系统全局临时profiling，会增加统计与锁开销；从未clear"]})
        self.result = result
        (self.output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        return result
