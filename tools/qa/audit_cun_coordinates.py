#!/usr/bin/env python3
"""Read-only, per-constraint audit of every atlas entry whose location uses 寸.

Only requested JSON/CSV reports are written. Numerical agreement with an existing
teaching scale is distinguished from anatomical localization and medical review.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import csv
import difflib
import hashlib
import importlib
import json
from pathlib import Path
import re
import sys
import unicodedata

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
CONTENT = ROOT / 'app/src/main/assets/content'
CODE = ROOT / 'tools/content/anchor_points.py'
NUMERIC_TOLERANCE_MM = .5  # Engineering comparison threshold; not clinical accuracy.
MEASURE = re.compile(r'(?P<lo>\d+(?:\.\d+)?)(?:\s*[~～至–-]\s*(?P<hi>\d+(?:\.\d+)?))?\s*寸')
AMBIGUOUS_MEASURE = re.compile(r'(?P<lo>[lIlL])\s*寸')
CHEST_REGIONS = {'前胸部', '侧胸部', '上腹部', '下腹部', '腹股沟'}
CANONICAL_URL = 'https://www.ntcamsac.ac.cn/upload/std_info/202306192125376309.pdf'


def normal(text):
    return unicodedata.normalize('NFKC', text)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def path_label(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def group(point):
    region = point['region']
    if region in CHEST_REGIONS:
        return '胸腹与腹股沟'
    if region in {'背部', '腰部', '骶部', '脊柱区', '腰区'}:
        return '背腰骶'
    if region in {'头部', '面部', '颈前部', '项部', '面颊部'}:
        return '头面颈项'
    if any(term in region for term in ('臂', '肘', '腕', '手', '肩')):
        return '上肢与肩带'
    return '下肢与臀'


def quantities(point):
    result = []
    for field in ('locationZh', 'anatomyZh'):
        text = normal(point.get(field, ''))
        matches = [(m, False) for m in MEASURE.finditer(text)]
        matches += [(m, True) for m in AMBIGUOUS_MEASURE.finditer(text)]
        for index, (match, ambiguous) in enumerate(sorted(matches, key=lambda item: item[0].start())):
            begin = max(text.rfind(';', 0, match.start()), text.rfind('。', 0, match.start())) + 1
            end = min([p for p in (text.find(';', match.end()), text.find('。', match.end())) if p >= 0] or [len(text)])
            clause = text[begin:end]
            before, after = text[begin:match.start()], text[match.end():end]
            directions = [term for term in ('前后左右各旁开', '两旁各', '侧上方', '侧后方', '内下',
                '旁开', '直上', '直下', '前方', '后方', '外侧', '内侧', '上', '下', '前', '后') if before.endswith(term)]
            direction = directions[0] if directions else 'distance-without-explicit-direction'
            if '入发际' in before:
                direction = '入发际(需先确定发际标志)'
            if '(指寸)' in clause:
                unit = 'explicit-F-cun'
            elif any(term in point['region'] for term in ('指', '趾', '面颊', '面部')):
                unit = 'cun-local-reference-not-specified-in-current-summary'
            else:
                unit = 'B-cun-anatomical-segment-reference-required'
            value = None if ambiguous else float(match['lo'])
            high = None if ambiguous else float(match.groupdict().get('hi') or match['lo'])
            terms = [term for term in ('前正中线', '后正中线', '头正中线', '骶正中嵴', '脐中', '剑突尖',
                '剑胸结合', '胸骨上窝', '腋中线', '腋前纹头', '腋后纹头', '腕掌侧远端横纹',
                '腕背侧远端横纹', '腕背横纹', '肘横纹', '腘横纹', '髌底', '髂前上棘', '内踝尖',
                '外踝尖', '指甲根角', '趾甲根角', '指甲游离缘', '趾甲游离缘', '前发际', '后发际',
                '耳垂', '耳尖', '耳根', '眉', '口角', '第7颈椎棘突', '第6、7颈椎棘突间') if term in clause]
            result.append({'id': f"{point['id']}:{field}:{index}", 'constraintIndex': index, 'field': field,
                'originalFieldText': point.get(field, ''), 'normalizedClause': clause,
                'rawMeasurement': match.group(0), 'valueCun': value, 'maximumCun': high,
                'textEncodingAmbiguity': ambiguous, 'isRange': high is not None and high != value,
                'isApproximate': '约' in before[-5:], 'referenceTerms': terms,
                'referencedCodes': re.findall(r'[A-Z]{2,3}\d+\+?', clause),
                'direction': direction, 'unitType': unit, 'prefix': before, 'suffix': after,
                'instanceReviews': []})
    return result


def canonical_clauses(path):
    """Keep a clause's first location sentence separately from notes and footers."""
    if path is None or not path.exists():
        return {}
    text = normal(path.read_text())
    text = re.sub(r'(?<=\d)\s*\.\s*(?=\d)', '.', text)
    # Some PDF page footers are glued directly to the next point heading.
    headers = list(re.finditer(r'(?m)(?<![\d.])((?:5|7)\.\d+\.\d+)[ \t]+([^\n]+)', text))
    result = {}
    for index, match in enumerate(headers):
        block = text[match.end():headers[index + 1].start() if index + 1 < len(headers) else len(text)]
        # Source text is evidence, not instructions. Ignore page boilerplate.
        location = re.search(r'(在[^。]*。)', block)
        if not location:
            continue
        sentence = re.sub(r'\s+', '', location[1])
        result.setdefault(match[1], {'header': match[2].strip(), 'locationText': sentence,
            'sourceTextLine': text[:match.start()].count('\n') + 1,
            'notes': [re.sub(r'\s+', '', note) for note in re.findall(r'注[:：]([^。]*。)', block)]})
    return result


def standard_diff(point, official, canonical_paths):
    refs = [r for r in point['sourceRefs'] if r['sourceId'] in ('gb12346-2021', 'gb40997-2021')]
    ref = refs[0]
    canonical_path = canonical_paths.get(ref['sourceId'])
    if canonical_path is None and ref['sourceId'] == 'gb40997-2021':
        return {'status': 'official-extra-point-text-not-provided', 'sourceRefs': point['sourceRefs'],
            'reason': 'GB/T40997正式全文尚未作为本轮固定输入；大学副本不能充当正式逐条核对。'}
    clause = ref['clause']
    source = official.get(clause)
    if source is None:
        return {'status': 'canonical-clause-missing', 'clause': clause,
            'reason': '正式固定输入尚无可解析的该条定位句，不能使用大学副本替代。'}
    def compact(text):
        return re.sub(r'[\s,;。:]+', '', normal(text).removeprefix('在'))
    a, b = compact(point['locationZh']), compact(source['locationText'])
    corroboration = []
    if point['id'] == 'st26' and a == compact(re.sub(r'(?<=脐中下)[lI](?=寸)', '1', source['locationText'])):
        note = next((note for note in source.get('notes', []) if 'KI15' in note and 'CV7' in note and '横平' in note), None)
        if note:
            for support_clause in ('5.8.15', '5.14.7'):
                support = official.get(support_clause, {})
                if '脐中下1寸' in support.get('locationText', ''):
                    corroboration.append({'clause': support_clause, 'locationText': support['locationText'],
                        'sourceTextLine': support.get('sourceTextLine'), 'crossReferenceNote': note})
    encoding_only = a != b and len(corroboration) == 2
    status = 'format-equivalent' if a == b else 'encoding-only-one-cun-supported-by-canonical-note' if encoding_only else 'wording-difference-requires-review'
    return {'status': status,
        'clause': clause, 'canonicalFile': path_label(canonical_path),
        'canonicalHeader': source['header'], 'canonicalLocationText': source['locationText'],
        'appLocationText': point['locationZh'], 'equivalentAfterWhitespaceAndPunctuation': a == b,
        'locationContentEquivalentAfterCanonicalNoteCorroboration': a == b or encoding_only,
        'locationContentDifferenceRequiresReview': a != b and not encoding_only,
        'encodingCorroboration': corroboration,
        'numericEncodingAmbiguityRequiresPageImage': bool(AMBIGUOUS_MEASURE.search(normal(source['locationText']))),
        'visualPdfPageAvailableForGlyphReview': False if encoding_only else None,
        'encodingBoundary': '正式文本层原样保留l；同条横平KI15/CV7注释及两参考穴均为脐下1寸，足以核定定位数值。原始PDF未取得，尚未视觉核对字形；这不是定位内容争议。' if encoding_only else None,
        'normalizedDifferences': [{'operation': op, 'app': a[i:j], 'canonical': b[k:l]}
            for op, i, j, k, l in difflib.SequenceMatcher(a=a, b=b).get_opcodes() if op != 'equal']}


def code_lines(needle, code):
    return [{'path': 'tools/content/anchor_points.py', 'line': number, 'text': line.strip()}
        for number, line in enumerate(code.splitlines(), 1) if needle in line]


def scalar_review(instance, value, scale, actual, reference, evidence, reason=None):
    expected = value * scale
    deviation = actual - expected
    return {'instanceId': instance['id'], 'status': 'numeric-deviation' if abs(deviation * 1000) > NUMERIC_TOLERANCE_MM
        else 'numeric-consistent-with-unvalidated-reference', 'metric': 'world-axis-distance',
        'reference': reference, 'modelCunMetres': scale, 'requestedCun': value,
        'expectedDistanceMm': expected * 1000, 'actualDistanceMm': actual * 1000,
        'signedDeviationMm': deviation * 1000, 'actualModelCun': actual / scale,
        'codeEvidence': evidence, 'reason': reason or '只校验该轴上的模型折量数值；参考标志、体表路径和医学定位尚未通过该检查。',
        'independentMeasurement': True,
        'medicalCalibrationCompleted': False}


def independent_model_references(g):
    """Rebuild numerical proxies from source vertices, not generated targets.

    Soft-tissue crease/axillary references are deliberately identified as fixed
    proxies. A copied generation explanation cannot supply a missing landmark.
    """
    result = {}
    for side, sign in (('left', 1), ('right', -1)):
        refs = {}
        for name, bone in (('elbow', 'humerus'), ('wrist', 'radius'),
                           ('ankle-medial', 'tibia'), ('ankle-lateral', 'fibula')):
            fid = g.find(f'{side} {bone}'); v = g.vertices(fid)
            refs[name] = {'position': v[abs(v[:, 1] - v[:, 1].min()) < .012].mean(axis=0).tolist(),
                'sourceStructureIds': [fid], 'extraction': 'Mean of source vertices within 12mm of minimum Y',
                'limitation': '骨网格极值代理；未确认真实横纹或踝尖软组织标志。'}
        fid = g.find(f'{side} patella'); v = g.vertices(fid)
        midpoint = (v.min(axis=0) + v.max(axis=0)) / 2
        for name, y in (('patella-base', v[:, 1].max()), ('patella-tip', v[:, 1].min())):
            refs[name] = {'position': [float(midpoint[0]), float(y), float(midpoint[2])],
                'sourceStructureIds': [fid], 'extraction': 'Actual source patella maximum/minimum Y',
                'limitation': '真实髌骨网格极值；尚未经专业复核髌底内外侧端和体表髌尖。'}
        fid = g.find(f'{side} hip bone'); v = g.vertices(fid)
        q = v[(abs(v[:, 0]) < .02) & (v[:, 2] > .11)]
        refs['pubic-top'] = {'position': [sign * .005, float(q[:, 1].max()), float(q[:, 2].max())],
            'sourceStructureIds': [fid], 'extraction': 'Maximum Y of hip-bone vertices with abs(X)<20mm and Z>110mm',
            'limitation': '耻骨上缘的源骨筛选代理，未专业审校。'}
        refs['axilla'] = {'position': [sign * .165, 1.300, .090],
            'sourceStructureIds': [g.find(f'{side} humerus'), 'FJ2810'],
            'extraction': 'Explicit fixed anterior axillary teaching proxy',
            'limitation': '固定前腋代理；不等于已识别的前/后腋纹头。'}
        # The plane is useful only for a numeric diagnostic; it is not a
        # posterior surface point, and never silently becomes a true crease.
        refs['popliteal-plane'] = {**copy.deepcopy(refs['patella-tip']),
            'extraction': 'Y plane of independently rebuilt patellar minimum Y',
            'limitation': '髌尖同高平面代理；未识别真实腘横纹，不能用作已审校后膝位置。'}
        fid = g.find(f'{side} ulna'); v = g.vertices(fid)
        refs['olecranon-tip'] = {'position': v[np.argmax(v[:, 1])].tolist(),
            'sourceStructureIds': [fid], 'extraction': 'Full source-ulna vertex at maximum Y',
            'limitation': '尺骨最高Y顶点只作鹰嘴尖几何代理，尚未经专业标志审校。'}
        result[side] = refs
    return result


def reference_axis_review(instance, value, proximal, distal, denominator, origin,
                          direction, segment, code, needle):
    unit = (proximal['position'][1] - distal['position'][1]) / denominator
    if unit <= 0:
        raise ValueError(f'Invalid independent segment: {segment}')
    position = np.asarray(instance['anchor']['position'], dtype=float)
    sign = 1 if direction == 'superior' else -1
    actual = sign * (position[1] - origin['position'][1])
    limitations = list(dict.fromkeys(ref['limitation'] for ref in (proximal, distal, origin)))
    result = scalar_review(instance, value, unit, actual,
        {'segment': segment, 'axis': '+Y superior', 'denominatorCun': denominator,
         'proximal': proximal, 'distal': distal, 'origin': origin, 'direction': direction,
         'endpointsIndependentlyRebuiltFromSource': 'pointId' not in proximal and 'pointId' not in distal,
         'currentReferencePointReused': any('pointId' in ref for ref in (proximal, distal, origin))}, code_lines(needle, code),
        '；'.join(limitations) + ' 数值偏差只反映这些明确代理下的世界Y轴关系，不是医学定位误差。')
    result.update(expectedYMetres=origin['position'][1] + sign * value * unit,
        actualYMetres=float(position[1]), landmarkLimitations=limitations,
        engineeringAxisCheck='outside-tolerance' if abs(result['signedDeviationMm']) > NUMERIC_TOLERANCE_MM else 'within-tolerance',
        anatomicalReferenceCheck='provisional-mesh-or-soft-tissue-proxies',
        independentMeasurement=True)
    return result


def fresh_regional_review(point, constraint, instance, g, points, code, references):
    """Current-coordinate checks independent of the frozen regional audit.

    Only explicit standard chains yield millimetre expectations. In particular,
    absent greater-trochanter/gluteal-fold/甲角/hairline references stay absent.
    """
    position = np.asarray(instance['anchor']['position'], dtype=float)
    side = instance['side'] if instance['side'] in references else 'left'
    refs = references[side]; value = constraint['valueCun']; prefix = constraint['prefix']
    clause = constraint['normalizedClause']; area = group(point); pid = point['id']
    actual = {'instanceId': instance['id'], 'actualPositionMetres': position.tolist(),
        'independentMeasurement': True, 'medicalCalibrationCompleted': False,
        'status': 'standard-reference-not-calibrated'}
    def named(point_id):
        item = next((i for i in points[point_id]['instances'] if i['side'] == instance['side']),
                    points[point_id]['instances'][0])
        return {'position': item['anchor']['position'], 'pointId': point_id,
            'extraction': 'Current delivered reference-point position, independently read from acupoints.json',
            'limitation': '引用尚未医学审校的同库参考穴；只可核对相对教学关系。'}
    def measure(proximal, distal, denominator, origin, direction, segment, needle):
        return reference_axis_review(instance, value, refs[proximal], refs[distal], denominator,
            refs[origin] if isinstance(origin, str) else origin, direction, segment, code, needle)
    if '旁开' in prefix and (area == '背腰骶' or pid in {'bl35', 'bl53', 'bl54'}):
        result = scalar_review(instance, value, .026, abs(position[0]),
            {'origin': 'world sagittal plane X=0', 'scale': '26mm teaching transverse constant',
             'missingStandardReference': '肩胛骨内侧缘至后正中线3横寸标定'},
            code_lines('CHESTCUN', code),
            '只核对现有26mm横向常量乘法；未建立标准肩胛内侧缘3横寸尺度、棘突下或骶后孔体表标志。')
        result['status'] = 'arithmetic-only-reference-unvalidated'
        result['independentMeasurement'] = True
        return result
    if '甲' in point['locationZh']:
        actual.update(reason=f"{constraint['rawMeasurement']}所需的甲根角/甲游离缘与个体指寸均未建立；源末节骨端点不能替代甲角，故不编造毫米期望。",
            missingReferences=['nail-root-corner-or-free-margin', 'individual-F-cun'])
        return actual
    if area == '头面颈项':
        named_id = next((q for name, q in (('百会', 'gv20'), ('翳风', 'te17')) if name in prefix), None)
        if named_id:
            ref = named(named_id); delta = position - np.asarray(ref['position'])
            actual.update(reference=ref, deltaFromNamedPointMm=(delta * 1000).tolist(),
                endpointChordMm=float(np.linalg.norm(delta) * 1000))
        if pid in {'ex-hn15', 'extra-xueyadian'}:
            actual['heightAboveC7SpinousMm'] = float((position[1] - g.L['cervical-7-spinous'][1]) * 1000)
        missing = ('前/后发际12纵寸、两额角9横寸及实际头皮测量路径' if '发际' in clause or pid in getattr(g, 'HEADSCALP', {})
            else '口角/目外眦/眉/耳垂等句内局部标志和对应局部量尺' if not named_id
            else f'参考穴{named_id}周围的局部方向量尺与连续体表路径')
        actual.update(reason=f"本约束{constraint['rawMeasurement']}需{missing}；当前只保留真实世界坐标/已知参考差值，不把固定米制点或三维弦长当同身寸。",
            missingReferences=[missing], codeEvidence=code_lines('HEADSCALP' if pid in getattr(g, 'HEADSCALP', {}) else 'HEADFACE', code))
        return actual
    if value is None:
        actual.update(status='source-text-number-ambiguous', reason='数值含字母或待确认字符，不能据此定义毫米期望。')
        return actual
    if '腕' in prefix and re.search(r'上$', prefix):
        return measure('elbow', 'wrist', 12, 'wrist', 'superior', 'forearm-12', 'fc=')
    if '腋前' in prefix and re.search(r'(?:下|直上)$', prefix):
        return measure('axilla', 'elbow', 9, 'axilla', 'inferior' if prefix.endswith('下') else 'superior',
            'anterior-upper-arm-9', 'armcun=')
    if '肘横纹' in prefix and prefix.endswith(('上', '下')):
        superior = prefix.endswith('上')
        return measure('axilla' if superior else 'elbow', 'elbow' if superior else 'wrist', 9 if superior else 12,
            'elbow', 'superior' if superior else 'inferior', 'anterior-upper-arm-9' if superior else 'forearm-12', 'armcun=')
    if pid == 'te9' and prefix.endswith('下'):
        return measure('elbow', 'wrist', 12, 'olecranon-tip', 'inferior', 'forearm-12-from-ulna-olecranon-proxy', 'olecranon_tip')
    if '髌底' in prefix and prefix.endswith('上'):
        return measure('pubic-top', 'patella-base', 18, 'patella-base', 'superior', 'anterior-thigh-18', 'thigh=')
    if '内踝尖上' in prefix or '外踝尖上' in prefix:
        external = '外踝尖上' in prefix
        ankle = 'ankle-lateral' if external else 'ankle-medial'
        return measure('popliteal-plane' if external else 'patella-tip', ankle, 16 if external else 15, ankle,
            'superior', 'lateral-lower-leg-16' if external else 'medial-lower-leg-15', 'ankle-lateral' if external else 'ankle-medial')
    if prefix.endswith('腘横纹下'):
        external = '后' in point['region'] or '外' in point['region']
        return measure('popliteal-plane', 'ankle-lateral' if external else 'ankle-medial', 16 if external else 15,
            'popliteal-plane', 'inferior', 'posterior-lower-leg-16' if external else 'medial-lower-leg-15', 'patella-tip')
    for name, ref_id in (('阴陵泉', 'sp9'), ('太溪', 'ki3'), ('犊鼻', 'st35'), ('昆仑', 'bl60'), ('气冲', 'st30')):
        if name not in prefix:
            continue
        origin = named(ref_id); delta = position - np.asarray(origin['position'])
        actual.update(reference=origin, deltaFromNamedPointMm=(delta * 1000).tolist(),
            endpointChordMm=float(np.linalg.norm(delta) * 1000))
        if prefix.endswith(('上', '下')) and point['region'] != '足内侧':
            external = ref_id in {'st35', 'bl60'}
            if ref_id == 'st30':
                return measure('pubic-top', 'patella-base', 18, origin, 'inferior', 'anterior-thigh-18', 'lr10')
            return measure('popliteal-plane' if external else 'patella-tip', 'ankle-lateral' if external else 'ankle-medial',
                16 if external else 15, origin, 'superior' if prefix.endswith('上') else 'inferior',
                'lateral-lower-leg-16' if external else 'medial-lower-leg-15', ref_id)
        if prefix.endswith('后'):
            actual['posteriorSignedDisplacementMm'] = float(-delta[2] * 1000)
        actual['reason'] = '已重算当前参考穴差值；该处前后/足内侧局部寸尺或体表路径未建立，三维弦长不能直接当标准寸距。'
        actual['missingReferences'] = ['local-direction-cun-scale-and-surface-path']
        return actual
    if pid in {'gb31', 'gb32'}:
        actual.update(reference=refs['popliteal-plane'], heightAbovePoplitealProxyMm=float((position[1] - refs['popliteal-plane']['position'][1]) * 1000),
            reason='标准股外大转子—腘纹19寸链缺少经独立确认的大转子标志；保留腘纹代理上实际高度，禁止借前股18寸给出合格数值。',
            missingReferences=['greater-trochanter', 'reviewed-popliteal-crease'], requiredSegmentCun=19)
        return actual
    if pid in {'bl37', 'bl38'}:
        gluteal = named('bl36')
        gluteal['limitation'] = '保留的BL36仅作臀沟参考穴代理，未独立识别真实臀沟。'
        return reference_axis_review(instance, value, gluteal, refs['popliteal-plane'], 14,
            gluteal if pid == 'bl37' else refs['popliteal-plane'],
            'inferior' if pid == 'bl37' else 'superior', 'posterior-thigh-14-with-BL36-proxy',
            code, "pid in ['bl37','bl38']")
    missing_by_id = {
        'si9': ('posterior-axillary-fold', '腋后纹头和后腋—鹰嘴9寸链'),
        'extra-jianqian': ('anterior-axillary-fold-local-superior-scale', '腋前皱襞尽端和局部上行量尺'),
        'te9': ('olecranon-tip', '源尺骨鹰嘴尖与前臂12寸链，不能用肱骨肘点冒充'),
        'te10': ('olecranon-tip-posterior-upper-arm', '鹰嘴尖和后腋—鹰嘴9寸链'),
        'te11': ('olecranon-tip-posterior-upper-arm', '鹰嘴尖和后腋—鹰嘴9寸链'),
        'te12': ('olecranon-tip-posterior-upper-arm', '鹰嘴尖和后腋—鹰嘴9寸链'),
        'bl37': ('gluteal-fold', '臀沟—腘纹14寸链的真实臀沟标志'),
        'bl38': ('gluteal-fold', '股后臀沟—腘纹14寸链及真实腘纹'),
        'ki5': ('medial-malleolus-sole-scale', '太溪原点与内踝尖—足底3寸局部尺'),
        'ki6': ('medial-malleolus-sole-scale', '内踝尖—足底3寸和内踝下缘凹陷'),
        'ex-ue8': ('metacarpophalangeal-joint-F-cun', '掌指关节标志及0.5指寸局部尺度'),
        'ex-le1': ('local-thigh-transverse-cun', '梁丘两旁横向局部量尺；不能自动复用胸部26mm'),
        'ex-le6': ('fibular-head', '腓骨小头软组织标志及外侧小腿16寸链'),
        'ex-le7': ('lateral-patellar-ligament-depression', '髌韧带外侧凹陷和外侧小腿16寸链'),
    }
    key, missing = missing_by_id.get(pid, ('named-local-reference', '句内指定局部解剖标志与方向量尺'))
    actual.update(reason=f"本约束{constraint['rawMeasurement']}尚需{missing}；未用固定偏移、最近顶点或共用方法名作为符合标准的证据。",
        missingReferences=[key])
    return actual


def inspect_constraint(point, constraint, instance, g, points, code, references=None):
    position = np.asarray(instance['anchor']['position'], dtype=float)
    value, prefix = constraint['valueCun'], constraint['prefix']
    region = point['region']
    if value is None:
        result = {'instanceId': instance['id'], 'status': 'source-text-number-ambiguous',
            'reason': f"{constraint['rawMeasurement']}含字母，现有数字正则不解析；必须核对正式原件并同步条款注释。"}
        if point['id'] == 'st26':
            same_height = [points[pid]['instances'][0]['anchor']['position'][1] for pid in ('ki15', 'cv7')]
            result['existingReferencePoints'] = [{'pointId': pid, 'yMm': y * 1000}
                for pid, y in zip(('ki15', 'cv7'), same_height)]
            result['actualYMm'] = position[1] * 1000
            result['differenceFromKI15AndCV7Mm'] = [(position[1] - y) * 1000 for y in same_height]
        return result
    if ('前正中线' in prefix or '脐中旁开' in prefix) and '旁开' in prefix and region in CHEST_REGIONS | {'颈前部'}:
        result = scalar_review(instance, value, .026, abs(position[0]),
            'Existing chest transverse teaching reference: 104 mm / 4; not professionally measured nipple/other transverse landmarks.',
            code_lines('CHESTCUN=.026', code) + code_lines("(?:前正中线|脐中)旁开", code),
            '检查与现有胸腹横向教学列的一致性；26mm不是已测得的个体骨度寸，不能从屏幕像素或弦长反推。')
        if region in {'下腹部', '腹股沟'}:
            result['generatorLowerAbdominalVerticalScaleMm'] = float(g.ABDCUN * 1000)
            result['axisMixingReview'] = '独立按横向26mm教学列检查，不从脐耻5纵寸导出横尺；是否仍有纵横混用由当前偏差与源码说明分别判断。'
        if point['id'] == 'st16':
            xs = np.linspace(0., position[0], int(abs(position[0]) / .0005) + 1)
            surface_path = np.array([g.project(float(x), float(position[1])) for x in xs])
            result['frontMidlineEndpointMetres'] = surface_path[0].tolist()
            result['frontMidlineEndpointChordMm'] = float(np.linalg.norm(position - surface_path[0]) * 1000)
            result['horizontalSurfacePathApproximationMm'] = float(np.linalg.norm(np.diff(surface_path, axis=0), axis=1).sum() * 1000)
            result['surfacePathSampleSpacingMm'] = .5
            result['metricsAreNotInterchangeable'] = '104mm世界X投影、前正中点三维弦长及皮肤横截折线弧长分别报告；并不等同屏幕像素，也不是已审定的4B-cun。'
        return result
    above = re.search(r'脐中(?:上|下)$', prefix)
    if above or prefix.endswith('脐下'):
        up = prefix.endswith('上')
        scale = float((g.L['xiphoid-tip'][1] - g.L['navel'][1]) / 8 if up else
                      (g.L['navel'][1] - g.L['pubic-top'][1]) / 5)
        actual = (position[1] - g.L['navel'][1]) * (1 if up else -1)
        return scalar_review(instance, value, scale, actual,
            'Navel→xiphoid tip /8' if up else 'Navel→pubic superior reference /5',
            code_lines('ABDCUN=', code) + code_lines("r'脐中([上下])", code),
            '模型真实脐部皮肤特征/骨极值所形成的Y轴折量；尚未核验体表弧长、标准体位和专业标志定位。')
    if prefix.endswith('胸骨上窝下'):
        scale = float((g.L['sternal-notch'][1] - g.L['xiphoid-tip'][1]) / 9)
        return scalar_review(instance, value, scale, float(g.L['sternal-notch'][1] - position[1]),
            'Suprasternal reference→xiphoid tip /9, chest longitudinal reference independent of transverse scale.',
            code_lines('n==21', code), '独立比较胸前9纵寸模型链，不复用胸腹横向常量，也不把数值吻合宣称临床校准。')
    if point['id'] == 'cv15':
        return scalar_review(instance, value, float((g.L['xiphoid-tip'][1] - g.L['navel'][1]) / 8), float(g.L['xiphoid-tip'][1] - position[1]),
            'Xiphoid tip→navel /8; final GB/T2021 tip reference must be checked against canonical clause.',
            code_lines('elif 9<=n<=15', code),
            '正式GB/T2021使用剑突尖下1寸；按真实尖端链计数，库中文字是否一致另列，不按旧版剑胸结合文本移动坐标。')
    if point['id'] == 'gb23':
        evidence = instance['anchor'].get('placementEvidence', {})
        segments = evidence.get('pathSegments', [])
        skin = g.vertices('FJ2810')
        measured = 0.; all_bound = True; reconstructed = []
        for segment in segments:
            triangle = skin[np.asarray(segment['sourceTriangle']) - 1]
            a = np.asarray(segment['startBarycentric']) @ triangle
            b = np.asarray(segment['endBarycentric']) @ triangle
            measured += float(np.linalg.norm(b - a))
            all_bound &= min(segment['startBarycentric'] + segment['endBarycentric']) >= -1e-8
            reconstructed.append((a, b))
        base = next(i['anchor']['position'] for i in points['gb22']['instances'] if i['side'] == instance['side'])
        continuity = max([float(np.linalg.norm(b - c)) for (_, b), (c, _) in zip(reconstructed, reconstructed[1:])] or [0.])
        return {'instanceId': instance['id'], 'status': 'relative-surface-spacing-only-base-not-localized',
            'independentMeasurement': True, 'medicalCalibrationCompleted': False,
            'metric': 'sum-of-source-triangle-path-segments', 'expectedDistanceMm': value * .026 * 1000,
            'actualDistanceMm': measured * 1000 if segments else None,
            'signedDeviationMm': (measured - value * .026) * 1000 if segments else None,
            'endpointChordMm': float(np.linalg.norm(position - base) * 1000),
            'pathSegmentsBoundToSourceTriangles': bool(segments and all_bound),
            'pathContinuityResidualMm': continuity * 1000,
            'baseAlignmentResidualMm': float(np.linalg.norm(reconstructed[0][0] - base) * 1000) if reconstructed else None,
            'endpointAlignmentResidualMm': float(np.linalg.norm(reconstructed[-1][1] - position) * 1000) if reconstructed else None,
            'basePointId': 'gb22', 'basePositionMetres': base,
            'baseHeightAndMidaxillaryLineVerified': False, 'fourthInterspaceHeightCorrected': False,
            'reason': '只复核既有GB22基点到GB23的26mm连续皮肤教学弧长；腋部模型缺口、基点高度及腋中线仍未校，不能计作完整1B-cun医学校准。'}
    if point['id'] == 'gb28':
        return {'instanceId': instance['id'], 'status': 'named-landmark-not-established',
            'independentMeasurement': True,
            'actualPositionMetres': position.tolist(), 'missingReferences': ['anterior-superior-iliac-spine', 'local-inferomedial-cun-scales'],
            'reason': '髂前上棘内下0.5寸需要同侧髂前上棘及内/下两方向局部尺度；当前无ASIS锚点，固定X/Y不能反推该约束。',
            'medicalCalibrationCompleted': False}
    return fresh_regional_review(point, constraint, instance, g, points, code,
        references if references is not None else independent_model_references(g))


def binding_review(instance, g):
    anchor = instance['anchor']; position = np.asarray(anchor['position'])
    surface = anchor.get('sourceSurface')
    result = {'instanceId': instance['id'], 'side': instance['side'], 'ordinal': instance['ordinal'],
        'level': instance.get('level'), 'positionMetres': position.tolist(), 'sourceSurface': surface,
        'sourceVertex': anchor.get('sourceVertex'), 'sourceTriangle': anchor.get('sourceTriangle'),
        'generatorMethodMetadataOnly': anchor.get('method'), 'landmarkRefs': anchor.get('landmarkRefs', []),
        'generationMeasurementExplanation': copy.deepcopy(anchor.get('measurementEvidence')),
        'generationExplanationIsIndependentValidation': False,
        'bindingIsNotAnatomicalLocalization': True}
    if surface:
        vertices = g.vertices(surface)
        if anchor.get('sourceTriangle') and anchor.get('barycentric'):
            triangle = vertices[np.asarray(anchor['sourceTriangle']) - 1]
            weights = np.asarray(anchor['barycentric'])
            result['bindingMetric'] = 'source-triangle-barycentric-reconstruction'
            result['bindingResidualMm'] = float(np.linalg.norm(weights @ triangle - position) * 1000)
            result['validBarycentricWeights'] = bool(weights.min() >= -1e-7 and abs(weights.sum() - 1) < 1e-7)
        elif anchor.get('sourceVertex'):
            result['bindingMetric'] = 'source-vertex-coordinate-difference'
            result['bindingResidualMm'] = float(np.linalg.norm(vertices[anchor['sourceVertex'] - 1] - position) * 1000)
            result['limitation'] = '顶点绑定仅能证明贴于网格，不能证明寸距/解剖位置；缺少三角形路径。'
    return result


def compare(previous, current):
    def flatten(report):
        return {(entry['id'], c['id'], r['instanceId']): r for entry in report['entries']
            for c in entry['constraints'] for r in c['instanceReviews']}
    a, b = flatten(previous), flatten(current); changes = []; numerical = []; statuses = []
    fields = ('metric', 'reference', 'modelCunMetres', 'requestedCun', 'expectedDistanceMm',
              'actualDistanceMm', 'signedDeviationMm', 'actualModelCun', 'actualPositionMetres',
              'endpointChordMm', 'basePositionMetres')
    for key in sorted(a.keys() | b.keys()):
        if a.get(key) != b.get(key):
            changes.append({'pointId': key[0], 'constraintId': key[1], 'instanceId': key[2],
                'before': a.get(key), 'after': b.get(key)})
        before_values = {field: a.get(key, {}).get(field) for field in fields}
        after_values = {field: b.get(key, {}).get(field) for field in fields}
        if before_values != after_values:
            numerical.append({'pointId': key[0], 'constraintId': key[1], 'instanceId': key[2],
                'before': before_values, 'after': after_values})
        if a.get(key, {}).get('status') != b.get(key, {}).get('status'):
            statuses.append({'pointId': key[0], 'constraintId': key[1], 'instanceId': key[2],
                'before': a.get(key, {}).get('status'), 'after': b.get(key, {}).get('status')})
    def positions(report):
        result = {}
        for entry in report['entries'] + report.get('contextReferenceEntries', []):
            for instance in entry['instances']:
                key = instance.get('instanceId', instance.get('id'))
                result[key] = instance.get('positionMetres', instance.get('anchor', {}).get('position'))
        return result
    old_positions, new_positions = positions(previous), positions(current)
    changed_positions = [{'instanceId': key, 'beforeMetres': old_positions.get(key), 'afterMetres': new_positions.get(key)}
        for key in sorted(old_positions.keys() | new_positions.keys()) if old_positions.get(key) != new_positions.get(key)]
    old_text = {e['id']: e['locationZh'] for e in previous['entries'] + previous.get('contextReferenceEntries', [])}
    new_text = {e['id']: e['locationZh'] for e in current['entries'] + current.get('contextReferenceEntries', [])}
    text_changes = [{'pointId': key, 'before': old_text.get(key), 'after': new_text.get(key)}
        for key in sorted(old_text.keys() | new_text.keys()) if old_text.get(key) != new_text.get(key)]
    return {'beforeInputs': previous['inputs'], 'afterInputs': current['inputs'],
        'changedConstraintInstanceReviews': len(changes), 'changes': changes,
        'changedMeasurementReviews': len(numerical), 'measurementChanges': numerical,
        'changedStatuses': len(statuses), 'statusChanges': statuses,
        'changedInstancePositions': len(changed_positions), 'positionChanges': changed_positions,
        'changedLocationTexts': len(text_changes), 'locationTextChanges': text_changes}


def merge_regional(entries, regional, inputs):
    by_id = {p['id']: p for p in regional.get('entries', [])}
    regional_inputs = {item['path']: item['sha256'] for item in regional.get('inputs', [])}
    matches = bool(regional_inputs) and all(regional_inputs.get(key) == inputs[key]
        for key in ('tools/content/anchor_points.py', 'app/src/main/assets/content/acupoints.json',
                    'app/src/main/assets/content/landmarks.json'))
    for entry in entries:
        independent = by_id.get(entry['id'])
        measurements = {(m['constraintIndex'], m['instanceId']): m
            for m in (independent or {}).get('measurements', [])}
        entry['regionalReview'] = independent
        entry['regionalReviewCurrentInputHashesMatch'] = matches if independent else None
        entry['regionalReviewApplicability'] = ('same-fixed-source-and-coordinate-inputs' if matches
            else 'historical-source-findings-do-not-assert-current-code-defects') if independent else None
        positions = {i['instanceId']: i['positionMetres'] for i in entry['instances']}
        same_coordinates = bool(independent) and normal(independent.get('assetLocationZh', '')) == normal(entry['locationZh']) and \
            regional_inputs.get('app/src/main/assets/content/landmarks.json') == inputs['app/src/main/assets/content/landmarks.json'] and \
            all(positions.get(m['instanceId']) == m.get('actual', {}).get('positionM') for m in measurements.values())
        entry['regionalMeasurementsMatchCurrentCoordinatesAndLandmarkFile'] = same_coordinates if independent else None
        for constraint in entry['constraints']:
            for review in constraint['instanceReviews']:
                measurement = measurements.get((constraint['constraintIndex'], review['instanceId']))
                if not measurement:
                    continue
                review['independentRegionalMeasurement'] = measurement
                review['independentRegionalInputHashesMatch'] = matches
                review['independentRegionalCoordinateAndLandmarkInputsMatch'] = same_coordinates
                review['independentRegionalMeasurementIsHistorical'] = not matches
                # A saved coordinate measurement can remain valid after unrelated
                # generator edits. Its old code findings are still explicitly
                # labelled with the original source SHA, never promoted as current.
                if (matches or same_coordinates) and review['status'] == 'independent-regional-measurement-required':
                    review['status'] = measurement['status']
                    review['reason'] = measurement.get('reason', measurement.get('reasonZh'))
                    for key in ('expectedDistanceMm', 'actualDistanceMm', 'signedDeviationMm'):
                        if key in measurement:
                            review[key] = measurement[key]
            if entry['id'] == 'gb28':
                for review in constraint['instanceReviews']:
                    if review['status'] == 'independent-regional-measurement-required':
                        review['status'] = 'named-landmark-not-established'
                        review['reason'] = '髂前上棘内下0.5寸需要同侧髂前上棘的局部标志及内下两方向尺度；当前只记录骨盆整体，未标出ASIS，不能以固定X=.132/Y=耻骨上缘+.025反推该约束。'
            constraint['status'] = sorted({r['status'] for r in constraint['instanceReviews']})


def refresh_review_summary(report):
    entries = report['entries']
    measured = [r for entry in entries for c in entry['constraints'] for r in c['instanceReviews']]
    report['summary']['constraintInstanceStatuses'] = dict(Counter(r['status'] for r in measured))
    report['summary']['standardTextStatuses'] = dict(Counter(e['standardTextDiff']['status'] for e in entries))
    report['summary']['formalMeridianLocationClausesChecked'] = sum(
        e['standardTextDiff']['status'] in {'format-equivalent', 'encoding-only-one-cun-supported-by-canonical-note'}
        for e in entries)
    report['summary']['formalLocationContentDifferences'] = sum(
        e['standardTextDiff'].get('locationContentDifferenceRequiresReview', False) for e in entries)
    report['summary']['independentRegionalReviewsIncluded'] = sum(e.get('regionalReview') is not None for e in entries)
    report['summary']['historicalRegionalSourceReviews'] = sum(e.get('regionalReview') is not None and
        not e.get('regionalReviewCurrentInputHashesMatch') for e in entries)
    report['summary']['freshIndependentConstraintInstanceReviews'] = sum(bool(r.get('independentMeasurement')) for r in measured)
    report['summary']['constraintInstancesWithNumericExpectedAndActual'] = sum(
        isinstance(r.get('expectedDistanceMm'), (int, float)) and isinstance(r.get('actualDistanceMm'), (int, float))
        for r in measured)
    report['summary']['numericDeviationPointIds'] = [e['id'] for e in entries if any(
        r['status'] == 'numeric-deviation' for c in e['constraints'] for r in c['instanceReviews'])]
    report['summary']['unestablishedStandardReferencePointIds'] = [e['id'] for e in entries if any(
        r['status'] in {'standard-reference-not-calibrated', 'named-landmark-not-established'}
        for c in e['constraints'] for r in c['instanceReviews'])]


def millimetres(values):
    values = [float(v) for v in values if isinstance(v, (float, int))]
    if not values:
        return None
    def fmt(value):
        return f'{value:.2f}'.rstrip('0').rstrip('.')
    return fmt(values[0]) if max(values) - min(values) < .005 else f'{fmt(min(values))}～{fmt(max(values))}'


def app_review_zh(entry):
    """Learner-facing content: report each constraint, without source internals."""
    standard = entry['standardTextDiff']
    if standard['status'] == 'official-extra-point-text-not-provided':
        intro = '本条经外奇穴的正式标准全文尚待逐句核对。'
    elif standard['status'] == 'format-equivalent':
        intro = '定位文字已与GB/T 12346—2021正式条文逐句对照。'
    elif standard.get('numericEncodingAmbiguityRequiresPageImage') and entry['id'] == 'st26':
        intro = '定位数字1已按同条横平中注、阴交的注释核对；原网页提取字形l保留为编码记录。'
    else:
        intro = '定位文字与正式条文仍有差异，尚待核定。'
    sentences = []
    side = {'left': '左侧', 'right': '右侧', 'midline': '正中'}
    instances = {i['instanceId']: i for i in entry['instances']}
    for constraint in entry['constraints']:
        reviews = constraint['instanceReviews']
        numeric = [r for r in reviews if isinstance(r.get('expectedDistanceMm'), (int, float)) and
                   isinstance(r.get('actualDistanceMm'), (int, float))]
        label = constraint['normalizedClause'].strip('；;，,。')
        statuses = {r['status'] for r in reviews}
        if 'relative-surface-spacing-only-base-not-localized' in statuses:
            sentences.append(f'{label}：沿现有渊腋基点的连续体表教学弧长为{millimetres([r.get("actualDistanceMm") for r in reviews])}毫米；渊腋的第四肋间高度与腋中线仍待校。')
        elif numeric:
            expected = millimetres([r['expectedDistanceMm'] for r in numeric])
            bad = [r for r in numeric if abs(r.get('signedDeviationMm', 0)) > NUMERIC_TOLERANCE_MM]
            if 'arithmetic-only-reference-unvalidated' in statuses:
                descriptor = '固定26毫米横向教学尺'
                limit = '；标准要求的肩胛骨内侧缘至后正中线3横寸尚未标定'
            elif entry['region'] in CHEST_REGIONS | {'颈前部'} and '旁开' in constraint['prefix']:
                descriptor = '固定26毫米横向教学尺'
                limit = '；尚未独立标定本模型乳头等横向骨度标志'
            else:
                descriptor = '本模型区段标志代理'
                limit = '；骨极值、横纹或参考穴仍待专业标定'
            if bad:
                deviations = '、'.join(f'{side.get(instances[r["instanceId"]].get("side"), "该点")}{r["signedDeviationMm"]:+.2f}毫米'
                    for r in bad[:4])
                if len(bad) > 4:
                    deviations += f'等{len(bad)}处'
                sentences.append(f'{label}：按{descriptor}应为{expected}毫米，当前偏差{deviations}{limit}。')
            else:
                sentences.append(f'{label}：按{descriptor}对应{expected}毫米，现有实例数值一致{limit}。')
        else:
            reasons = list(dict.fromkeys(r.get('reason', r.get('reasonZh', '')) for r in reviews))
            # These explanations contain anatomical limitations, not code paths.
            reason = reasons[0] if reasons else '尚未建立该处标准标志和方向量尺。'
            reason = re.sub(r'本约束[^；;]*?(?:尚需|需)', '尚需', reason)
            sentences.append(f'{label}：{reason.rstrip("。;")}。')
    return intro + ''.join(sentences) + '上述寸距复核只用于本模型教学关系检查，不代表医学定位已校准。'


def generation_explanation_review(instance, independent_review):
    """Describe generated evidence without using it as the standard reference."""
    explanation = copy.deepcopy(instance['anchor'].get('measurementEvidence'))
    if explanation is None:
        return None
    result = {'rawEvidence': explanation, 'usedAsIndependentStandardReference': False,
        'actualPositionMetres': instance['anchor']['position'],
        'limitation': '此处仅说明生成器声明的目标与交付点是否一致；标准端点/分母数值由本审计另行重算。'}
    if isinstance(explanation, dict):
        target = explanation.get('targetY')
        if isinstance(target, (int, float)):
            result['declaredTargetYMetres'] = target
            result['deliveredMinusDeclaredTargetYMm'] = (instance['anchor']['position'][1] - target) * 1000
        expected = independent_review.get('expectedYMetres')
        if expected is not None and isinstance(target, (int, float)):
            result['declaredMinusIndependentlyRecomputedTargetYMm'] = (target - expected) * 1000
        if explanation.get('status') == 'unresolved':
            result['unresolvedGeneratorReferencePreserved'] = True
    return result


def compact_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')) if value is not None else ''


def csv_records(report):
    """One row per textual constraint × actual left/right/multipoint instance."""
    rows = []
    for entry in report['entries']:
        instances = {i['instanceId']: i for i in entry['instances']}
        standard = entry['standardTextDiff']
        for constraint in entry['constraints']:
            for review in constraint['instanceReviews']:
                instance = instances[review['instanceId']]
                rows.append({
                    '条目ID': entry['id'], '中文名': entry['nameZh'], '编号': '/'.join(entry['codes']),
                    '身体分组': entry['group'], '部位': entry['region'], '实例ID': review['instanceId'],
                    '侧别': instance.get('side'), '实例序号': instance.get('ordinal'), '局部层级': instance.get('level'),
                    '描述字段': constraint['field'], '原定位全文': entry['locationZh'], '原解剖全文': entry['anatomyZh'],
                    '约束ID': constraint['id'], '约束原句': constraint['normalizedClause'], '原始寸描述': constraint['rawMeasurement'],
                    '寸数下限': constraint['valueCun'], '寸数上限': constraint['maximumCun'],
                    '方向': constraint['direction'], '单位类型': constraint['unitType'], '约量': constraint['isApproximate'],
                    '参考标志': '/'.join(constraint['referenceTerms']), '参考穴编号': '/'.join(constraint['referencedCodes']),
                    '交付X_mm': instance['positionMetres'][0] * 1000,
                    '交付Y_mm': instance['positionMetres'][1] * 1000,
                    '交付Z_mm': instance['positionMetres'][2] * 1000,
                    '工程检查状态': review['status'], '数值度量': review.get('metric', review.get('measurementKind')),
                    '期望距离_mm': review.get('expectedDistanceMm'), '实际距离_mm': review.get('actualDistanceMm'),
                    '有符号偏差_mm': review.get('signedDeviationMm'), '独立期望Y_m': review.get('expectedYMetres'),
                    '诊断一寸_m': review.get('modelCunMetres'), '实际模型寸': review.get('actualModelCun'),
                    '独立数值检查': review.get('independentMeasurement', False),
                    '标准全文检查状态': standard['status'], '标准条款': standard.get('clause'),
                    '正式定位句': standard.get('canonicalLocationText'), '标准文本差异': compact_json(standard.get('normalizedDifferences')),
                    '数值字符待核': standard.get('numericEncodingAmbiguityRequiresPageImage', False),
                    '检查说明及限制': review.get('reason', review.get('reasonZh')),
                    '应用内寸距复核说明': entry.get('appReviewZh'),
                    '缺失标志': compact_json(review.get('missingReferences', [])),
                    '参考端点和公式': compact_json(review.get('reference')),
                    '源代码证据': compact_json(review.get('codeEvidence', review.get('sourceCodeReferences', []))),
                    '源网格': instance.get('sourceSurface'), '三角形': compact_json(instance.get('sourceTriangle')),
                    '绑定残差_mm': instance.get('bindingResidualMm'),
                    '生成器声明': compact_json(review.get('generationMeasurementExplanation')),
                    '历史区域报告': entry.get('regionalReviewApplicability'),
                    '医学定位审校完成': False,
                })
    return rows


def write_csv(path, records):
    if not records:
        raise ValueError('Refusing to write an empty cun audit CSV')
    path = path.resolve(); path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(records[0]), lineterminator="\n")
        writer.writeheader(); writer.writerows(records)


def export_csvs(report, detail_path=None, entries_path=None):
    if detail_path:
        write_csv(detail_path, csv_records(report))
    if entries_path:
        rows = []
        for entry in report['entries']:
            results = [r for c in entry['constraints'] for r in c['instanceReviews']]
            rows.append({'条目ID': entry['id'], '中文名': entry['nameZh'], '编号': '/'.join(entry['codes']),
                '身体分组': entry['group'], '部位': entry['region'], '原定位全文': entry['locationZh'],
                '原解剖全文': entry['anatomyZh'], '实例数': len(entry['instances']),
                '寸约束数': len(entry['constraints']), '约束实例检查数': len(results),
                '工程检查状态计数': compact_json(dict(Counter(r['status'] for r in results))),
                '标准全文检查状态': entry['standardTextDiff']['status'],
                '正式定位句': entry['standardTextDiff'].get('canonicalLocationText'),
                '应用内寸距复核说明': entry.get('appReviewZh'),
                '逐约束结果': compact_json([{'constraintId': c['id'], 'raw': c['rawMeasurement'],
                    'direction': c['direction'], 'unitType': c['unitType'],
                    'instances': [{k: r.get(k) for k in ('instanceId', 'status', 'expectedDistanceMm',
                        'actualDistanceMm', 'signedDeviationMm', 'reason', 'missingReferences')}
                        for r in c['instanceReviews']]} for c in entry['constraints']]),
                '历史区域报告': entry.get('regionalReviewApplicability'), '医学定位审校完成': False})
        write_csv(entries_path, rows)


def standard_input_records(paths):
    result = []
    for source_id, path in paths.items():
        if path is None:
            continue
        metadata_path = path.with_suffix('.json')
        metadata = json.loads(metadata_path.read_text()) if metadata_path.exists() else {}
        pdf_path = path.with_suffix('.pdf')
        original_pdf = pdf_path.exists() and pdf_path.read_bytes().startswith(b'%PDF')
        result.append({'sourceId': source_id, 'textPath': path_label(path), 'textSha256': sha(path),
            'captureMethod': metadata.get('method', 'designated fixed text input'),
            'sourceUrl': metadata.get('sourceUrl'), 'originalPdfDownloaded': original_pdf,
            'originalPdfSha256': sha(pdf_path) if original_pdf else None,
            'textHashIsNotPdfHash': True,
            'captureEvidencePath': path_label(metadata_path) if metadata else None,
            'captureEvidenceSha256': sha(metadata_path) if metadata else None})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'manifests/cun-coordinate-audit.json')
    parser.add_argument('--canonical-text', type=Path)
    parser.add_argument('--canonical-extra-text', type=Path)
    parser.add_argument('--regional', type=Path, default=ROOT / 'manifests/cun-regional-audit.json')
    parser.add_argument('--compare', type=Path, help='Read an earlier audit; include exact per-constraint before/after differences.')
    parser.add_argument('--csv', type=Path, help='Write one UTF-8 CSV row per constraint × instance, including numerical evidence and limitations.')
    parser.add_argument('--entries-csv', type=Path, help='Write a 226-entry overview CSV, retaining all nested constraint results.')
    parser.add_argument('--export-existing', type=Path,
        help='Only export CSV files from this fixed audit JSON; do not recalculate or change the input report.')
    parser.add_argument('--supplement-only', action='store_true',
        help='Attach fixed standard text/regional reviews to existing report without rereading or recalculating changed geometry inputs.')
    args = parser.parse_args()
    if args.export_existing:
        if not args.csv and not args.entries_csv:
            parser.error('--export-existing requires --csv and/or --entries-csv')
        source = args.export_existing.resolve()
        if any(p and p.resolve() == source for p in (args.csv, args.entries_csv)):
            parser.error('CSV output must not overwrite its input report')
        report = json.loads(source.read_text())
        export_csvs(report, args.csv, args.entries_csv)
        print(json.dumps({'sourceReport': str(source), 'sourceReportSha256': sha(source),
            'entries': len(report['entries']), 'constraintInstanceRows': len(csv_records(report)),
            'medicalCalibrationCompleted': False}, ensure_ascii=False, indent=2))
        return
    canonical = args.canonical_text.resolve() if args.canonical_text else None
    canonical_extra = args.canonical_extra_text.resolve() if args.canonical_extra_text else None
    args.regional = args.regional.resolve()
    args.output = args.output.resolve()
    if any(path and 'university' in path.name for path in (canonical, canonical_extra)):
        parser.error('The university copy cannot be designated canonical.')
    if args.supplement_only:
        report = json.loads(args.output.read_text())
        regional = json.loads(args.regional.read_text()) if args.regional.exists() else {}
        official = canonical_clauses(canonical) | canonical_clauses(canonical_extra)
        paths = {'gb12346-2021': canonical, 'gb40997-2021': canonical_extra}
        for entry in report['entries']:
            entry['standardTextDiff'] = standard_diff(entry, official, paths)
        for entry in report.get('contextReferenceEntries', []):
            channel = {'cv': '5.14', 'st': '5.3', 'gb': '5.11'}
            prefix = re.match(r'[a-z]+', entry['id'])[0]
            number = re.search(r'\d+', entry['id'])[0]
            entry['sourceRefs'] = [{'sourceId': 'gb12346-2021', 'clause': f'{channel[prefix]}.{number}'}]
            entry['standardTextDiff'] = standard_diff(entry, official, paths)
        merge_regional(report['entries'], regional, report['inputs'])
        refresh_review_summary(report)
        for entry in report['entries']:
            entry['appReviewZh'] = app_review_zh(entry)
        supplement_files = [p for p in (canonical, canonical_extra, args.regional) if p and p.exists()]
        report['supplementalInputs'] = {path_label(p): sha(p) for p in supplement_files}
        report['canonicalStandardStatus'] = 'fixed-canonical-web-text-compared' if canonical else 'canonical-file-not-yet-provided'
        report['canonicalExtraStandardStatus'] = 'fixed-canonical-web-text-compared' if canonical_extra else 'canonical-file-not-yet-provided'
        report['geometryRecomputedBySupplement'] = False
        report['supplementToolSha256'] = sha(Path(__file__).resolve())
        report['standardInputRecords'] = standard_input_records(paths)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        export_csvs(report, args.csv, args.entries_csv)
        print(json.dumps(report['summary'], ensure_ascii=False, indent=2))
        return
    files = [Path(__file__).resolve(), CONTENT / 'acupoints.json', CONTENT / 'landmarks.json', ROOT / 'app/src/main/assets/anatomy.json',
        CODE, ROOT / 'tools/content/chest_anchors.py', ROOT / 'tools/content/side_chest_anchors.py',
        ROOT / 'tools/assets/source/FJ2810.obj', ROOT / 'tools/assets/source/isa_BP3D_4.0_obj_99.zip']
    files.extend(p for p in (ROOT / 'tools/content/cun_units.py', ROOT / 'tools/content/cun_surface.py',
                            ROOT / 'tools/content/limb_cun.py') if p.exists())
    for standard_path in (canonical, canonical_extra):
        if standard_path:
            files.append(standard_path)
            if standard_path.with_suffix('.pdf').exists():
                files.append(standard_path.with_suffix('.pdf'))
            if standard_path.with_suffix('.json').exists():
                files.append(standard_path.with_suffix('.json'))
    if args.regional.exists():
        files.append(args.regional)
    before_hashes = {path_label(p): sha(p) for p in files}
    raw = json.loads((CONTENT / 'acupoints.json').read_text()); points = {p['id']: p for p in raw['points']}
    sys.path.insert(0, str(ROOT / 'tools/content'))
    g = importlib.import_module('anchor_points')
    independent_references = independent_model_references(g)
    code = CODE.read_text(); official = canonical_clauses(canonical) | canonical_clauses(canonical_extra)
    canonical_paths = {'gb12346-2021': canonical, 'gb40997-2021': canonical_extra}
    regional = json.loads(args.regional.read_text()) if args.regional.exists() else {}
    entries = []
    for point in raw['points']:
        if not any('寸' in point.get(field, '') for field in ('locationZh', 'anatomyZh')):
            continue
        constraints = quantities(point)
        for constraint in constraints:
            constraint['instanceReviews'] = []
            for instance in point['instances']:
                review = inspect_constraint(point, constraint, instance, g, points, code, independent_references)
                review['generationMeasurementExplanation'] = generation_explanation_review(instance, review)
                constraint['instanceReviews'].append(review)
            constraint['status'] = sorted({r['status'] for r in constraint['instanceReviews']})
        entries.append({'id': point['id'], 'nameZh': point['nameZh'], 'codes': point['codes'],
            'region': point['region'], 'group': group(point), 'locationZh': point['locationZh'],
            'anatomyZh': point.get('anatomyZh', ''), 'sourceRefs': point['sourceRefs'],
            'standardTextDiff': standard_diff(point, official, canonical_paths),
            'constraints': constraints, 'instances': [binding_review(i, g) for i in point['instances']],
            'medicalCalibrationCompleted': False})
    merge_regional(entries, regional, before_hashes)
    after_hashes = {path_label(p): sha(p) for p in files}
    if before_hashes != after_hashes:
        raise RuntimeError('An audit input changed during measurement; no mixed-input report will be written')
    measured = [r for entry in entries for c in entry['constraints'] for r in c['instanceReviews']]
    result = {'schemaVersion': 2, 'purpose': 'Every cun-bearing entry, every textual measurement, every instance; source and geometric review, not medical calibration.',
        'inputs': before_hashes, 'canonicalStandardUrl': CANONICAL_URL,
        'standardInputRecords': standard_input_records(canonical_paths),
        'canonicalStandardStatus': 'fixed-canonical-file-compared' if canonical else 'canonical-file-not-yet-provided',
        'canonicalExtraStandardStatus': 'fixed-canonical-file-compared' if canonical_extra else 'canonical-file-not-yet-provided',
        'numericComparisonToleranceMm': NUMERIC_TOLERANCE_MM,
        'numericToleranceIsMedicalAccuracyClaim': False,
        'independentModelReferenceProxies': independent_references,
        'historicalRegionalReviewBoundary': 'Frozen regional source findings retain their original input hashes. After-input checks are recomputed independently; historical findings never assert that a current defect persists.',
        'summary': {'allEntries': len(points), 'locationCunEntries': sum('寸' in p.get('locationZh', '') for p in points.values()),
            'anatomyCunEntries': sum('寸' in p.get('anatomyZh', '') for p in points.values()),
            'auditedEntries': len(entries), 'auditedInstances': sum(len(e['instances']) for e in entries),
            'textMeasurementConstraints': sum(len(e['constraints']) for e in entries), 'constraintInstanceReviews': len(measured),
            'byGroupEntries': dict(Counter(e['group'] for e in entries)),
            'constraintInstanceStatuses': dict(Counter(r['status'] for r in measured)),
            'standardTextStatuses': dict(Counter(e['standardTextDiff']['status'] for e in entries)),
            'independentRegionalReviewsIncluded': len([e for e in entries if e['regionalReview'] is not None]),
            'medicallyCalibratedEntries': 0},
        'modelScaleReferencesMm': {'chestTransverseUncalibratedConstant': float(g.CHESTCUN * 1000),
            'lowerAbdominalVertical5': float(g.ABDCUN * 1000), 'upperAbdominalVertical8': float(g.UPPERCUN * 1000),
            'chestVertical9': float((g.L['sternal-notch'][1] - g.L['xiphoid-tip'][1]) / 9 * 1000)},
        'scaleReferenceLimitations': {
            'chestTransverse': '未独立标注并专业确认两乳头/其他横向标志；ST17本身也由同一26mm常量生成，不能循环论证该常量已测得8横寸。',
            'abdominalVertical': '脐为源皮肤特征代理，耻骨/剑突为骨网格极值；Y轴长度折量不自动等于标准体位下的体表量取。',
            'backTransverse': '未独立标注肩胛骨内侧缘到后正中线3横寸；胸部常量不得自动视为背腰实测。',
            'head': '缺少经专业确认的前后发际、眉/口角/耳垂标志和表面测量路径。',
            'limbs': '实际骨极值、髌底与髌尖、软组织横纹、内外踝和前后股段必须分别标定。'},
        'contextReferenceEntries': [{**{k: points[pid][k] for k in ('id', 'nameZh', 'locationZh', 'instances')},
            'standardTextDiff': standard_diff(points[pid], official, canonical_paths)}
            for pid in ('cv2', 'cv8', 'cv16', 'cv22', 'st17', 'gb22')],
        'entries': entries}
    refresh_review_summary(result)
    for entry in result['entries']:
        entry['appReviewZh'] = app_review_zh(entry)
    assert len(entries) == sum(any('寸' in p.get(f, '') for f in ('locationZh', 'anatomyZh')) for p in points.values())
    assert all(e['constraints'] and all(len(c['instanceReviews']) == len(e['instances']) for c in e['constraints']) for e in entries)
    if args.compare:
        result['comparison'] = compare(json.loads(args.compare.read_text()), result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    export_csvs(result, args.csv, args.entries_csv)
    print(json.dumps(result['summary'], ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
