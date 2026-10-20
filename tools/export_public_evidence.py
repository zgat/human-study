#!/usr/bin/env python3
"""Export portable public evidence; keep complete local manifests and device records private.

Run after final asset validation. --include-build additionally requires current-version,
hash-matched build/delivery manifests. This is evidence export, never a build or device test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_MANIFESTS = (
    "content-anchor-audit.json", "content-artifacts.json", "content-functions.json",
    "content-sources.json", "content-validation.json", "cutaway-material.json",
    "fascia-content.json", "meridian-palette.json", "model-build.json",
    "model-connective-supplement.json", "model-landmarks.json", "model-muscle-content.json",
    "model-muscle-fix.json", "model-muscle-simplification.json", "model-muscle-topology.json",
    "model-skin-occlusion.json", "model-supplement.json", "model-taxonomy.json",
    "model-trapezius-fix.json", "model-validation.json", "route-rendering.json",
)
GENERATED_FILES = {"test-summary.json", "build-evidence.json", "delivery-evidence.json"}
PRIVATE_KEYS = {
    "serial", "serialnumber", "deviceserial", "deviceid", "deviceidentity", "androidid",
    "transport", "lasttransport", "transportid", "service", "servicename", "mdns",
    "pid", "apppid", "ownerpid", "samepid", "pidbefore", "pidafter", "uid", "appuid",
    "installedpath", "foreground", "window", "hostname", "hostid", "address", "ip", "ipaddress",
    "password", "passwd", "token", "accesstoken", "apitoken", "apikey", "secret", "clientsecret",
    "privatekey", "authorization", "credentials", "pairingcode", "pairingkey",
}
LOCAL_PATH = re.compile(r"(?<![A-Za-z0-9:/])/(?:Users|Volumes|private/tmp|private/var|var|tmp|home|data/app|data/local)(?:/[^\n\r\"'<>]*)?")
PRIVATE_IP = re.compile(r"(?<![\d])(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(?::\d+)?")
ADB_SERVICE = re.compile(r"\badb-[A-Za-z0-9_-]+\._adb[^\s\"'<>]*", re.IGNORECASE)
PROCESS_EVIDENCE = re.compile(r"\b(?:PID|UID)\s*[:=]?\s*\d+\b", re.IGNORECASE)
CREDENTIAL = re.compile(
    r"(?:-----BEGIN (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----|"
    r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b|"
    r"\bAKIA[A-Z0-9]{16}\b|\bsk-[A-Za-z0-9_-]{24,}\b|"
    r"Authorization\s*:\s*Bearer\s+[A-Za-z0-9._~-]{20,})", re.IGNORECASE)


def normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def private_key(key: str) -> bool:
    value = normalized_key(key)
    return value in PRIVATE_KEYS or value.endswith(("serialnumber", "devicepid", "transportid"))


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(root: Path, name: str) -> dict:
    return json.loads((root / "manifests" / name).read_text(encoding="utf-8"))


def sanitize(value: Any, root: Path) -> Any:
    """Preserve scientific IDs/hashes while removing device identity and local execution paths."""
    root_paths = {str(root.absolute()), str(root.resolve())}
    private_values = set()

    def collect(item: Any) -> None:
        if isinstance(item, dict):
            for key, child in item.items():
                if private_key(key) and isinstance(child, str) and len(child) >= 4:
                    private_values.add(child)
                collect(child)
        elif isinstance(item, list):
            for child in item:
                collect(child)

    collect(value)

    def clean_text(text: str) -> str:
        for secret in sorted(private_values, key=len, reverse=True):
            text = text.replace(secret, "[private-value]")
        # Handle project paths before general absolute paths, including volume names with spaces.
        for root_text in sorted(root_paths, key=len, reverse=True):
            text = text.replace(root_text + "/", "").replace(root_text, ".")
        if text.startswith(("https://", "http://")) and not any(c.isspace() for c in text):
            url = urlsplit(text)
            netloc = url.netloc.rsplit("@", 1)[-1]
            query = [(key, item) for key, item in parse_qsl(url.query, keep_blank_values=True)
                     if not private_key(key)]
            text = urlunsplit((url.scheme, netloc, url.path, urlencode(query), url.fragment))
        else:
            text = LOCAL_PATH.sub("[local-path]", text)
        text = PRIVATE_IP.sub("[private-address]", text)
        text = ADB_SERVICE.sub("[device-service]", text)
        text = PROCESS_EVIDENCE.sub("[process-identity]", text)
        return CREDENTIAL.sub("[credential-removed]", text)

    def clean(item: Any) -> Any:
        if isinstance(item, dict):
            return {key: clean(child) for key, child in item.items() if not private_key(key)}
        if isinstance(item, list):
            return [clean(child) for child in item]
        return clean_text(item) if isinstance(item, str) else item

    return clean(value)


def assert_public(value: Any) -> None:
    """Fail closed if a future exporter change bypasses the privacy transform."""
    if isinstance(value, dict):
        for key, child in value.items():
            if private_key(key):
                raise ValueError("A private identity or credential field reached public evidence")
            assert_public(child)
    elif isinstance(value, list):
        for child in value:
            assert_public(child)
    elif isinstance(value, str):
        if any(pattern.search(value) for pattern in (LOCAL_PATH, PRIVATE_IP, ADB_SERVICE, PROCESS_EVIDENCE, CREDENTIAL)):
            raise ValueError("A local path, device identity or credential reached public evidence")


def checked_artifact(root: Path, item: dict) -> dict:
    """Only attest bytes actually present inside this project; never hash external local files."""
    path = (root / item["path"]).resolve()
    try:
        relative = path.relative_to(root.resolve())
    except ValueError as error:
        raise ValueError("An artifact is outside the project") from error
    if not path.is_file() or sha(path) != item["sha256"]:
        raise ValueError(f"Artifact is missing or its SHA-256 changed: {relative}")
    if "bytes" in item and path.stat().st_size != item["bytes"]:
        raise ValueError(f"Artifact byte count changed: {relative}")
    return {"path": relative.as_posix(), "bytes": path.stat().st_size, "sha256": item["sha256"]}


def evidence_summary(root: Path, version: str) -> dict:
    validation = read(root, "fascia-validation.json")
    functional = read(root, "fascia-functional-device.json")
    if validation["applicationVersion"] != functional["applicationVersion"]:
        raise ValueError("Unit and device evidence refer to different application versions")
    source_version = functional["applicationVersion"]
    suites = validation["jvm"]
    unit = {key: sum(int(suite.get(key, 0)) for suite in suites)
            for key in ("tests", "failures", "errors", "skipped")}
    unit["passed"] = unit["tests"] - unit["failures"] - unit["errors"] - unit["skipped"]
    device = {key: functional[key] for key in ("status", "testsDiscovered", "passed", "assumptionSkipped", "failed")}
    if unit["failures"] or unit["errors"] or functional["status"] != "passed" or device["failed"]:
        raise ValueError("Only completed successful regression evidence can be exported as passed")
    models = read(root, "model-validation.json")
    build = read(root, "model-build.json")
    content = read(root, "content-validation.json")
    functions = read(root, "content-functions.json")
    fascia = read(root, "fascia-content.json")
    supplement = read(root, "model-connective-supplement.json")
    if models["status"] != "passed" or not content["checksPassed"]:
        raise ValueError("Current model/content consistency validation has not passed")
    artifacts = [checked_artifact(root, item) for item in models["models"]]
    anatomy = checked_artifact(root, build["anatomyMetadata"])
    for item in fascia["inputs"] + fascia["outputs"]:
        checked_artifact(root, item)
    result = {
        "schemaVersion": 1, "targetApplicationVersion": version,
        "evidenceApplicationVersion": source_version,
        "targetVersionFullSuiteExecuted": version == source_version,
        "versionBoundaryZh": ("本摘要仅复述已完成的同版本回归；发布版安装检查须另列。" if version == source_version else
            f"以下历史整轮计数来自已完成的{source_version}功能回归；当前目标版本{version}没有因此被记作执行了整套测试。改名后的单元测试、安装与冒烟结果另列。"),
        "historicalRegression": {"buildVariant": "debug", "unit": unit, "device": device,
                       "lint": validation.get("lint", {})},
        "currentAssetFacts": {
            "modelValidation": models["status"], "models": artifacts, "anatomy": anatomy,
            "structuresBySystem": build["countsBySystem"],
            "acupoints": {key: content[key] for key in ("entries", "meridianEntries", "extraEntries", "instances", "routes")},
            "fascialConnections": fascia["connections"], "fascialInstances": fascia["instances"],
            "fascialReferenceRegions": fascia["targets"], "addedFasciaAndAponeurosisMeshes": len(supplement["addedMeshes"]),
        },
        "professionalReview": {
            "acupointInstancesCompleted": content["medicalReviewedInstances"],
            "traditionalUseEntriesCompleted": functions["professionalReviewedEntries"],
            "fascialReferenceRegionsCompleted": fascia["professionalAnatomicalReviewedTargets"],
            "clinicalTargets": fascia["clinicalTargets"],
            "statusZh": "三维定位、筋膜配准和学习内容仍为教学参考；几何验证不等同专业医学审校。",
        },
        "publicationScopeZh": "仅含匿名统计及当前素材事实；完整设备运行证据保留本地，未复制设备标识、安装路径、截图或原始性能记录。",
    }
    perf_path = root / "manifests/fascia-performance.json"
    if perf_path.exists():
        perf = json.loads(perf_path.read_text(encoding="utf-8"))
        # The old device/performance records must identify exactly the same debug input.
        expected = {a["package"]: a["sha256"] for a in functional.get("artifacts", [])}
        observed = {a["package"]: a["sha256"] for a in perf.get("apks", [])}
        if perf.get("status") == "passed" and expected and observed == expected:
            timing = perf["timeStats"]
            result["historicalPerformanceSummary"] = {
                "evidenceApplicationVersion": source_version, "buildVariant": perf["buildVariant"],
                "mode": perf["mode"], "quality": perf["quality"], "requestedMinutes": perf["requestedMinutes"],
                "snapshotWindowSeconds": timing["elapsedSeconds"],
                "presentedIntervals": timing["intervalCount"],
                "presentedIntervalsPerWallSecond": timing["presentedIntervalsPerWallSecond"],
                "deviceModel": perf.get("device", {}).get("model"), "androidApi": perf.get("device", {}).get("sdk"),
                "limitsZh": "这是所标版本的Debug短时统计；不作为目标版本、十分钟稳定性或Release性能证明。",
            }
    return result


def build_evidence(root: Path, version: str) -> dict[str, dict]:
    inputs = read(root, "build-inputs.json")
    delivery = read(root, "delivery.json")
    if inputs.get("applicationVersion") != version or delivery.get("apk", {}).get("versionName") != version:
        raise ValueError("Build/delivery version is stale; finish the target-version build before --include-build")
    apk = checked_artifact(root, delivery["apk"])
    matches = [item for item in inputs.get("artifacts", []) if item.get("sha256") == apk["sha256"]]
    if not matches:
        raise ValueError("Build inputs and delivery do not identify the same APK")
    for item in matches:
        checked_artifact(root, item)
    # Explicit field selection: this is not a copy of the raw build or local runtime record.
    return {
        "build-evidence.json": {"applicationVersion": version, "versions": inputs.get("versions", {}),
            "commands": inputs.get("commands", {}), "releaseParameters": inputs.get("releaseParameters", {}),
            "inputFiles": inputs.get("files", []), "releaseArtifact": apk,
            "scopeZh": "构建输入与当前APK字节核验；不表示目标版本已重新执行历史整轮功能或性能测试。"},
        "delivery-evidence.json": {"applicationVersion": version, "apk": apk,
            "package": delivery["apk"].get("package"), "checks": delivery.get("checks", {}),
            "counts": delivery.get("counts", {}), "assets": delivery.get("assets", []),
            "nativeLibraries": delivery.get("nativeLibraries", []),
            "clinicalReviewCompleted": delivery["clinicalReviewCompleted"]},
    }


def current_unit_tests(root: Path, version: str) -> dict:
    reports = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No current JUnit XML reports were found")
    source_inputs = [root / "app/build.gradle.kts"] + [path for folder in ("main", "test")
        for path in (root / "app/src" / folder).rglob("*.kt")]
    newest_input = max(path.stat().st_mtime_ns for path in source_inputs if path.is_file())
    if any(path.stat().st_mtime_ns < newest_input for path in reports):
        raise ValueError("JUnit reports predate current Android source inputs; rerun unit tests")
    suites = []
    for path in reports:
        node = ET.parse(path).getroot()
        suites.append({"name": node.attrib.get("name", path.stem),
            **{key: int(node.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")},
            "reportSha256": sha(path)})
    counts = {key: sum(item[key] for item in suites) for key in ("tests", "failures", "errors", "skipped")}
    if not counts["tests"] or counts["failures"] or counts["errors"]:
        raise ValueError("Current unit reports contain no tests or contain failures")
    counts["passed"] = counts["tests"] - counts["skipped"]
    return {"applicationVersion": version, "buildVariant": "debug", **counts, "suites": suites,
            "scopeZh": "本轮JUnit XML计数与摘要；不替代该版本的设备功能回归、安装或性能测试。"}


def export(root: Path, version: str, include_build: bool = False, include_current_units: bool = False) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", version):
        raise ValueError("Invalid application version")
    data = {name: read(root, name) for name in PUBLIC_MANIFESTS}
    data["test-summary.json"] = evidence_summary(root, version)
    if include_build:
        data.update(build_evidence(root, version))
    if include_current_units:
        if not include_build:
            raise ValueError("--current-unit-tests also requires --include-build version/hash verification")
        data["test-summary.json"]["currentUnitTests"] = current_unit_tests(root, version)
    prepared = {name: sanitize(value, root) for name, value in data.items()}
    for value in prepared.values():
        assert_public(value)
    output = root / "docs/validation"
    output.mkdir(parents=True, exist_ok=True)
    # Retire only this exporter's previous derived build summaries, never private evidence or
    # separately maintained rename-smoke summaries in the same public directory.
    for stale in GENERATED_FILES - prepared.keys():
        (output / stale).unlink(missing_ok=True)
    entries = []
    for name, value in prepared.items():
        path = output / name
        encoded = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
        temporary = path.with_suffix(".json.tmp")
        temporary.write_bytes(encoded)
        temporary.replace(path)
        entries.append({"path": name, "bytes": len(encoded), "sha256": hashlib.sha256(encoded).hexdigest()})
    report = {"schemaVersion": 1, "targetApplicationVersion": version, "files": entries,
              "policyZh": "固定素材清单白名单；设备与历史原件不复制；构建证据须显式启用并与当前版本和APK哈希匹配。"}
    (output / "export-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="Target release version; defaults to app/build.gradle.kts versionName")
    parser.add_argument("--include-build", action="store_true", help="Export curated current-version build/APK evidence after byte verification")
    parser.add_argument("--current-unit-tests", action="store_true", help="Add fresh current-version JUnit XML counts; requires --include-build")
    args = parser.parse_args()
    version = args.version
    if version is None:
        match = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text())
        if not match:
            parser.error("Cannot determine versionName; pass --version")
        version = match.group(1)
    try:
        report = export(ROOT, version, args.include_build, args.current_unit_tests)
    except (KeyError, OSError, ValueError) as error:
        parser.exit(1, f"Public evidence export failed: {error}\n")
    print(json.dumps({"targetApplicationVersion": version, "directory": "docs/validation",
                      "files": len(report["files"]), "currentBuildIncluded": args.include_build}, ensure_ascii=False))


if __name__ == "__main__":
    main()
