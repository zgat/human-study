#!/usr/bin/env python3
"""Build offline glTF 2.0 anatomy assets from official BodyParts3D 4.0 OBJ.

Python standard library plus bundled Node.js meshoptimizer. All geometry is source geometry:
muscles use bounded, topology-checked edge collapse; other systems use deterministic clustering.
Never substitute anatomical primitives or fill anatomical openings.
"""
from __future__ import annotations

import argparse
import array
import csv
import hashlib
import json
import math
import re
import struct
import sys
import zipfile
import statistics
from muscle_geometry import simplify_muscles
from collections import defaultdict
from pathlib import Path
from muscle_learning import apply_learning
from taxonomy import registry as taxonomy_registry, preferred_source, system_for, apply_taxonomy

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = HERE / 'source'
ASSETS = ROOT / 'app/src/main/assets'
MANIFEST = ROOT / 'manifests'
MODEL_ID = 'bodyparts3d-male-v4'
BASE_URL = 'https://dbarchive.biosciencedbc.jp/data/bodyparts3d/20130619/'
ARCHIVE = 'isa_BP3D_4.0_obj_99.zip'


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        while b := f.read(1024 * 1024):
            h.update(b)
    return h.hexdigest()


def read_tsv(name):
    with (SOURCE / name).open(encoding='utf-8-sig') as f:
        return list(csv.DictReader(f, delimiter='\t'))


def source_tables():
    names = {r['concept id']: r['en'] for r in read_tsv('isa_parts_list_e.txt')}
    element_map = defaultdict(set)
    for r in read_tsv('isa_element_parts.txt'):
        element_map[r['concept id']].add(r['element file id'])
    inverse = defaultdict(list)
    for concept, files in element_map.items():
        for file_id in files:
            inverse[file_id].append((len(files), concept, names.get(concept, '')))
    for values in inverse.values():
        values.sort(key=lambda x: (x[0], -bool(re.search(r'\b(left|right)\b', x[2])), x[1]))
    return names, element_map, inverse


def load_obj(data):
    vertices, faces = [], []
    for line in data.decode('utf-8', errors='replace').splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] == 'v':
            vertices.append(tuple(map(float, fields[1:4])))
        elif fields[0] == 'f':
            ids = [int(v.split('/')[0]) for v in fields[1:]]
            ids = [i - 1 if i > 0 else len(vertices) + i for i in ids]
            for j in range(1, len(ids) - 1):
                faces.append((ids[0], ids[j], ids[j + 1]))
    if not vertices or not faces:
        raise ValueError('OBJ has no indexed geometry')
    return vertices, faces


def bounds(vertices):
    return {'min': [min(v[i] for v in vertices) for i in range(3)],
            'max': [max(v[i] for v in vertices) for i in range(3)]}


def normals(vertices, faces):
    out = [[0., 0., 0.] for _ in vertices]
    for a, b, c in faces:
        p, q, r = vertices[a], vertices[b], vertices[c]
        u = [q[i] - p[i] for i in range(3)]
        v = [r[i] - p[i] for i in range(3)]
        n = (u[1] * v[2] - u[2] * v[1],
             u[2] * v[0] - u[0] * v[2],
             u[0] * v[1] - u[1] * v[0])
        for index in (a, b, c):
            for i in range(3):
                out[index][i] += n[i]
    result = []
    for n in out:
        length = math.sqrt(sum(x * x for x in n))
        result.append(tuple(x / length for x in n) if length > 1e-20 else (0., 1., 0.))
    return result


def compact(vertices, faces):
    used = sorted(set(i for face in faces for i in face))
    index = {old: new for new, old in enumerate(used)}
    return [vertices[i] for i in used], [tuple(index[i] for i in f) for f in faces]


def cluster(vertices, faces, cell):
    # Components below 120 triangles remain exact, preserving tiny bones/structures.
    if len(faces) < 120:
        return vertices, faces, False
    bins, remap = {}, []
    acc = []
    for x, y, z in vertices:
        key = (round(x / cell), round(y / cell), round(z / cell))
        if key not in bins:
            bins[key] = len(acc)
            acc.append([0., 0., 0., 0])
        i = bins[key]
        a = acc[i]
        a[0] += x; a[1] += y; a[2] += z; a[3] += 1
        remap.append(i)
    vs = [(x / n, y / n, z / n) for x, y, z, n in acc]
    fs, seen = [], set()
    for face in faces:
        f = tuple(remap[i] for i in face)
        if len(set(f)) < 3:
            continue
        key = tuple(sorted(f))
        if key not in seen:
            seen.add(key)
            fs.append(f)
    if len(fs) < 4 or len(fs) >= len(faces):
        return vertices, faces, False
    vs, fs = compact(vs, fs)
    return vs, fs, True


def classify(file_id, name, tables):
    return system_for(file_id)


def region(name, b):
    if name == 'skin':
        return 'whole'
    if re.search(r'foot|halluci|fibular|tibia|femor|glute|thigh|\bleg\b|patella|calcane|\btars|plantar|psoas|iliacus|sartorius|gracilis|adductor (brevis|longus|magnus|minimus)|poplite|soleus|gastrocnemius|pectineus|piriformis|obturator|gemellus|semitendinosus|semimembranosus|tensor fasciae', name):
        return 'lower_limb'
    if re.search(r'hand|pollic|carpi|brachi|\barm\b|forearm|humer|radius|ulna|scapula|clavicle|shoulder|deltoid|supraspinatus|infraspinatus|subscapularis|teres major|teres minor|anconeus|pronator|supinator', name):
        return 'upper_limb'
    if re.search(r'capit|cervic|\bneck\b|hyoid|thyroid|crico|arytenoid|pharyn|palat|gloss|skull|cran|mandib|maxill|eye|(inferior|superior|medial|lateral) rectus$|(inferior|superior) oblique$|brain|cerebr|cerebell|pons|medulla|temporal|parietal|frontal|occipital|ethmoid|sphenoid|vomer|nasal|nasalis|tongue|\bear\b|retina|lens|optic|vocalis|digastric|platysma|scalen|splenius|sternocleid|uvula|cornea|iris|sclera', name):
        return 'head_neck'
    # Geometric fallback does not pretend to be a clinical region annotation.
    center = [(b['min'][i] + b['max'][i]) / 2 for i in range(3)]
    if center[1] < 0.73 and abs(center[0]) > 0.045:
        return 'lower_limb'
    if center[1] > 1.39:
        return 'head_neck'
    if abs(center[0]) > 0.19:
        return 'upper_limb'
    return 'trunk'


def muscle_layer(name, ancestors):
    # Region-based anatomical teaching groups, never concentric shell indices.
    deep = ['deep muscle', 'deep postvertebral', 'interosse', 'lumbrical',
            'subscapularis', 'pronator quadratus', 'supinator', 'flexor digitorum profundus',
            'flexor pollicis longus', 'tibialis posterior', 'flexor digitorum longus',
            'flexor hallucis longus', 'obturator', 'gemellus', 'quadratus femoris',
            'gluteus minimus', 'popliteus', 'vastus intermedius', 'rotator', 'semispinalis',
            'interspinalis', 'intertransversarius', 'longus colli', 'longus capitis',
            'rectus capitis', 'obliquus capitis', 'psoas', 'iliacus', 'coccygeus',
            'puborectalis', 'pubococcygeus', 'iliococcygeus', 'innermost intercostal',
            'transversus thoracis', 'mylohyoid', 'geniohyoid', 'genioglossus',
            'hyoglossus', 'pharyngeal', 'pharyngeus', 'palatini', 'vocalis',
            'arytenoid', 'cricothyroid', 'uvular', 'rectus', 'oblique']
    superficial = ['pectoralis major', 'deltoid', 'trapezius', 'external oblique',
                   'sternocleidomastoid', 'platysma', 'biceps brachii', 'triceps brachii',
                   'brachioradialis', 'palmaris longus', 'flexor carpi radialis',
                   'extensor carpi radialis longus', 'extensor carpi radialis brevis',
                   'extensor digitorum', 'extensor digiti minimi', 'extensor carpi ulnaris',
                   'gluteus maximus', 'sartorius', 'rectus femoris', 'vastus lateralis',
                   'vastus medialis', 'gracilis', 'adductor longus', 'tensor fasciae latae',
                   'biceps femoris', 'semitendinosus', 'gastrocnemius', 'tibialis anterior',
                   'fibularis longus', 'extensor digitorum longus']
    if any(t in name for t in superficial):
        return 1
    if any(t in name for t in deep):
        return 3
    if any('deep muscle' in a or 'deep postvertebral' in a for a in ancestors):
        return 3
    return 2


def canonical_muscle_name(name):
    n = name.lower().strip('()')
    side = '.l' if re.search(r'\bleft\b', n) or n.endswith('.l') else '.r' if re.search(r'\bright\b', n) or n.endswith('.r') else ''
    n = re.sub(r'\b(left|right)\b|\.[lr]$', '', n)
    n = re.sub(r'\bmuscles?\b', '', n)
    n = n.replace('external abdominal oblique', 'external oblique')
    n = n.replace('clavicular head of pectoralis major', 'clavicular part of pectoralis major')
    n = n.replace('sternocostal head of pectoralis major', 'sternocostal part of pectoralis major')
    n = n.replace('scapular spinal part', 'spinal part')
    n = n.replace('colli', 'cervicis')
    n = re.sub(r'\s+', ' ', n).strip()
    return n + side


def add_z_supplement(selected, lexicon, descriptions):
    from fbx_extract import Fbx, static_meshes
    path = SOURCE / 'MuscularSystem100.fbx'
    meshes = list(static_meshes(Fbx(path)))
    grouped = defaultdict(list)
    for meta, vs, fs in selected:
        if meta.get('registrationSystem',meta.get('geometrySystem',meta['system'])) == 'muscle':
            grouped[canonical_muscle_name(meta.get('geometryNameEn',meta['nameEn']))].append(meta['bounds'])
    pairs = []
    for mesh in meshes:
        key = canonical_muscle_name(mesh['name'])
        if key not in grouped:
            continue
        target = {'min': [min(b['min'][i] for b in grouped[key]) for i in range(3)],
                  'max': [max(b['max'][i] for b in grouped[key]) for i in range(3)]}
        source = bounds(mesh['vertices'])
        delta = [(target['min'][i]+target['max'][i]-source['min'][i]-source['max'][i])/2 for i in range(3)]
        pairs.append({'key': key, 'sourceBoundsMetres': source, 'targetBoundsMetres': target,
                      'centroidDeltaMetres': delta})
    translation = [statistics.median(p['centroidDeltaMetres'][i] for p in pairs) for i in range(3)]
    for p in pairs:
        p['residualMetres'] = math.sqrt(sum((p['centroidDeltaMetres'][i]-translation[i])**2 for i in range(3)))
    # Explicit missing-muscle allowlist. No fascial references, NC organs or already
    # present muscle bellies are imported implicitly from the larger Z atlas.
    needed = ['latissimus dorsi', 'rectus abdominis', 'internal abdominal oblique',
              'transversus abdominis', 'superficial part of masseter', 'deep part of masseter',
              'temporalis', 'frontalis', 'occipitalis', 'temporoparietalis', 'nasalis',
              'bucinator', 'corrugator supercilii', 'depressor anguli oris',
              'depressor labii inferioris', 'depressor septi nasi', 'levator anguli oris',
              'levator labii superioris', 'levator nasolabialis', 'mentalis',
              'orbicularis oris', 'orbital part of orbicularis oculi',
              'palpebral part of orbicularis oculi', 'procerus', 'risorius',
              'zygomaticus major', 'zygomaticus minor', 'superior head of lateral pterygoid',
              'inferior head of lateral pterygoid', 'medial pterygoid',
              'pyramidalis', 'quadratus lumborum', 'multifidus colli',
              'multifidus lumborum', 'multifidus thoracis', 'extensor digitorum brevis',
              'deep head of flexor pollicis brevis']
    added, found = [], set()
    for mesh in meshes:
        n = re.sub(r'\bmuscle\b|\.[lr]$', '', mesh['name'].lower()).strip()
        n = re.sub(r'\s+', ' ', n)
        if n not in needed:
            continue
        found.add(n)
        side = 'left ' if mesh['name'].endswith('.l') else 'right ' if mesh['name'].endswith('.r') else ''
        name = side + n
        vs = [tuple(v[i]+translation[i] for i in range(3)) for v in mesh['vertices']]
        vs, fs = compact(vs, mesh['faces'])
        b = bounds(vs)
        zh, okay = translated_name(name, lexicon)
        if not okay:
            raise ValueError('Missing Chinese supplement label: ' + name)
        deep = any(t in n for t in ['deep part', 'transversus', 'quadratus lumborum', 'multifidus', 'pterygoid', 'deep head'])
        layer = 3 if deep else 2 if 'internal abdominal oblique' in n else 1
        meta = {'id': f'ZA_{mesh["id"]}', 'nameZh': zh, 'nameEn': name,
                'system': 'muscle', 'region': region(name, b), 'layer': layer,
                'nodeName': f'ZA_{mesh["id"]}', 'bounds': b, 'sourceElementId': str(mesh['id']),
                'sourceGeometryId': str(mesh['geometryId']), 'sourceLicense': 'CC-BY-SA-4.0',
                'sourceDataset': 'Z-Anatomy MuscularSystem100.fbx',
                'translationStatus': 'reviewable', 'layerReviewStatus': 'editorial-unreviewed',
                'descriptionZh': f'{zh}。此肌肉由 Z-Anatomy 真实网格补充，使用共同肌肉配准至男性参考模型。',
                'registrationReviewStatus': 'geometric-reference-unreviewed',
                'registrationTranslationMetres': translation,
                'sourceWorldMatrixCentimetres': mesh['fbxMatrixCentimetres']}
        for term, detail in descriptions['muscles'].items():
            if term in name:
                meta.update({k:v for k,v in detail.items() if k.endswith('Zh')})
                meta['descriptionSources'] = detail.get('sources', [])
                break
        selected.append((meta, vs, fs))
        added.append({'id':meta['id'], 'sourceModelName':mesh['name'], 'nameZh':zh,
                      'sourceGeometryId':mesh['geometryId'], 'bounds':b})
    residuals = sorted(p['residualMetres'] for p in pairs)
    audit = {'sourceUrl': 'https://raw.githubusercontent.com/LluisV/Z-Anatomy/PC-Version/Resources/Models/FBX/MuscularSystem100.fbx',
             'sourceSha256': sha256(path), 'sourceBytes':path.stat().st_size,
             'sourceLicense':'CC-BY-SA-4.0', 'fbxUnits':'centimetre',
             'worldToTarget':'FBX hierarchical XYZ Euler / scale / translation; *0.01; robust median translation',
             'translationMetres':translation, 'registrationPairs':pairs,
             'registrationResidualMedianMetres':statistics.median(residuals),
             'registrationResidualP95Metres':residuals[int(.95*(len(residuals)-1))],
             'addedMeshes':added, 'unavailableRequestedMuscles':sorted(set(needed)-found),
             'scopeZh':'仅导入明确缺失的肌肉网格，不导入内耳、肾脏等器官网格。',
             'limitationZh':'共同肌肉包围盒中心的稳健平移配准是几何参考，并非逐点医学配准；需专业视觉审校。'}
    (MANIFEST/'model-supplement.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2)+'\n')
    return audit


def translated_name(name, lexicon):
    # Sides are extracted anywhere, including 'head of right biceps brachii'.
    side = '左侧' if re.search(r'\bleft\b', name) else '右侧' if re.search(r'\bright\b', name) else ''
    n = re.sub(r'\b(left|right)\b', '', name)
    n = re.sub(r'\s+', ' ', n).strip()
    if n in lexicon:
        return side + lexicon[n], True
    text = n
    prefix = ''
    qualifiers = [('long head of ', '长头'), ('short head of ', '短头'),
                  ('lateral head of ', '外侧头'), ('medial head of ', '内侧头'),
                  ('humeral head of ', '肱骨头'), ('ulnar head of ', '尺骨头'),
                  ('superficial head of ', '浅头'), ('oblique head of ', '斜头'),
                  ('transverse head of ', '横头')]
    for en, zh in qualifiers:
        if text.startswith(en):
            text = text[len(en):]
            prefix = zh
            break
    for en, zh in sorted(lexicon.items(), key=lambda x: -len(x[0])):
        text = re.sub(r'\b' + re.escape(en) + r'\b', zh, text)
    pieces = [('ascending part of ', '升部·'), ('descending part of ', '降部·'),
              ('transverse part of ', '横部·'), ('abdominal part of ', '腹部·'),
              ('clavicular part of ', '锁骨部·'), ('sternocostal part of ', '胸肋部·'),
              ('acromial part of ', '肩峰部·'), ('spinal part of ', '肩胛冈部·'),
              ('long head of ', '长头·'), ('short head of ', '短头·'),
              ('lateral head of ', '外侧头·'), ('medial head of ', '内侧头·'),
              ('humeral head of ', '肱骨头·'), ('ulnar head of ', '尺骨头·'),
              ('superficial head of ', '浅头·'), ('oblique head of ', '斜头·'),
              ('transverse head of ', '横头·'), ('set of ', ''),
              ('first ', '第1'), ('second ', '第2'), ('third ', '第3'),
              ('fourth ', '第4'), ('fifth ', '第5'), ('sixth ', '第6'),
              ('seventh ', '第7'), ('eighth ', '第8'), ('ninth ', '第9'),
              ('tenth ', '第10'), ('eleventh ', '第11'), ('twelfth ', '第12'),
              (' of foot', '（足）'), (' of hand', '（手）'), ('muscle', '肌'),
              (' part', '部'), (' of ', '·')]
    for a, b in pieces:
        text = text.replace(a, b)
    text = re.sub(r'\s+', '', text)
    okay = not re.search('[A-Za-z]', text)
    return side + text + ('（' + prefix + '）' if prefix else ''), okay


PALETTES = {'skin': (0.73, .49, .34, 1), 'muscle': (.63, .15, .16, 1),
            'bone': (.88, .82, .65, 1), 'organ': (.57, .29, .29, 1),
            'connective': (.76,.85,.79,1), 'unknown': (.49,.55,.61,1), 'space': (.41,.65,.77,1)}


class Glb:
    def __init__(self, quality):
        self.bin = bytearray()
        self.doc = {'asset': {'version': '2.0', 'generator': 'MeridianAtlas BodyParts3D pipeline'},
                    'scene': 0, 'scenes': [{'nodes': []}], 'nodes': [], 'meshes': [],
                    'accessors': [], 'bufferViews': [], 'materials': [], 'buffers': [],
                    'extras': {'modelId': MODEL_ID, 'quality': quality, 'license': 'CC-BY-4.0',
                               'coordinates': 'metres; Y up; anatomical left +X; anterior +Z'}}
        for system, color in PALETTES.items():
            material = {'name': system, 'doubleSided': True,
                'pbrMetallicRoughness': {'baseColorFactor': color, 'metallicFactor': 0,
                                       'roughnessFactor': .73}}
            if system == 'skin':
                material['alphaMode'] = 'BLEND'
            self.doc['materials'].append(material)
        self.triangles = self.vertices = 0

    def accessor(self, payload, component_type, shape, count, target, limits=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        offset = len(self.bin)
        self.bin.extend(payload)
        vi = len(self.doc['bufferViews'])
        self.doc['bufferViews'].append({'buffer': 0, 'byteOffset': offset,
                                      'byteLength': len(payload), 'target': target})
        a = {'bufferView': vi, 'componentType': component_type, 'count': count, 'type': shape}
        if limits:
            a.update(limits)
        ai = len(self.doc['accessors'])
        self.doc['accessors'].append(a)
        return ai

    def add(self, metadata, vs, fs):
        # Float32 normals keep maximum Filament / glTF loader compatibility.
        pos = array.array('f', (x for v in vs for x in v))
        ns = array.array('f', (x for n in normals(vs, fs) for x in n))
        index_type = 'H' if len(vs) <= 65535 else 'I'
        indices = array.array(index_type, (i for face in fs for i in face))
        if sys.byteorder != 'little':
            pos.byteswap(); ns.byteswap(); indices.byteswap()
        b = bounds(vs)
        p = self.accessor(pos.tobytes(), 5126, 'VEC3', len(vs), 34962, b)
        n = self.accessor(ns.tobytes(), 5126, 'VEC3', len(vs), 34962)
        i = self.accessor(indices.tobytes(), 5123 if index_type == 'H' else 5125,
                          'SCALAR', len(fs) * 3, 34963)
        mid = len(self.doc['meshes'])
        self.doc['meshes'].append({'name': metadata['id'], 'primitives': [{
            'attributes': {'POSITION': p, 'NORMAL': n}, 'indices': i,
            'material': list(PALETTES).index(metadata['system']), 'mode': 4}]})
        self.doc['scenes'][0]['nodes'].append(mid)
        self.doc['nodes'].append({'name': metadata['id'], 'mesh': mid,
                                 'extras': {k: metadata[k] for k in ['id', 'system', 'region', 'layer']}})
        self.triangles += len(fs); self.vertices += len(vs)

    def write(self, path):
        self.doc['buffers'] = [{'byteLength': len(self.bin)}]
        js = json.dumps(self.doc, ensure_ascii=False, separators=(',', ':')).encode()
        js += b' ' * ((-len(js)) % 4)
        self.bin += b'\0' * ((-len(self.bin)) % 4)
        temporary = path.with_suffix('.glb.tmp')
        with temporary.open('wb') as f:
            f.write(struct.pack('<4sII', b'glTF', 2, 12 + 8 + len(js) + 8 + len(self.bin)))
            f.write(struct.pack('<I4s', len(js), b'JSON')); f.write(js)
            f.write(struct.pack('<I4s', len(self.bin), b'BIN\0')); f.write(self.bin)
        temporary.replace(path)


def build(args):
    tables = source_tables()
    names, element_map, inverse = tables
    lexicon = json.loads((HERE / 'chinese-names.json').read_text())
    lexicon.update(json.loads((HERE / 'chinese-names-extra.json').read_text()))
    descriptions = json.loads((HERE / 'muscle-descriptions.json').read_text())
    selected, omitted, translation_gaps, missing = [], [], [], []
    src_manifest = []
    for name in [ARCHIVE, 'isa_parts_list_e.txt', 'isa_element_parts.txt',
                 'isa_inclusion_relation_list.txt', 'partof_parts_list_e.txt',
                 'partof_element_parts.txt', 'partof_inclusion_relation_list.txt',
                 'coordinate_system.png', 'README_e.html', 'license.html']:
        p = SOURCE / name
        src_manifest.append({'file': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size,
                             'sha256': sha256(p), 'url': BASE_URL + name if name != 'license.html'
                             else 'https://dbarchive.biosciencedbc.jp/en/bodyparts3d/lic.html'})
    with zipfile.ZipFile(SOURCE / ARCHIVE) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f'ZIP checksum failure: {bad}')
        members = {Path(n).stem: n for n in archive.namelist() if n.lower().endswith('.obj')}
        skin_file = next(iter(element_map['FMA7163']))
        skin_vertices, _ = load_obj(archive.read(members[skin_file]))
        ground = min(v[2] for v in skin_vertices)
        for file_id in sorted(members):
            candidates = inverse.get(file_id)
            if not candidates:
                omitted.append({'id': file_id, 'reason': 'no official concept mapping'})
                continue
            _, concept, name = preferred_source(file_id,candidates)
            system = classify(file_id, name, tables)
            if not system:
                omitted.append({'id': file_id, 'nameEn': name, 'reason': 'outside requested four systems'})
                continue
            vs, fs = load_obj(archive.read(members[file_id]))
            vs = [(x * .001, (z - ground) * .001, -y * .001) for x, y, z in vs]
            vs, fs = compact(vs, fs)
            b = bounds(vs)
            entry=taxonomy_registry()['entries'][file_id]
            zh, complete = entry['nameZh'], True
            if not complete:
                translation_gaps.append({'id': file_id, 'nameEn': name, 'partialZh': zh})
                zh = f'{PALETTES_LABELS[system]}·{file_id}'
            meta = {'id': file_id, 'nameZh': zh, 'nameEn': name, 'system': system,
                    'region': region(name, b), 'layer': muscle_layer(name, [n for _, _, n in candidates]) if system == 'muscle' else 0,
                    'nodeName': file_id, 'bounds': b, 'sourceConceptId': concept,
                    'sourceDataset': 'BodyParts3D', 'sourceLicense': 'CC-BY-4.0',
                    'sourceElementId': file_id, 'translationStatus': 'reviewable' if complete else 'pending',
                    'geometrySystem':entry['geometrySystem'],'geometryNameEn':entry['geometryNameEn'],
                    'registrationSystem':entry['taxonomyPreviousSystem'],
                    'descriptionZh': f'{zh}，位于{REGION_ZH[region(name, b)]}。本图采用 BodyParts3D 成人男性参考网格。'}
            if system == 'muscle':
                meta['layerBasis'] = 'regional-anatomy-teaching-groups; not concentric shells'
                meta['layerReviewStatus'] = 'editorial-unreviewed'
                for term, detail in descriptions['muscles'].items():
                    if term in name:
                        meta.update({k: v for k, v in detail.items() if k.endswith('Zh')})
                        meta['descriptionSources'] = detail.get('sources', [])
                        break
            selected.append((meta, vs, fs))
        for file_id in sorted(element_map['FMA62955'] - set(members)):
            missing.append({'id': file_id, 'nameEn': inverse[file_id][0][2], 'reason': 'listed element absent from archive'})
    supplement = add_z_supplement(selected, lexicon, descriptions) if (SOURCE/'MuscularSystem100.fbx').exists() else None
    from connective_supplement import meshes as connective_meshes, write_manifest as write_connective_manifest
    connective=connective_meshes(write_obj=True)
    selected.extend(connective)
    write_connective_manifest(connective)
    apply_taxonomy([m for m,_,_ in selected])
    muscle_content = apply_learning([m for m, _, _ in selected])
    model_dir = ASSETS / 'models'
    model_dir.mkdir(parents=True, exist_ok=True)
    results = []
    geometry_selected=[({**m,'system':m.get('geometrySystem',m['system'])},vs,fs) for m,vs,fs in selected]
    muscle_geometry, muscle_simplification = simplify_muscles(geometry_selected, ROOT,
        max(0, args.standard_cell_mm) * .001, args.low_cell_mm * .001)
    (MANIFEST/'model-muscle-simplification.json').write_text(
        json.dumps(muscle_simplification, ensure_ascii=False, indent=2)+'\n')
    for quality in ['standard', 'low']:
        glb = Glb(quality)
        if supplement:
            glb.doc['extras']['license'] = 'CC-BY-SA-4.0'
        unchanged_small = 0
        for index, (meta, vs, fs) in enumerate(selected):
            geometry_system=meta.get('geometrySystem',meta['system'])
            if meta['id'].startswith('ZA_') and meta['system']=='connective':
                pass # Genuine thin fascia/aponeurosis/retinaculum source geometry is retained exactly.
            elif geometry_system == 'muscle':
                vs, fs = muscle_geometry[quality][meta['id']]
            elif quality == 'low':
                if meta['system'] == 'skin':
                    # The skin is a thin surface. Coarse 5 mm position clustering can
                    # merge folds with opposing faces and create visible triangle patches.
                    # Reuse exactly the standard-tier geometry; other low nodes stay 5 mm.
                    if args.standard_cell_mm > 0:
                        vs, fs, changed = cluster(vs, fs, args.standard_cell_mm * .001)
                else:
                    vs, fs, changed = cluster(vs, fs, args.low_cell_mm * .001)
                    unchanged_small += int(not changed)
            elif args.standard_cell_mm > 0:
                vs, fs, changed = cluster(vs, fs, args.standard_cell_mm * .001)
            glb.add(meta, vs, fs)
            if index % 100 == 0:
                print(f'{quality}: {index}/{len(selected)} structures', flush=True)
        output = model_dir / f'body-{quality}.glb'
        glb.write(output)
        results.append({'quality': quality, 'path': str(output.relative_to(ROOT)),
                        'bytes': output.stat().st_size, 'sha256': sha256(output),
                        'structures': len(selected), 'vertices': glb.vertices,
                        'triangles': glb.triangles, 'unchangedSmallMeshes': unchanged_small})
        if quality == 'low':
            results[-1]['geometryExceptions'] = [{'id':m['id'], 'system':'skin',
                'policy':'reuse-standard-tier-geometry', 'cellMm':args.standard_cell_mm,
                'reasonZh':'皮肤薄层避免 5 mm 聚类合并相反朝向的表面。'}
                for m, _, _ in selected if m['system'] == 'skin']
        print(json.dumps(results[-1]), flush=True)
    metadata = {'schemaVersion': 1, 'modelId': MODEL_ID, 'sex': 'male',
                'source': {'name': 'BodyParts3D + Z-Anatomy muscle supplement' if supplement else 'BodyParts3D', 'release': '4.0', 'license': 'CC-BY-SA-4.0' if supplement else 'CC-BY-4.0',
                           'url': 'https://dbarchive.biosciencedbc.jp/en/bodyparts3d/download.html'},
                'coordinateSystem': {'units': 'metre', 'up': '+Y', 'anterior': '+Z',
                                     'anatomicalLeft': '+X', 'sourceUnits': 'millimetre',
                                     'sourceGroundZ': ground,
                                     'transform': '[sourceX/1000,(sourceZ-groundZ)/1000,-sourceY/1000]'},
                'models': results, 'structures': [m for m, _, _ in selected]}
    anatomy_path = ASSETS / 'anatomy.json'
    anatomy_temporary = anatomy_path.with_suffix('.json.tmp')
    anatomy_temporary.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    anatomy_temporary.replace(anatomy_path)
    audit = {'schemaVersion': 1, 'modelId': MODEL_ID, 'inputFiles': src_manifest,
             'outputs': results, 'coordinateSystem': metadata['coordinateSystem'],
             'countsBySystem': dict(__import__('collections').Counter(m['system'] for m, _, _ in selected)),
             'countsByMuscleLayer': dict(__import__('collections').Counter(m['layer'] for m, _, _ in selected if m['system'] == 'muscle')),
             'missingReferencedElements': missing, 'translationGaps': translation_gaps,
             'omittedElements': omitted,
             'bodyParts3dSourceGaps': ['latissimus dorsi', 'rectus abdominis', 'internal oblique',
                                 'transversus abdominis', 'masseter', 'temporalis'],
             'limitationsZh': ['男性参考模型；不代表所有人体变异。',
                 '官方 4.0 缺少的部分常见肌肉由 Z-Anatomy 真实网格补充；没有用程序图元补造。',
                 '肌肉层级是按区域编排的学习分组，尚未经过解剖专业人员逐层审校。',
                 '中文名称及起止点作用供审核，尚未经过专业人员逐项验收。',
                 '肌肉采用保留拓扑的边折叠简化，标准档误差预算1.5 mm、低档5 mm；误差是简化器估计值，未经严格Hausdorff测距。',
                 '骨骼和内脏标准档1.5 mm、低档5 mm顶点聚类；皮肤两档相同。肌肉不再使用会引入开口的位置聚类，微小结构保持原始网格。结构 ID 保持一致。',
                 '血管及周围神经等非请求系统未导出。'],
             'supplementManifest': 'manifests/model-supplement.json' if supplement else None,
             'muscleContentCoverage': muscle_content,
             'build': {'python': sys.version.split()[0], 'lowCellMm': args.low_cell_mm, 'standardCellMm':args.standard_cell_mm,
                       'lowSkinUsesStandardGeometry':True, 'lowSkinCellMm':args.standard_cell_mm,
                       'muscleSimplification':'meshoptimizer 1.3 topology-preserving edge collapse; LockBorder + ErrorAbsolute',
                       'muscleStandardErrorMetres':max(0,args.standard_cell_mm)*.001,
                       'muscleLowErrorMetres':args.low_cell_mm*.001,
                       'dependencies': 'Python standard library; Node.js >=20 and bundled meshoptimizer WASM for muscles'}}
    audit['anatomyMetadata'] = {'path':str(anatomy_path.relative_to(ROOT)), 'sha256':sha256(anatomy_path)}
    audit['pipelineFiles'] = [{'path':str(p.relative_to(ROOT)), 'sha256':sha256(p)}
        for p in [HERE/'build_models.py',HERE/'fbx_extract.py',HERE/'verify_models.py',
                  HERE/'chinese-names.json',HERE/'chinese-names-extra.json',HERE/'muscle-descriptions.json',
                  HERE/'muscle_learning.py',HERE/'muscle-learning.tsv',HERE/'update_muscle_content.py',
                  HERE/'inspect_skin_fix.py',HERE/'muscle_geometry.py',HERE/'simplify_muscles.mjs',
                  HERE/'vendor/meshopt_simplifier.mjs',HERE/'audit_muscle_topology.py',
                  HERE/'taxonomy.py',HERE/'taxonomy.json',HERE/'connective_supplement.py',
                  HERE/'append_fascia_assets.py']]
    audit['licenseFiles'] = [{'path':str(p.relative_to(ROOT)), 'sha256':sha256(p)}
                             for p in sorted((ROOT/'licenses').glob('*')) if p.is_file()]
    MANIFEST.mkdir(exist_ok=True)
    (MANIFEST / 'model-build.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2) + '\n')
    print('DONE', len(selected), 'structures; translation gaps', len(translation_gaps), flush=True)


PALETTES_LABELS = {'skin': '皮肤', 'muscle': '肌肉', 'bone': '骨骼结构', 'organ': '器官结构',
                  'connective':'筋膜·腱韧带','unknown':'待分类','space':'解剖腔隙·参考'}
REGION_ZH = {'whole': '全身', 'head_neck': '头颈部', 'trunk': '躯干',
             'upper_limb': '上肢', 'lower_limb': '下肢'}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--low-cell-mm', type=float, default=5.0)
    p.add_argument('--standard-cell-mm', type=float, default=1.5)
    build(p.parse_args())
