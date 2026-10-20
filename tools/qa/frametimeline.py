#!/usr/bin/env python3
"""Analyze real SurfaceView presentation events, never substitute the Compose window."""
from __future__ import annotations
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.zga.meridianatlas"
DEFAULT_PROCESSOR = ROOT / ".tools/perfetto/trace_processor_shell"
PRESENTED_TYPES = "('On-time Present','Late Present','Early Present')"


def config_text(seconds: int) -> str:
    return f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" target_buffer: 0 }} }}
duration_ms: {seconds * 1000}
flush_period_ms: 10000
'''


def query(processor: Path, trace: Path, sql: str, destination: Path) -> list[dict]:
    destination.with_suffix(".sql").write_text(sql + "\n")
    result = subprocess.run([str(processor), "query", str(trace), sql], capture_output=True, text=True, timeout=180)
    destination.write_text(result.stdout)
    destination.with_suffix(".stderr.txt").write_text(result.stderr)
    if result.returncode:
        raise RuntimeError("trace_processor query failed: " + result.stderr[-1500:])
    return list(csv.DictReader(io.StringIO(result.stdout)))


def presentation_summary(rows: list[dict], expected_seconds: float) -> dict:
    timestamps = sorted({int(row["present_ns"]) for row in rows if row.get("present_ns", "").isdigit()})
    if len(timestamps) < 2:
        return {"status": "inconclusive", "reason": "没有至少两条本应用SurfaceView关联的真实显示呈现记录", "presentedFrames": len(timestamps)}
    span = (timestamps[-1] - timestamps[0]) / 1e9
    intervals = sorted((b - a) / 1e6 for a, b in zip(timestamps, timestamps[1:]))
    def percentile(fraction):
        return intervals[round((len(intervals) - 1) * fraction)]
    by_second = {}
    origin = timestamps[0]
    for timestamp in timestamps:
        second = (timestamp - origin) // 1_000_000_000
        by_second[second] = by_second.get(second, 0) + 1
    enough = span >= max(1, expected_seconds - 3)
    return {"status": "measured" if enough else "inconclusive", "presentedFrames": len(timestamps),
            "firstActualPresentNs": timestamps[0], "lastActualPresentNs": timestamps[-1], "observedSpanSeconds": span,
            "presentFps": (len(timestamps) - 1) / span if span > 0 else None,
            "frameIntervalMs": {"p50": percentile(.5), "p90": percentile(.9), "p95": percentile(.95), "p99": percentile(.99), "max": max(intervals)},
            "perSecondPresentedFrames": [{"secondFromFirstPresent": index, "frames": by_second.get(index, 0)} for index in range(int(span) + 1)],
            "coverageSufficient": enough, "requestedSeconds": expected_seconds}


def analyze(trace: Path, output: Path, expected_seconds=600, processor=DEFAULT_PROCESSOR, pid: int | None = None) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    summary = {"status": "inconclusive", "trace": str(trace), "traceSha256": hashlib.sha256(trace.read_bytes()).hexdigest(),
               "processor": str(processor), "requestedSeconds": expected_seconds,
               "method": "SurfaceView actual surface frame -> display_frame_token -> SurfaceFlinger actual display slice ts+dur",
               "limits": ["只统计本包SurfaceView，Compose顶层窗口不能替代模型帧", "官方文档注明SurfaceViews可能没有FrameTimeline事件"]}
    try:
        if not processor.is_file():
            raise RuntimeError("缺少本地trace_processor；先运行 tools/qa/bootstrap_perfetto.py")
        summary["processorSha256"] = hashlib.sha256(processor.read_bytes()).hexdigest()
        summary["processorVersion"] = subprocess.run([str(processor), "--version"], capture_output=True, text=True, timeout=10).stdout.strip()
        pid_filter = "" if pid is None else f" AND p.pid = {int(pid)}"
        own = f"instr(COALESCE(a.layer_name,''),'{PACKAGE}') > 0"
        inventory = query(processor, trace, f"""SELECT a.layer_name, p.pid, p.name AS process_name, COUNT(*) AS frame_slices,
            MIN(a.ts) AS first_ns, MAX(a.ts+a.dur) AS last_end_ns
            FROM actual_frame_timeline_slice a LEFT JOIN process p USING(upid)
            WHERE {own}{pid_filter} GROUP BY a.layer_name,p.pid,p.name ORDER BY frame_slices DESC""", output / "own-app-layers.csv")
        summary["ownAppLayers"] = inventory
        # App slice end is GPU/post completion, NOT the actual on-screen present.
        # DisplayFrame's actual slice ends at present. Tokens connect the two.
        rows = query(processor, trace, f"""SELECT DISTINCT a.layer_name, a.display_frame_token,
            a.surface_frame_token, p.pid, a.present_type, a.jank_type, sf.ts+sf.dur AS present_ns
            FROM actual_frame_timeline_slice a LEFT JOIN process p ON p.upid=a.upid
            JOIN actual_frame_timeline_slice sf ON sf.display_frame_token=a.display_frame_token
              AND sf.surface_frame_token=0 AND sf.dur>0 AND sf.present_type IN {PRESENTED_TYPES}
            WHERE {own} AND lower(a.layer_name) LIKE '%surfaceview%'
              AND a.surface_frame_token>0 AND a.display_frame_token>0
              AND a.present_type IN {PRESENTED_TYPES}{pid_filter}
            ORDER BY present_ns,a.layer_name""", output / "surfaceview-presents.csv")
        summary.update(presentation_summary(rows, expected_seconds))
        summary["matchedLayerNames"] = sorted({row["layer_name"] for row in rows})
        stats = query(processor, trace, """SELECT name, value, severity FROM stats WHERE value>0 AND
            (name LIKE '%overwrit%' OR name LIKE '%drop%' OR severity='error') ORDER BY name""", output / "trace-data-quality.csv")
        summary["traceDataQuality"] = stats
        if any("overwrit" in row.get("name", "") or row.get("severity") == "error" for row in stats):
            summary["status"] = "inconclusive"
            summary["qualityWarning"] = "追踪存在覆盖或解析错误；不能认定完整十分钟呈现测量"
        summary["traceBounds"] = query(processor, trace, "SELECT start_ts,end_ts,(end_ts-start_ts)/1e9 AS span_seconds FROM trace_bounds", output / "trace-bounds.csv")
    except (OSError, RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
        summary["reason"] = str(error)
    (output / "frametimeline-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return summary


class Capture:
    """One bounded Perfetto session; only its verified perfetto PID may be stopped."""
    def __init__(self, device, logs: Path, output: Path, run_id: str, seconds: int):
        self.device = device; self.logs = logs; self.output = output
        self.seconds = seconds; self.pid = None; self.started = None
        safe_id = re.sub(r"[^A-Za-z0-9_-]", "-", run_id)
        self.remote_trace = "/data/misc/perfetto-traces/meridian-" + safe_id + ".perfetto-trace"
        self.local_trace = output / "model-frametimeline.perfetto-trace"

    def start(self):
        queried = self.device.run("shell", "perfetto --query", timeout=20)
        (self.logs / "perfetto-query.txt").write_text(queried.stdout + queried.stderr)
        if "android.surfaceflinger.frametimeline" not in queried.stdout:
            raise RuntimeError("设备未声明FrameTimeline数据源")
        local_config = self.logs / "frametimeline.pbtxt"
        local_config.write_text(config_text(self.seconds))
        # The perfetto SELinux domain cannot read /data/local/tmp on some devices.
        # Feed the documented stdin config directly; no device config file is needed.
        command = "perfetto --background-wait --txt -c - -o " + shlex.quote(self.remote_trace)
        result = subprocess.run(self.device.base + ["shell", command], env=self.device.env,
            input=local_config.read_text(), capture_output=True, text=True, timeout=40)
        (self.logs / "perfetto-start.txt").write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("Perfetto stdin config start failed: " + result.stderr.strip())
        pids = re.findall(r"(?m)^\s*(\d+)\s*$", result.stdout)
        if not pids:
            raise RuntimeError("无法从Perfetto启动输出确认本次会话PID；保留其自身时限")
        self.pid = int(pids[-1]); self.started = time.monotonic()
        return {"perfettoPid": self.pid, "durationSeconds": self.seconds, "remoteTrace": self.remote_trace}

    def finish(self, app_pid: int | None = None):
        if self.pid is None:
            return {"status": "inconclusive", "reason": "Perfetto未确认启动"}
        # Read the exact process command before sending TERM: no killall or global stop.
        command = self.device.shell("cat /proc/" + str(self.pid) + "/cmdline", check=False).replace("\x00", " ")
        if command.strip():
            if "perfetto" not in command or self.remote_trace not in command:
                return {"status": "inconclusive", "reason": "会话PID已被复用或身份不能核验；未发送停止信号"}
            stopped = self.device.run("shell", "kill -TERM " + str(self.pid), check=False)
            (self.logs / "perfetto-stop.txt").write_text(stopped.stdout + stopped.stderr)
            for _ in range(40):
                # Use the exit code; kill -0 has no stdout even on success.
                state = self.device.run("shell", "kill -0 " + str(self.pid) + " 2>/dev/null", check=False)
                if state.returncode != 0:
                    break
                time.sleep(.25)
        pulled = self.device.run("pull", self.remote_trace, str(self.local_trace), timeout=120)
        (self.logs / "perfetto-pull.txt").write_text(pulled.stdout + pulled.stderr)
        return analyze(self.local_trace, self.output / "frametimeline", self.seconds, pid=app_pid)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-seconds", type=float, default=600)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--trace-processor", type=Path, default=DEFAULT_PROCESSOR)
    args = parser.parse_args()
    result = analyze(args.trace.resolve(strict=True), args.output.resolve(), args.expected_seconds, args.trace_processor.resolve(), args.pid)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "measured" else 3


if __name__ == "__main__":
    raise SystemExit(main())
