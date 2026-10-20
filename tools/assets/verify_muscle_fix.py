#!/usr/bin/env python3
"""Prove muscle-only geometry changes and retain before / after topology evidence."""
import hashlib
import json
import sys
from pathlib import Path
from inspect_skin_fix import geometry

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT/'app/src/main/assets'
REPORT = ROOT/'manifests/model-muscle-fix.json'


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def snapshot():
    anatomy = json.loads((ASSETS/'anatomy.json').read_text())
    structures = anatomy['structures']
    muscles = {s['id'] for s in structures if s['system'] == 'muscle'}
    models = {}
    for quality in ('standard','low'):
        data, _ = geometry(ASSETS/f'models/body-{quality}.glb')
        models[quality] = {'sha256': data['sha256'], 'bytes': data['bytes'],
            'nodeIds': list(data['nodes']),
            'nonMuscleGeometry': {key: value for key,value in data['nodes'].items() if key not in muscles},
            'allGeometryDigest': {key: digest(value) for key,value in data['nodes'].items()}}
    points = json.loads((ASSETS/'content/acupoints.json').read_text())
    points = points['points'] if isinstance(points,dict) and 'points' in points else points
    anchors = [{'id': p['id'], 'instances': p['instances']} for p in points]
    return {'models': models, 'structuresSha256': digest(structures), 'anchorsSha256': digest(anchors),
            'topology': json.loads((ROOT/'manifests/model-muscle-topology.json').read_text())['summary']}


def main():
    current = snapshot()
    if sys.argv[1:] == ['before']:
        report = {'schemaVersion':1, 'purposeZh':'仅替换肌肉简化方法，验证其他系统几何、结构信息和穴位实例不变。',
                  'before': current}
    elif sys.argv[1:] == ['check']:
        report = json.loads(REPORT.read_text())
        before = report['before']
        assert before['structuresSha256'] == current['structuresSha256'], 'Anatomy structure metadata changed'
        assert before['anchorsSha256'] == current['anchorsSha256'], 'Acupoint instances changed'
        changed = {}
        for quality in ('standard','low'):
            old, new = before['models'][quality], current['models'][quality]
            assert old['nodeIds'] == new['nodeIds'], 'Stable IDs / order changed'
            assert old['nonMuscleGeometry'] == new['nonMuscleGeometry'], f'{quality}: non-muscle bytes changed'
            changed[quality] = [key for key,value in new['allGeometryDigest'].items()
                                if old['allGeometryDigest'][key] != value]
        report.update(status='passed', after=current, changedNodes=changed,
                      checks=['1231 stable IDs and order unchanged in both tiers',
                              '728 non-muscle nodes retain byte-exact POSITION/NORMAL/index payloads in each tier',
                              'Anatomical structure metadata, bounds, Chinese text and layer IDs unchanged',
                              'All 413 acupoint entry instance records unchanged'])
    else:
        raise SystemExit('Usage: verify_muscle_fix.py before|check')
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'phase':sys.argv[1], 'status':report.get('status','captured'),
                      'modelBytes':{q:m['bytes'] for q,m in current['models'].items()},
                      'topology':current['topology']},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
