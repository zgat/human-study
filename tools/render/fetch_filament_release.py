#!/usr/bin/env python3
"""Fetch the three unmodified Android AARs from the pinned official Filament release."""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import pathlib
import re
import urllib.request
import zipfile

VERSION = "1.78.0"
PROJECT = pathlib.Path(__file__).resolve().parents[2]
LIBS = PROJECT / "app" / "libs"
MANIFEST = pathlib.Path(__file__).with_name("filament-release.json")
RELEASE = f"https://github.com/google/filament/releases/tag/v{VERSION}"
LISTING = f"https://github.com/google/filament/releases/expanded_assets/v{VERSION}"
NAMES = [f"{name}-v{VERSION}-android.aar" for name in ("filament", "gltfio", "filament-utils")]


def open_url(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": "MeridianAtlas-build"})
    return urllib.request.urlopen(request, timeout=120)


def inspect(path: pathlib.Path, url: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        failure = archive.testzip()
        if failure:
            raise RuntimeError(f"AAR CRC check failed: {path.name}: {failure}")
        entries = archive.namelist()
        if "classes.jar" not in entries or "AndroidManifest.xml" not in entries:
            raise RuntimeError(f"Not an Android AAR: {path.name}")
        abis = sorted({item.split("/")[1] for item in entries if item.startswith("jni/") and item.endswith(".so")})
        if "arm64-v8a" not in abis:
            raise RuntimeError(f"Missing arm64-v8a JNI libraries: {path.name}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return {"path": str(path.relative_to(PROJECT)), "source": url,
            "bytes": path.stat().st_size, "sha256": digest.hexdigest(),
            "native_abis": abis, "aar_crc_verified": True}


def main() -> None:
    with open_url(LISTING) as response:
        listing = response.read().decode("utf-8")
    links = {"https://github.com" + item for item in re.findall(r'href="([^"]+)"', listing)
             if item.startswith(f"/google/filament/releases/download/v{VERSION}/")}
    urls = {name: f"https://github.com/google/filament/releases/download/v{VERSION}/{name}"
            for name in NAMES}
    missing = [name for name, url in urls.items() if url not in links]
    if missing:
        raise RuntimeError(f"Official release listing lacks required AARs: {missing}")
    LIBS.mkdir(parents=True, exist_ok=True)

    def fetch(name: str) -> dict:
        destination = LIBS / name
        url = urls[name]
        if not destination.exists():
            partial = destination.with_suffix(".aar.part")
            with open_url(url) as response, partial.open("wb") as output:
                while chunk := response.read(1 << 20):
                    output.write(chunk)
            inspect(partial, url)
            partial.replace(destination)
        item = inspect(destination, url)
        print(f"{name}: {item['bytes']} bytes SHA-256 {item['sha256']}", flush=True)
        return item

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        artifacts = list(pool.map(fetch, NAMES))
    manifest = {"component": "Google Filament Android", "version": VERSION,
                "release": RELEASE, "official_asset_listing": LISTING,
                "reason": "The pinned GitHub release is available before its Maven Central artifacts.",
                "checksum_scope": "Local download readback SHA-256; upstream digest not independently available.",
                "artifacts": artifacts}
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Manifest: {MANIFEST.relative_to(PROJECT)}", flush=True)


if __name__ == "__main__":
    main()
