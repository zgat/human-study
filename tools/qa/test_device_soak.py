"""Host-only parser checks. Importing this module never resolves or contacts a device."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("device_soak", Path(__file__).with_name("device_soak.py"))
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)


class EvidenceParserTest(unittest.TestCase):
    def test_surface_latency_uses_actual_not_desired_or_ready(self):
        raw = "16666666\n100 150 300\n200 250 400\n0 0 0\n300 9223372036854775807 500\n"
        result = soak.parse_latency(raw)
        self.assertEqual(16666666, result["refreshPeriodNs"])
        self.assertEqual(250, result["latestActualPresentNs"])
        self.assertEqual(2, result["validPresentCount"])

    def test_unsupported_or_empty_surface_data_is_not_a_successful_frame(self):
        self.assertIsNone(soak.parse_latency("Permission Denial: can't dump SurfaceFlinger\n")["latestActualPresentNs"])
        self.assertIsNone(soak.parse_latency("16666666\n0 0 0\n")["latestActualPresentNs"])

    def test_modern_requested_layer_state_extracts_only_actual_name(self):
        name = "SurfaceView[com.zga.meridianatlas/com.zga.meridianatlas.MainActivity](BLAST)#4644"
        raw = ("RequestedLayerState{a3a7a4a " + name + " parentId=4643 z=0}\n" + name +
               "\nRequestedLayerState{abc SurfaceView[other.app/MainActivity]#2 parentId=1}\n")
        self.assertEqual([name], soak.parse_surface_layers(raw))

    def test_memory_summary_and_legacy_total(self):
        self.assertEqual({"totalPssKiB": 123456, "totalRssKiB": 234567},
                         soak.parse_memory("  TOTAL PSS: 123456   TOTAL RSS: 234567\n"))
        self.assertEqual(43210, soak.parse_memory("    TOTAL   43210  2340  123\n")["totalPssKiB"])
        self.assertIsNone(soak.parse_memory("No process found")["totalPssKiB"])

    def test_status_only_accepts_valid_named_driver_records(self):
        raw = ('INSTRUMENTATION_STATUS: atlas_soak_ready={"pid":123,"package":"com.zga.meridianatlas"}\n'
               'INSTRUMENTATION_STATUS: atlas_soak_ready=malformed\n'
               'INSTRUMENTATION_STATUS: atlas_soak_heartbeat={"pinches":3}\n')
        records = soak.status_records(raw, "atlas_soak_ready")
        self.assertEqual(1, len(records))
        self.assertEqual(123, records[0]["pid"])


if __name__ == "__main__":
    unittest.main()
