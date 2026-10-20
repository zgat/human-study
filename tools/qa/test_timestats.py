"""Host-only parser, evidence-boundary and profiling-state restoration checks."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("atlas_timestats", Path(__file__).with_name("timestats.py"))
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)
LAYER = "89dcb50 SurfaceView[com.zga.meridianatlas/com.zga.meridianatlas.MainActivity](BLAST)#5456"


def block(count=3, histogram="16ms=3", layer=LAYER, uid=10373, refresh=120):
    return (f"displayRefreshRate = {refresh} fps\nrenderRate = 120 fps\nuid = {uid}\n"
            f"layerName = {layer}\npackageName = \ngameMode = Unsupported\ntotalFrames = {count}\n"
            "droppedFrames = 0\naverageFPS = 62.500\npresent2present histogram is as below:\n" + histogram + "\n")


def dump(count=3, histogram="16ms=3", extra="", start=100):
    return (f"SurfaceFlinger TimeStats:\nstatsStart = {start}\nstatsEnd = 200\ntotalFrames = 9999\n"
            + block(count, histogram) + extra)


FULL = (f"  Layer [5456] {LAYER}\n    ownerPid=23000\n"
        "TimeStats miniDump:\nNumber of layers currently being tracked is 0\n"
        "Number of layers in the stats pool is 0\n")


class Device:
    def __init__(self, dumps):
        self.dumps = iter(dumps)
        self.commands = []

    def shell(self, command, **kwargs):
        self.commands.append(command)
        if command == "dumpsys SurfaceFlinger":
            return FULL
        if command.startswith("cmd package"):
            return "package:com.zga.meridianatlas uid:10373\n"
        if command.endswith("-dump"):
            value = next(self.dumps)
            if isinstance(value, Exception):
                raise value
            return value
        return ""


class TimeStatsEvidenceTest(unittest.TestCase):
    def test_only_real_package_blast_surfaceview_is_saved(self):
        extra = (block(layer="com.zga.meridianatlas/MainActivity#2") +
                 block(layer="Background for " + LAYER) +
                 block(layer=LAYER.replace("com.zga.meridianatlas", "other.app")))
        parsed = stats.parse_dump(dump(extra=extra))
        self.assertEqual([LAYER], [x["layerName"] for x in parsed["layers"]])
        self.assertEqual(9999, parsed["global"]["totalFrames"])
        self.assertEqual(3, parsed["layers"][0]["totalFrames"])

    def test_actual_layer_requires_matching_owner_and_rejects_container(self):
        name, mini = stats.model_layer_from_dump(FULL, 23000)
        self.assertEqual(LAYER, name)
        self.assertIn("tracked is 0", mini)
        with self.assertRaises(RuntimeError):
            stats.model_layer_from_dump(FULL, 99)

    def test_differences_counters_not_cumulative_average_or_global_frames(self):
        a = stats.parse_dump(dump(3, "16ms=3"))
        b = stats.parse_dump(dump(8, "16ms=6 33ms=2"))
        result = stats.interval_delta(a, b, LAYER, 10373)
        self.assertEqual(5, result["intervalCount"])
        self.assertEqual({"16": 3, "33": 2}, result["present2presentHistogram"])
        self.assertAlmostEqual(5000 / 114, result["histogramEstimatedFps"])
        self.assertEqual(33, result["intervalBucketMs"]["p99"])

    def test_new_refresh_bucket_is_added_without_double_counting(self):
        a = stats.parse_dump(dump())
        b = stats.parse_dump(dump(extra=block(2, "33ms=2", refresh=60)))
        self.assertEqual(2, stats.interval_delta(a, b, LAYER, 10373)["intervalCount"])

    def test_counter_histogram_and_global_reset_are_rejected(self):
        a = stats.parse_dump(dump(3, "16ms=3"))
        for b in [dump(2, "16ms=2"), dump(4, "16ms=2 33ms=2"), dump(4, "16ms=4", start=101)]:
            with self.subTest(b=b), self.assertRaises(ValueError):
                stats.interval_delta(a, stats.parse_dump(b), LAYER, 10373)

    def test_changed_uid_missing_surface_and_mismatched_histogram_are_rejected(self):
        a = stats.parse_dump(dump())
        for raw in [dump().replace("uid = 10373", "uid = 9"), "", dump(4, "16ms=3")]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                stats.interval_delta(a, stats.parse_dump(raw), LAYER, 10373)

    @patch.object(stats.time, "sleep", return_value=None)
    def test_disabled_session_retains_prior_counts_warms_up_and_restores(self, _):
        device = Device([dump(606, "16ms=606"), dump(610, "16ms=609 1000ms=1", start=300),
                         dump(615, "16ms=614 1000ms=1", start=300)])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = stats.Capture(device, root / "logs", root / "out", original_enabled=False)
            with patch.object(stats.time, "monotonic_ns", side_effect=[0, 1_000_000, 1_000_000_000,
                              1_001_000_000, 3_000_000_000, 3_001_000_000]):
                capture.start(23000)
                result = capture.finish(2)
            self.assertEqual("measured", result["status"])
            self.assertEqual(5, result["intervalCount"])
            self.assertEqual({"16": 5}, result["present2presentHistogram"])
            self.assertEqual(2.5, result["presentedIntervalsPerWallSecond"])
            self.assertEqual(1, sum(x.endswith("-enable") for x in device.commands))
            self.assertEqual(1, sum(x.endswith("-disable") for x in device.commands))
            self.assertFalse(any("-clear" in x for x in device.commands))

    def test_original_enabled_session_is_never_toggled(self):
        device = Device([dump(), dump(4, "16ms=4"), dump(8, "16ms=8")])
        with tempfile.TemporaryDirectory() as directory:
            capture = stats.Capture(device, Path(directory), Path(directory), original_enabled=True)
            capture.start(23000)
            result = capture.finish(600)
            self.assertEqual("inconclusive", result["status"])
            self.assertFalse(result["coverageSufficient"])
        self.assertFalse(any(x.endswith(("-enable", "-disable")) for x in device.commands))

    def test_nonempty_dump_never_infers_enabled_state(self):
        device = Device([dump()])
        with tempfile.TemporaryDirectory() as directory:
            capture = stats.Capture(device, Path(directory), Path(directory))
            with self.assertRaises(RuntimeError):
                capture.start(23000)
        self.assertFalse(any(x.endswith(("-enable", "-disable")) for x in device.commands))

    @patch.object(stats.time, "sleep", return_value=None)
    def test_failure_after_enable_still_restores_without_clear(self, _):
        device = Device(["", RuntimeError("read failure")])
        with tempfile.TemporaryDirectory() as directory:
            capture = stats.Capture(device, Path(directory), Path(directory))
            with self.assertRaises(RuntimeError):
                capture.start(23000)
        self.assertTrue(device.commands[-1].endswith("-disable"))
        self.assertFalse(any("-clear" in x for x in device.commands))


if __name__ == "__main__":
    unittest.main()
