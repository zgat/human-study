#!/usr/bin/env python3
"""Compare genuine source topology with both display LODs; never repair anatomy blindly."""
import collections
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

from build_models import load_obj

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets'


def topology(vs, fs):
    # OBJ files may split an otherwise shared geometric vertex at a seam.
    welded = {}
    remap = []
    for v in vs:
        remap.append(welded.setdefault(tuple(v), len(welded)))
    edges = collections.Counter()
    degenerate = 0
    for face in fs:
        a, b, c = [remap[i] for i in face]
        if len({a, b, c}) < 3:
            degenerate += 1
            continue
        for p, q in ((a, b), (b, c), (c, a)):
            edges[tuple(sorted((p, q)))] += 1
    return {'vertices': len(vs), 'weldedVertices': len(welded), 'triangles': len(fs),
            'boundaryEdges': sum(n == 1 for n in edges.values()),
            'nonManifoldEdges': sum(n > 2 for n in edges.values()),
            'collapsedFaces': degenerate}


def read_glb(path):
    raw = path.read_bytes()
    jslen = struct.unpack_from('<I', raw, 12)[0]
    doc = json.loads(raw[20:20+jslen])
    blob = memoryview(raw)[28+jslen:]
    def values(index):
        a = doc['accessors'][index]
        v = doc['bufferViews'][a['bufferView']]
        offset = v.get('byteOffset', 0) + a.get('byteOffset', 0)
        fmt = {5123: 'H', 5125: 'I', 5126: 'f'}[a['componentType']]
        width = {'VEC3': 3, 'SCALAR': 1}[a['type']]
        size = struct.calcsize(fmt) * width * a['count']
        flat = [x[0] for x in struct.iter_unpack('<'+fmt, blob[offset:offset+size])]
        return [tuple(flat[i:i+width]) for i in range(0, len(flat), width)] if width > 1 else flat
    result = {}
    for node in doc['nodes']:
        if node.get('extras', {}).get('system') != 'muscle':
            continue
        p = doc['meshes'][node['mesh']]['primitives'][0]
        vs = values(p['attributes']['POSITION'])
        ids = values(p['indices'])
        fs = [tuple(ids[i:i+3]) for i in range(0, len(ids), 3)]
        result[node['name']] = topology(vs, fs)
    return result, hashlib.sha256(raw).hexdigest()


def main():
    anatomy = json.loads((ASSETS/'anatomy.json').read_text())
    selected = {s['id']: s for s in anatomy['structures'] if s['system'] == 'muscle'}
    result = {key: {'id': key, 'nameZh': s['nameZh'], 'sourceDataset': s['sourceDataset']}
              for key, s in selected.items()}
    source = ROOT/'tools/assets/source'
    archive = source/'isa_BP3D_4.0_obj_99.zip'
    with zipfile.ZipFile(archive) as z:
        members = {Path(n).stem: n for n in z.namelist() if n.endswith('.obj')}
        for key in selected:
            if key in members:
                result[key]['source'] = topology(*load_obj(z.read(members[key])))
    if (source/'MuscularSystem100.fbx').exists():
        from fbx_extract import Fbx, static_meshes
        for mesh in static_meshes(Fbx(source/'MuscularSystem100.fbx')):
            key = 'ZA_' + str(mesh['id'])
            if key in selected:
                result[key]['source'] = topology(mesh['vertices'], mesh['faces'])
    inputs = []
    for quality in ('standard', 'low'):
        path = ASSETS/f'models/body-{quality}.glb'
        data, digest = read_glb(path)
        inputs.append({'path': str(path.relative_to(ROOT)), 'sha256': digest})
        for key, stats in data.items():
            result[key][quality] = stats
    summary = {}
    for quality in ('source', 'standard', 'low'):
        rows = [r[quality] for r in result.values() if quality in r]
        summary[quality] = {'nodes': len(rows), 'triangles': sum(r['triangles'] for r in rows),
            'nodesWithBoundary': sum(r['boundaryEdges'] > 0 for r in rows),
            'boundaryEdges': sum(r['boundaryEdges'] for r in rows),
            'nonManifoldEdges': sum(r['nonManifoldEdges'] for r in rows)}
    newly_open = {q: [k for k,r in result.items() if 'source' in r and
                     r['source']['boundaryEdges'] == 0 and r[q]['boundaryEdges'] > 0]
                  for q in ('standard','low')}
    report = {'schemaVersion': 1, 'purposeZh': '对比肌肉源网格与两档显示几何，区分原生开边和聚类引入开边。',
              'modelInputs': inputs, 'summary': summary, 'newlyOpenNodes': newly_open,
              'limitationsZh': '边界边只能证明网格开口，不能单独证明画面每处暗斑的原因；肌肉之间及关节的正常空隙另计。',
              'nodes': list(result.values())}
    out = ROOT/'manifests/model-muscle-topology.json'
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'summary': summary, 'newlyOpenNodeCounts': {q: len(v) for q,v in newly_open.items()}}, indent=2))


if __name__ == '__main__':
    main()
