#!/usr/bin/env python3
"""Validate actual generated GLB bytes and shared anatomy metadata offline."""
import array
import hashlib
import json
import math
import struct
import sys
import zipfile
from pathlib import Path
from inspect_skin_fix import geometry

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for data in iter(lambda: f.read(1024*1024), b''):
            h.update(data)
    return h.hexdigest()


def glb(path, anatomy):
    data = path.read_bytes()
    magic, version, length = struct.unpack_from('<4sII', data)
    assert (magic, version, length) == (b'glTF', 2, len(data))
    jslen, typ = struct.unpack_from('<I4s', data, 12)
    assert typ == b'JSON'
    doc = json.loads(data[20:20+jslen])
    assert doc['extras']['license'] == anatomy['source']['license']
    blen, typ = struct.unpack_from('<I4s', data, 20+jslen)
    assert typ == b'BIN\0'
    binary = memoryview(data)[28+jslen:]
    assert len(binary) == blen and blen - doc['buffers'][0]['byteLength'] in range(4)
    metadata = {s['id']: s for s in anatomy['structures']}
    node_ids = [n['name'] for n in doc['nodes']]
    assert len(node_ids) == len(set(node_ids))
    assert set(node_ids) == set(metadata)
    assert doc['scenes'][0]['nodes'] == list(range(len(node_ids)))
    assert next(m for m in doc['materials'] if m['name'] == 'skin')['alphaMode'] == 'BLEND'
    assert next(m for m in doc['materials'] if m['name'] == 'skin')['pbrMetallicRoughness']['baseColorFactor'][3] == 1
    assert all(m.get('alphaMode', 'OPAQUE') == 'OPAQUE' for m in doc['materials'] if m['name'] != 'skin')
    total_vertices = total_triangles = 0
    for node in doc['nodes']:
        assert not any(k in node for k in ['matrix','rotation','scale','translation'])
        mesh = doc['meshes'][node['mesh']]
        assert mesh['name'] == node['name'] == node['extras']['id']
        primitive = mesh['primitives'][0]
        assert primitive['mode'] == 4
        def values(index):
            a = doc['accessors'][index]
            view = doc['bufferViews'][a['bufferView']]
            width = {'VEC3':3, 'SCALAR':1}[a['type']]
            fmt = {5126:'f',5125:'I',5123:'H'}[a['componentType']]
            start = view.get('byteOffset',0) + a.get('byteOffset',0)
            nbytes = a['count'] * width * struct.calcsize(fmt)
            assert start + nbytes <= len(binary)
            assert nbytes == view['byteLength']
            out = array.array(fmt)
            out.frombytes(binary[start:start+nbytes])
            if sys.byteorder != 'little': out.byteswap()
            return a, out
        pa, pos = values(primitive['attributes']['POSITION'])
        na, norm = values(primitive['attributes']['NORMAL'])
        ia, indices = values(primitive['indices'])
        assert len(pos) == len(norm) == pa['count']*3
        assert na['count'] == pa['count'] and len(indices) % 3 == 0
        assert min(indices) >= 0 and max(indices) < pa['count']
        assert all(math.isfinite(v) for v in pos)
        assert all(math.isfinite(v) for v in norm)
        for i in range(0,len(norm),3):
            assert abs(sum(v*v for v in norm[i:i+3])-1) < 1e-4
        for axis in range(3):
            mn, mx = min(pos[axis::3]), max(pos[axis::3])
            assert abs(mn-pa['min'][axis]) < 1e-5 and abs(mx-pa['max'][axis]) < 1e-5
            assert mn >= metadata[node['name']]['bounds']['min'][axis]-1e-5
            assert mx <= metadata[node['name']]['bounds']['max'][axis]+1e-5
        total_vertices += pa['count']; total_triangles += len(indices)//3
    return {'path':str(path.relative_to(ROOT)), 'sha256':digest(path), 'bytes':len(data),
            'structures':len(node_ids), 'vertices':total_vertices, 'triangles':total_triangles,
            'nodeIds':node_ids}


def main():
    anatomy = json.loads((ASSETS/'anatomy.json').read_text())
    manifest = json.loads((ROOT/'manifests/model-build.json').read_text())
    for item in manifest['inputFiles']:
        assert digest(ROOT/item['file']) == item['sha256'], item['file']
    for item in manifest['pipelineFiles'] + manifest['licenseFiles']:
        assert digest(ROOT/item['path']) == item['sha256'], item['path']
    with zipfile.ZipFile(ROOT/'tools/assets/source/isa_BP3D_4.0_obj_99.zip') as z:
        assert z.testzip() is None
        # A separately range-fetched early skin must exactly match the finished ZIP.
        assert z.read('isa_BP3D_4.0_obj_99/FJ2810.obj') == (ROOT/'tools/assets/source/FJ2810.obj').read_bytes()
    structures = anatomy['structures']
    assert len({x['id'] for x in structures}) == len(structures)
    for x in structures:
        assert x['system'] in ['skin','muscle','organ','bone','connective','space','unknown']
        assert x.get('tissueType') and x.get('tissueTypeZh') and x.get('sourceConceptNoteZh')
        assert x['region'] in ['whole','head_neck','trunk','upper_limb','lower_limb']
        assert x['layer'] in ([1,2,3] if x['system']=='muscle' else [0])
        assert any('\u4e00' <= c <= '\u9fff' for c in x['nameZh'])
        assert x['translationStatus'] != 'pending'
        assert x['nodeName'] == x['id']
    for name in ['latissimus dorsi','rectus abdominis','internal abdominal oblique',
                 'transversus abdominis','masseter','temporalis','gluteus maximus',
                 'pectoralis major','biceps brachii','tibialis anterior']:
        assert any(name in x['nameEn'] for x in structures), name
    results = [glb(ASSETS/'models'/f'body-{q}.glb', anatomy) for q in ['standard','low']]
    assert results[0]['nodeIds'] == results[1]['nodeIds']
    assert results[1]['triangles'] < results[0]['triangles']
    _, standard_skin = geometry(ASSETS/'models/body-standard.glb')
    _, low_skin = geometry(ASSETS/'models/body-low.glb')
    for key in ['position','normal','indices']:
        assert standard_skin[key][1] == low_skin[key][1], key
        assert all(standard_skin[key][0][field] == low_skin[key][0][field]
                   for field in ['componentType','count','type'])
    assert digest(ASSETS/'anatomy.json') == manifest['anatomyMetadata']['sha256']
    muscle = [s for s in structures if s['system'] == 'muscle']
    assert all(s.get(k) for s in muscle for k in ['originZh','insertionZh','functionZh','descriptionSources','structureType'])
    assert all(s['learningReviewStatus'] == 'editorial-unreviewed' for s in muscle)
    occlusion_manifest = json.loads((ROOT/'manifests/model-skin-occlusion.json').read_text())
    occlusion_path = ROOT/occlusion_manifest['output']['path']
    occlusion = occlusion_path.read_bytes()
    magic, count = struct.unpack_from('<4sI',occlusion)
    assert magic == b'MSO1' and len(occlusion) == 8+count*36 and len(occlusion) <= 2000000
    assert digest(occlusion_path) == occlusion_manifest['output']['sha256']
    assert digest(ROOT/occlusion_manifest['input']['path']) == occlusion_manifest['input']['sha256']
    taxonomy=json.loads((ROOT/'manifests/model-taxonomy.json').read_text())
    assert taxonomy.get('initialAppendGeometryByteExact',taxonomy['existingGeometryByteExact']) and taxonomy['skinOcclusionUnchanged']
    exceptions=set(taxonomy.get('geometryExceptionIds',[]))
    assert digest(occlusion_path)==taxonomy['skinOcclusionSha256']
    for quality in ['standard','low']:
        actual,_=geometry(ASSETS/f'models/body-{quality}.glb')
        previous=taxonomy['modelsBefore'][quality]['nodes']
        assert all(actual['nodes'][key]==value for key,value in previous.items() if key not in exceptions)
    if exceptions:
        fixed=json.loads((ROOT/'manifests/model-trapezius-fix.json').read_text())
        assert fixed['status']=='passed' and fixed['other1235GeometryPayloadsByteExact']
        assert exceptions==set(fixed['replacedIds'])
        for quality in ['standard','low']:
            actual,_=geometry(ASSETS/f'models/body-{quality}.glb')
            assert all(actual['nodes'][key]==value for key,value in fixed['modelsBefore'][quality]['nodes'].items() if key not in exceptions)
    assert len(structures)==1239
    assert next(x for x in structures if x['id']=='FJ3178')['nameZh']=='胸骨体'
    assert next(x for x in structures if x['id']=='FJ3153')['nameZh']=='剑突'
    assert all(next(x for x in structures if x['id']==key)['system']=='muscle'
               for key in ['FJ1520','FJ1520M','FJ1521','FJ1521M'])
    assert next(x for x in structures if x['id']=='FJ1448')['system']=='connective'
    for triangle in struct.iter_unpack('<9f',occlusion[8:]):
        assert all(math.isfinite(v) for v in triangle)
        a,b,c = triangle[:3],triangle[3:6],triangle[6:]
        u=[b[i]-a[i] for i in range(3)]; v=[c[i]-a[i] for i in range(3)]
        assert sum((u[(i+1)%3]*v[(i+2)%3]-u[(i+2)%3]*v[(i+1)%3])**2 for i in range(3))>0
    for result in results:
        expected = next(x for x in manifest['outputs'] if x['path'] == result['path'])
        assert all(expected[k] == result[k] for k in ['sha256','bytes','structures','vertices','triangles'])
        del result['nodeIds']
    report = {'status':'passed','modelId':anatomy['modelId'], 'models':results,
              'anatomySha256':digest(ASSETS/'anatomy.json'),
              'checks':['source SHA-256','ZIP CRC','early skin equals complete archive',
                        'glTF headers and buffers','finite indexed geometry and unit normals',
                        'accessor bounds','same IDs and order across quality tiers',
                        'Chinese labels','major muscle mesh coverage','skin-only alpha blending',
                        'all muscle learning fields and source links','MSO1 skin occlusion header, hash and finite nondegenerate triangles',
                        'low-tier skin exact POSITION/NORMAL/index equality with standard tier',
                        'explicit systems and tissue/source concept metadata','previous geometry payloads byte-exact outside four authorized trapezius fixes',
                        'eight genuine source fascia additions','skin occlusion file unchanged'],
              'limitsZh':['未验证真实 Android 帧率。','未进行医学专业审校或完整视觉验收。']}
    (ROOT/'manifests/model-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
