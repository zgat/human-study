"""Privacy and evidence-boundary tests using synthetic temporary projects only."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "export_public_evidence.py"
SPEC = importlib.util.spec_from_file_location("public_evidence_export", SCRIPT)
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)


class PublicEvidenceTest(unittest.TestCase):
    def write(self, root, name, value):
        path = root / "manifests" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def fixture(self, root):
        for name in evidence.PUBLIC_MANIFESTS:
            self.write(root, name, {})
        def artifact(name, content):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            return {"path": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
        standard = artifact("app/src/main/assets/models/body-standard.glb", b"standard fixture")
        low = artifact("app/src/main/assets/models/body-low.glb", b"low fixture")
        anatomy = artifact("app/src/main/assets/anatomy.json", b"{}")
        fascia = artifact("app/src/main/assets/content/fascia.json", b"{}")
        self.write(root, "model-validation.json", {"status": "passed", "models": [standard, low]})
        self.write(root, "model-build.json", {"anatomyMetadata": anatomy,
            "countsBySystem": {"muscle": 499, "bone": 285, "organ": 382, "connective": 59, "space": 13, "skin": 1}})
        self.write(root, "content-validation.json", {"checksPassed": True, "entries": 413,
            "meridianEntries": 362, "extraEntries": 51, "instances": 843, "routes": 20, "medicalReviewedInstances": 0})
        self.write(root, "content-functions.json", {"professionalReviewedEntries": 0})
        self.write(root, "fascia-content.json", {"connections": 3, "instances": 6, "targets": 20,
            "professionalAnatomicalReviewedTargets": 0, "clinicalTargets": 0,
            "inputs": [standard, low, anatomy], "outputs": [fascia]})
        self.write(root, "model-connective-supplement.json", {"addedMeshes": [{"id": str(i), "tissueType": "fascia"} for i in range(8)]})
        self.write(root, "fascia-validation.json", {"applicationVersion": "0.2.2",
            "jvm": [{"name": "fixture", "tests": 42, "failures": 0, "errors": 0}], "lint": {"errors": 0, "warnings": 18}})
        self.write(root, "fascia-functional-device.json", {"applicationVersion": "0.2.2", "status": "passed",
            "testsDiscovered": 22, "passed": 21, "assumptionSkipped": 1, "failed": 0,
            "serial": "SYNTHETIC-DEVICE-ID", "installedPath": "/data/app/synthetic/base.apk"})
        self.write(root, "release-device.json", {"serial": "DO-NOT-PUBLISH", "pid": 12345})
        self.write(root, "source-package-v0.2.1.json", {"private": "historical"})
        (root / "app/build.gradle.kts").write_text('android { defaultConfig { versionName = "0.3.0" } }')
        return standard, low, anatomy

    def test_recursive_privacy_filter_preserves_scientific_ids_and_relative_inputs(self):
        with tempfile.TemporaryDirectory(prefix="public-evidence-") as directory:
            root = Path(directory) / "project with spaces"
            source = {
                "input": {"path": str(root / "app/src/main/assets/anatomy.json"), "sha256": "a" * 64},
                "compiler": {"path": "/Users/example/Library/runtime/bin/matc"},
                "nested": [{"serial": "SYNTHETIC-DEVICE-ID", "last_transport": "adb-synthetic._adb-tls-connect._tcp",
                            "pid": 12345, "uid": 54321, "installedPath": "/data/app/example/base.apk"}],
                "description": "SYNTHETIC-DEVICE-ID at 192.168.9.8:12345 PID 12345",
                "command": "run --cache /private/tmp/anonymous/cache",
                "localScript": "/data/local/tmp/fixture.apk",
                "sourceUrl": "https://example.org/paper?id=123&token=synthetic-secret",
                "authorization": "synthetic-secret",
                "sourceConceptId": "FMA7163", "sourceElementId": "FJ2810",
                "unlabelledCredential": "gh" + "p_" + "x" * 30,
            }
            clean = evidence.sanitize(source, root)
            evidence.assert_public(clean)
            text = json.dumps(clean)
            for private in ["SYNTHETIC-DEVICE-ID", "192.168.", "/Users/", "/private/tmp/", "/data/local/", "synthetic-secret"]:
                self.assertNotIn(private, text)
            self.assertEqual("app/src/main/assets/anatomy.json", clean["input"]["path"])
            self.assertEqual("a" * 64, clean["input"]["sha256"])
            self.assertEqual("https://example.org/paper?id=123", clean["sourceUrl"])
            self.assertEqual("FMA7163", clean["sourceConceptId"])
            self.assertEqual("FJ2810", clean["sourceElementId"])
            self.assertEqual([{}], clean["nested"])

    def test_allowlist_export_keeps_private_originals_and_marks_old_tests_as_historical(self):
        with tempfile.TemporaryDirectory(prefix="public-evidence-") as directory:
            root = Path(directory)
            self.fixture(root)
            originals = {p.name: p.read_bytes() for p in (root / "manifests").glob("*.json")}
            report = evidence.export(root, "0.3.0")
            output = root / "docs/validation"
            names = {entry["path"] for entry in report["files"]}
            self.assertEqual(set(evidence.PUBLIC_MANIFESTS) | {"test-summary.json"}, names)
            self.assertFalse(any("device" in name or "performance" in name or "-v0." in name or "source-package" in name for name in names))
            self.assertEqual(originals, {p.name: p.read_bytes() for p in (root / "manifests").glob("*.json")})
            summary = json.loads((output / "test-summary.json").read_text())
            self.assertEqual("0.3.0", summary["targetApplicationVersion"])
            self.assertEqual("0.2.2", summary["evidenceApplicationVersion"])
            self.assertFalse(summary["targetVersionFullSuiteExecuted"])
            self.assertEqual(42, summary["historicalRegression"]["unit"]["passed"])
            self.assertEqual(21, summary["historicalRegression"]["device"]["passed"])
            self.assertEqual(1, summary["historicalRegression"]["device"]["assumptionSkipped"])
            self.assertEqual(8, summary["currentAssetFacts"]["addedFasciaAndAponeurosisMeshes"])
            self.assertEqual(20, summary["currentAssetFacts"]["fascialReferenceRegions"])
            self.assertEqual(0, summary["professionalReview"]["clinicalTargets"])
            self.assertEqual(0, summary["professionalReview"]["acupointInstancesCompleted"])
            for path in output.glob("*.json"):
                evidence.assert_public(json.loads(path.read_text()))

    def test_build_gate_rejects_old_versions_and_keeps_current_unit_results_separate(self):
        with tempfile.TemporaryDirectory(prefix="public-evidence-") as directory:
            root = Path(directory)
            self.fixture(root)
            apk = root / "outputs/app.apk"
            apk.parent.mkdir()
            apk.write_bytes(b"synthetic APK")
            artifact = {"path": "outputs/app.apk", "bytes": apk.stat().st_size,
                        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
            build = {"applicationVersion": "0.2.2", "artifacts": [artifact],
                     "files": [{"path": str(root / "app/build.gradle.kts"), "sha256": "b" * 64}],
                     "javaVersion": "/Users/example/private-java/bin/java"}
            delivery = {"apk": {**artifact, "versionName": "0.2.2", "package": "example.app"},
                        "clinicalReviewCompleted": 0}
            self.write(root, "build-inputs.json", build)
            self.write(root, "delivery.json", delivery)
            with self.assertRaisesRegex(ValueError, "version is stale"):
                evidence.export(root, "0.3.0", include_build=True)
            self.assertFalse((root / "docs/validation").exists())
            build["applicationVersion"] = "0.3.0"
            delivery["apk"]["versionName"] = "0.3.0"
            self.write(root, "build-inputs.json", build)
            self.write(root, "delivery.json", delivery)
            reports = root / "app/build/test-results/testDebugUnitTest"
            reports.mkdir(parents=True)
            (reports / "TEST-fixture.xml").write_text('<testsuite name="current" tests="42" failures="0" errors="0" skipped="0"/>')
            evidence.export(root, "0.3.0", include_build=True, include_current_units=True)
            summary = json.loads((root / "docs/validation/test-summary.json").read_text())
            self.assertEqual("0.3.0", summary["currentUnitTests"]["applicationVersion"])
            self.assertEqual(42, summary["currentUnitTests"]["passed"])
            self.assertEqual("0.2.2", summary["evidenceApplicationVersion"])
            self.assertFalse(summary["targetVersionFullSuiteExecuted"])
            public_build = json.loads((root / "docs/validation/build-evidence.json").read_text())
            self.assertEqual("app/build.gradle.kts", public_build["inputFiles"][0]["path"])
            self.assertNotIn("javaVersion", public_build)
            apk.write_bytes(b"changed APK")
            with self.assertRaisesRegex(ValueError, "SHA-256 changed"):
                evidence.export(root, "0.3.0", include_build=True)


if __name__ == "__main__":
    unittest.main()
