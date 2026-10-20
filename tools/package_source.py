#!/usr/bin/env python3
"""Archive the exact committed public source tree; never include local evidence or keys."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = {'.gitignore', 'README.md', 'LICENSE', 'NOTICE', 'build.gradle.kts',
              'settings.gradle.kts', 'gradle.properties', 'gradlew'}
SOURCE_DIRS = {'app', 'gradle', 'licenses', 'tools', 'docs', '.github'}


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True).stdout


def main():
    commit = git('rev-parse', 'HEAD').decode().strip()
    # No accidental mixture of a committed tree and later working-copy edits.
    git('diff', '--exit-code', 'HEAD', '--')
    names = git('ls-tree', '-r', '--name-only', '-z', 'HEAD').decode().split('\0')
    for name in filter(None, names):
        path = Path(name)
        if name not in ROOT_FILES and path.parts[0] not in SOURCE_DIRS:
            raise RuntimeError('Unexpected public source path: ' + name)
        if any(part in {'build', '.tools', 'source', 'sources', '__pycache__'} for part in path.parts):
            raise RuntimeError('Local input/cache must not be tracked: ' + name)
        if path.suffix in {'.jks', '.keystore', '.apk', '.hprof'}:
            raise RuntimeError('Private key or build output must not be tracked: ' + name)
    config = git('show', 'HEAD:app/build.gradle.kts').decode()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
    archive = ROOT / f'outputs/human-study-{version}-source.zip'
    archive.parent.mkdir(exist_ok=True)
    git('archive', '--format=zip', '--prefix=HumanStudy/', '--output=' + str(archive), 'HEAD')
    entries = []
    with zipfile.ZipFile(archive) as zipped:
        assert zipped.testzip() is None
        for info in zipped.infolist():
            if not info.is_dir():
                entries.append({'path': info.filename.removeprefix('HumanStudy/'),
                                'bytes': info.file_size,
                                'sha256': hashlib.sha256(zipped.read(info)).hexdigest()})
    report = {'path': str(archive.relative_to(ROOT)), 'commit': commit,
              'bytes': archive.stat().st_size, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
              'files': entries, 'source': 'git archive HEAD',
              'exclusions': ['local evidence and logs', 'device identity and pairing', 'private keys',
                             'downloaded raw sources', 'build outputs', 'SDK and Gradle caches']}
    (ROOT / 'manifests').mkdir(exist_ok=True)
    (ROOT / 'manifests/source-package.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'files'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
