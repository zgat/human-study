"""Host-only evidence and coverage checks; no device or tracing session is opened."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("frametimeline", Path(__file__).with_name("frametimeline.py"))
frames = importlib.util.module_from_spec(spec)
spec.loader.exec_module(frames)


class PresentationEvidenceTest(unittest.TestCase):
    def test_distinct_actual_presents_do_not_double_count_layers(self):
        rows = [{"present_ns": str(value)} for value in [1000000000, 2000000000, 2000000000, 3000000000]]
        result = frames.presentation_summary(rows, 2)
        self.assertEqual("measured", result["status"])
        self.assertEqual(3, result["presentedFrames"])
        self.assertEqual(1.0, result["presentFps"])
        self.assertEqual(1000.0, result["frameIntervalMs"]["p99"])

    def test_short_trace_never_claims_ten_minute_coverage(self):
        result = frames.presentation_summary([{"present_ns": "1000000000"}, {"present_ns": "2000000000"}], 600)
        self.assertEqual("inconclusive", result["status"])
        self.assertFalse(result["coverageSufficient"])

    def test_no_surfaceview_data_stays_inconclusive(self):
        self.assertEqual("inconclusive", frames.presentation_summary([], 600)["status"])
        self.assertNotIn("presentFps", frames.presentation_summary([], 600))

    def test_config_is_bounded_and_has_only_the_frame_source(self):
        config = frames.config_text(600)
        self.assertIn("duration_ms: 600000", config)
        self.assertIn("size_kb: 32768", config)
        self.assertIn("RING_BUFFER", config)
        self.assertEqual(1, config.count("data_sources"))
        self.assertIn("android.surfaceflinger.frametimeline", config)

    @unittest.skipUnless(frames.DEFAULT_PROCESSOR.is_file(), "official project-local trace_processor not bootstrapped")
    def test_real_processor_accepts_sql_and_empty_trace_does_not_invent_frames(self):
        with tempfile.TemporaryDirectory(prefix="frametimeline-host-fixture-") as directory:
            root = Path(directory)
            trace = root / "empty.perfetto-trace"
            trace.write_bytes(b"")
            result = frames.analyze(trace, root / "analysis", 600)
            self.assertEqual("inconclusive", result["status"])
            self.assertEqual(0, result["presentedFrames"])
            self.assertEqual([], result["ownAppLayers"])


if __name__ == "__main__":
    unittest.main()
