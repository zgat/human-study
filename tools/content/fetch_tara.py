#!/usr/bin/env python3
"""Verify the pinned, unmodified TARA inputs offline; download only with --fetch."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
COMMIT = "7c05c8407f6e529bc839d34109e67ab00c642c73"
UPSTREAM = f"https://raw.githubusercontent.com/SciCrunch/TARA-Ontology-Repository/{COMMIT}/"
ASSETS = ROOT / "app/src/main/assets"
ORIGINALS = ASSETS / "third_party/tara"
LICENSE_NAME = "CC-BY-NC-ND-4.0.txt"
LICENSE_URL = "https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode.txt"
LICENSE_SHA256 = "38762e3777f4ec00a6f769062a7c3f704fb78ce08303ecff88558da4c49cf9ea"
INPUTS = (
    (
        ORIGINALS / "acupoints-1.7.2.ttl",
        UPSTREAM + "docs/distribution/ontology/version/1.7.2/asserted/acupoints.ttl",
        "c4967c2bb7f4936200f77fac5da19219e6ca5850e7f46dfc8a87bd28b7c68296",
    ),
    (ROOT / "licenses" / LICENSE_NAME, LICENSE_URL, LICENSE_SHA256),
    (ASSETS / "licenses" / LICENSE_NAME, LICENSE_URL, LICENSE_SHA256),
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_or_fetch(path: Path, url: str, expected: str, fetch: bool) -> None:
    if not path.exists():
        if not fetch:
            raise FileNotFoundError(f"缺少 {path.relative_to(ROOT)}；如需联网获取，请显式使用 --fetch")
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix=".tara-", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
        try:
            subprocess.run(
                [
                    "curl", "--fail", "--location", "--silent", "--show-error",
                    "--retry", "2", "--connect-timeout", "15", "--max-time", "60",
                    "--output", str(temporary), url,
                ],
                check=True,
            )
            if sha256(temporary) != expected:
                raise ValueError(f"上游内容哈希与固定输入不一致：{url}")
            # Never replace an existing input, including a concurrent writer's file.
            try:
                with path.open("xb") as output:
                    output.write(temporary.read_bytes())
            except FileExistsError:
                pass
        finally:
            temporary.unlink(missing_ok=True)
    actual = sha256(path)
    if actual != expected:
        raise ValueError(f"哈希不符，已保留原文件：{path.relative_to(ROOT)}\n预期 {expected}\n实际 {actual}")
    print(f"{path.relative_to(ROOT)} {actual}")


def main() -> None:
    parser = argparse.ArgumentParser(description="离线校验 TARA 固定原件及许可全文；默认不联网、不写文件。")
    parser.add_argument("--fetch", action="store_true", help="仅下载缺少的固定原件，已有文件只校验、不覆盖")
    args = parser.parse_args()
    for path, url, expected in INPUTS:
        verify_or_fetch(path, url, expected, args.fetch)
    notices = (ROOT / "licenses/TARA-NOTICE.md", ASSETS / "licenses/TARA-NOTICE.md")
    if notices[0].read_bytes() != notices[1].read_bytes():
        raise ValueError("仓库和安装包中的 TARA-NOTICE.md 不一致")
    print(f"TARA 固定提交 {COMMIT}：正式发布的本体原件及两份许可全文校验通过，署名说明一致。")


if __name__ == "__main__":
    main()
