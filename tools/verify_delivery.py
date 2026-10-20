#!/usr/bin/env python3
"""Verify the actual APK bytes, bundled data, ABI and signing; write a delivery manifest."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
def sha(data): return hashlib.sha256(data).hexdigest()
def sdk_tool(name):
    roots = [ROOT / ".tools/android-sdk", ROOT.parent.parent / ".tools/android-sdk"]
    if os.environ.get("ANDROID_HOME"): roots.append(Path(os.environ["ANDROID_HOME"]))
    for root in roots:
        for folder in sorted((root / "build-tools").glob("*"), reverse=True):
            candidate = folder / name
            if candidate.is_file(): return str(candidate)
    raise RuntimeError(f"SDK tool not found: {name}")
def command(args):
    result = subprocess.run(args, capture_output=True, text=True, check=True)
    return result.stdout + result.stderr
def elf_alignment(data):
    assert data[:5] == b"\x7fELF\x02", "Expected a 64-bit ELF library"
    assert data[5] == 1, "Expected little endian ELF"
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    values = []
    for index in range(count):
        base = offset + index*size
        if struct.unpack_from("<I",data,base)[0] == 1:
            values.append(struct.unpack_from("<Q",data,base+48)[0])
    assert values and min(values) >= 16384, f"ELF load segment alignment < 16KiB: {values}"
    return values

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve()
    checks = {"zipCrc": False, "bundledSourceHashes": False, "offlinePermission": False,
        "arm64Only": False, "elf16KiBAlignment": False, "signature": False, "zip16KiBAlignment": False, "applicationLabel": False}
    artifacts = []
    native = []
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "APK ZIP CRC failure"
        checks["zipCrc"] = True
        for path in sorted((ROOT / "app/src/main/assets").rglob("*")):
            if not path.is_file(): continue
            relative = path.relative_to(ROOT / "app/src/main/assets").as_posix()
            source = path.read_bytes(); packaged = archive.read("assets/" + relative)
            assert sha(source) == sha(packaged), f"Stale or changed packaged asset: {relative}"
            artifacts.append({"path": relative, "bytes": len(packaged), "sha256": sha(packaged)})
        checks["bundledSourceHashes"] = True
        for name in archive.namelist():
            if name.startswith("lib/") and name.endswith(".so"):
                assert name.split("/")[1] == "arm64-v8a", f"Unexpected ABI: {name}"
                data = archive.read(name)
                native.append({"path": name, "sha256": sha(data), "loadAlignments": elf_alignment(data)})
        assert native
        checks["arm64Only"] = True; checks["elf16KiBAlignment"] = True
        points = json.loads(archive.read("assets/content/acupoints.json"))["points"]
        routes = json.loads(archive.read("assets/content/meridians.json"))["meridians"]
        structures = json.loads(archive.read("assets/anatomy.json"))["structures"]
        fascial = json.loads(archive.read("assets/content/fascia.json"))["connections"]
        assert len(points) == 413 and len(routes) == 20
        assert sum(p["kind"] == "meridian" for p in points) == 362
        assert sum(p["kind"] == "extra" for p in points) == 51
        by_id = {part["id"]: part for part in structures}
        assert all(by_id[id]["system"] == "muscle" for id in ("FJ1520", "FJ1520M", "FJ1521", "FJ1521M"))
        assert by_id["FJ3178"]["system"] == "bone" and by_id["FJ1448"]["system"] == "connective"
        assert fascial and all(group["targets"] and group["sourceRefs"] for group in fascial)
        assert all(id in by_id for group in fascial for id in group["structureIds"])
    badging = command([sdk_tool("aapt2"), "dump", "badging", str(apk)])
    assert "name='com.zga.meridianatlas'" in badging
    assert "application-label:'人体研究'" in badging, "Unexpected application name"
    checks["applicationLabel"] = True
    package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
    version_name = re.search(r"versionName='([^']+)'", package_line).group(1)
    version_code = int(re.search(r"versionCode='(\d+)'", package_line).group(1))
    assert re.search(r"^(?:minSdkVersion|sdkVersion):'29'$", badging, re.MULTILINE)
    assert "targetSdkVersion:'36'" in badging
    assert "android.permission.INTERNET" not in badging
    checks["offlinePermission"] = True
    signing = command([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
    checks["signature"] = True
    alignment = command([sdk_tool("zipalign"), "-c", "-P", "16", "4", str(apk)])
    checks["zip16KiBAlignment"] = True
    report = {"collectedAtUtc": datetime.now(timezone.utc).isoformat(), "apk": {
        "path": str(apk.relative_to(ROOT)), "bytes": apk.stat().st_size, "sha256": sha(apk.read_bytes()),
        "package": "com.zga.meridianatlas", "versionName": version_name, "versionCode": version_code, "minSdk": 29, "targetSdk": 36,
        "signingPurpose": "local-testing"}, "checks": checks, "assets": artifacts,
        "nativeLibraries": native, "counts": {"acupoints": len(points),
        "instances": sum(len(p["instances"]) for p in points), "meridians": len(routes), "structures": len(structures),
        "fascialConnections": len(fascial), "connectionReferenceRegions": sum(len(group["targets"]) for group in fascial)},
        "clinicalReviewCompleted": False}
    (ROOT / "manifests/delivery.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
    (ROOT / "logs/apk-badging.txt").write_text(badging)
    (ROOT / "logs/apk-signature.txt").write_text(signing)
    print(json.dumps({"apk": report["apk"], "checks": checks, "counts": report["counts"]}, ensure_ascii=False, indent=2))

if __name__ == "__main__": main()
