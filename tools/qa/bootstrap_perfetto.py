#!/usr/bin/env python3
"""Download official trace_processor into this project's .tools, without a home cache."""
import ast
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / ".tools/perfetto"
LAUNCHER_URL = "https://get.perfetto.dev/trace_processor"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    launcher = DEST / "trace_processor"
    if not launcher.exists():
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", LAUNCHER_URL, "--output", str(launcher)], check=True)
    tree = ast.parse(launcher.read_text())
    manifest = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                    and any(isinstance(target, ast.Name) and target.id == "TRACE_PROCESSOR_SHELL_MANIFEST" for target in node.targets))
    entry = next(item for item in manifest if item.get("platform") == sys.platform.lower()
                 and platform.machine().lower() in item.get("machine", []))
    if not entry["url"].startswith("https://commondatastorage.googleapis.com/perfetto-luci-artifacts/"):
        raise RuntimeError("Unexpected official binary origin")
    binary = DEST / "trace_processor_shell"
    if not binary.is_file() or digest(binary) != entry["sha256"]:
        temporary = DEST / "trace_processor_shell.download"
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", entry["url"], "--output", str(temporary)], check=True)
        if digest(temporary) != entry["sha256"]:
            raise RuntimeError("Official manifest SHA-256 verification failed")
        temporary.chmod(0o755)
        temporary.replace(binary)
    metadata = {"launcherUrl": LAUNCHER_URL, "launcherSha256": digest(launcher), "binary": str(binary), **entry}
    (DEST / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
