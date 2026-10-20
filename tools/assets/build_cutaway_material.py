#!/usr/bin/env python3
"""Compile the sagittal clipping material with the runtime-matching official matc 1.78.0."""
import argparse
import hashlib
import json
import platform
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--matc', type=Path, default=ROOT/'.tools/filament-1.78.0/filament/bin/matc')
    args = parser.parse_args()
    compiler = args.matc.resolve()
    version = subprocess.check_output([str(compiler), '--version'], text=True).strip()
    if version != '78':
        raise SystemExit('Use matc from official Filament 1.78.0 to match the packaged runtime.')
    source = ROOT/'tools/assets/cutaway.mat'
    output = ROOT/'app/src/main/assets/materials/cutaway.filamat'
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(compiler), '-a', 'opengl', '-p', 'mobile', '-S', '-o', str(output), str(source)]
    subprocess.run(command, check=True)
    report = {'schemaVersion':1, 'filamentVersion':'1.78.0',
        'compiler':{'name':'matc', 'reportedMaterialVersion':version, 'sha256':sha(compiler),
                    'host':platform.system()+'/'+platform.machine(),
                    'officialMacArchive':{'url':'https://github.com/google/filament/releases/download/v1.78.0/filament-v1.78.0-mac.tgz',
                        'sha256':'dc2fbc7681998842e85ccc211ed1292b15631a97731fa49ef07c2698870ce9c5',
                        'digestSource':'https://github.com/google/filament/releases/expanded_assets/v1.78.0'}},
        'parameters':['-a','opengl','-p','mobile','-S'],
        'source':{'path':str(source.relative_to(ROOT)), 'sha256':sha(source)},
        'output':{'path':str(output.relative_to(ROOT)), 'bytes':output.stat().st_size, 'sha256':sha(output)},
        'coordinates':{'units':'metres','plane':'x=0 sagittal','left':'+X','right':'-X'},
        'behaviorZh':'裁切调用方指定外表面的半侧片元，当前含皮肤、肌肉和眼口表层；采用masked材质同步作用于颜色、深度和点选pass，深部内脏、牙齿、骨骼及锚点坐标不变。',
        'limitationsZh':['没有生成切面填充或新增解剖组织；这是观察用半身剖露。',
                         '材质适用于当前无纹理、统一系统色的模型；纹理模型需另接材质属性。']}
    (ROOT/'manifests/cutaway-material.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report['output'],indent=2))


if __name__ == '__main__':
    main()
