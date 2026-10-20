#!/usr/bin/env python3
"""Download fixed upstream inputs. Invoke after sourcing the local network env."""
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent / 'source'
BASE = 'https://dbarchive.biosciencedbc.jp/data/bodyparts3d/20130619/'
BODY_FILES = ['isa_BP3D_4.0_obj_99.zip', 'isa_element_parts.txt',
              'isa_inclusion_relation_list.txt', 'isa_parts_list_e.txt',
              'partof_parts_list_e.txt', 'partof_inclusion_relation_list.txt',
              'partof_element_parts.txt', 'README_e.html', 'coordinate_system.png']
INPUTS = [(BASE + n, SOURCE / n) for n in BODY_FILES] + [
    ('https://dbarchive.biosciencedbc.jp/en/bodyparts3d/lic.html', SOURCE / 'license.html'),
    ('https://raw.githubusercontent.com/LluisV/Z-Anatomy/PC-Version/Resources/Models/FBX/MuscularSystem100.fbx', SOURCE / 'MuscularSystem100.fbx'),
    ('https://raw.githubusercontent.com/Z-Anatomy/Models-of-human-anatomy/master/License.txt', ROOT / 'licenses/Z-Anatomy-upstream.txt'),
    ('https://raw.githubusercontent.com/spdx/license-list-data/main/text/CC-BY-4.0.txt', ROOT / 'licenses/CC-BY-4.0.txt'),
    ('https://raw.githubusercontent.com/spdx/license-list-data/main/text/CC-BY-SA-4.0.txt', ROOT / 'licenses/CC-BY-SA-4.0.txt'),
]

if __name__ == '__main__':
    for url, target in INPUTS:
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            complete = not target.name.endswith('.zip')
            if not complete:
                try:
                    with zipfile.ZipFile(target) as archive:
                        complete = archive.testzip() is None
                except zipfile.BadZipFile:
                    complete = False
            if complete:
                print('Existing input retained:', target.name, flush=True)
                continue
        subprocess.run(['curl', '-fL', '-C', '-', '--retry', '3', '--retry-all-errors',
                        '--connect-timeout', '15', '--max-time', '900', url,
                        '-o', str(target)], check=True)
