"""Relative GB23 spacing on the source skin, without moving the unreviewed GB22.

The exact horizontal mesh contour below is a teaching measurement, not a new
fourth-interspace or midaxillary localization. The source skin near the expected
upper axilla merges into the arm; this module must not hide that limitation by
moving the GB22 reference forward or binding it to an upper-arm triangle.
"""
from __future__ import annotations

from collections import defaultdict
import numpy as np

POINT_IDS = frozenset({'gb23'})
TEACHING_CUN_METRES = .104 / 4
EPS = 1e-10
LIMITATION_ZH = ('腋部模型受限，渊腋基点的高度及腋中线位置仍待专业校对；'
                 '辄筋仅沿现有基点所在的真实皮肤横截曲线校正相对教学间距，'
                 '没有校正第四肋间高度，不能作为个体取穴坐标。')


def section_points(vertices, faces, axis, value):
    """Exact source-triangle / coordinate-plane intersections; no slab median."""
    triangles = np.asarray(vertices, dtype=float)[np.asarray(faces, dtype=int)]
    signed = triangles[:, :, axis] - value
    points = []
    for i, j in ((0, 1), (1, 2), (2, 0)):
        a, b = triangles[:, i], triangles[:, j]
        da, db = signed[:, i], signed[:, j]
        crosses = (da * db <= 0) & (abs(db - da) > EPS)
        t = -da[crosses] / (db[crosses] - da[crosses])
        points.append(a[crosses] + t[:, None] * (b[crosses] - a[crosses]))
    result = np.concatenate(points)
    if not len(result):
        raise ValueError('Source mesh does not intersect the requested reference plane')
    return result


def trace_contour(vertices, faces, start_vertex, distance, lateral_sign=1,
                  lateral_limit=None):
    """Walk one continuous source-skin section in anterior (+Z) direction.

    Connectivity is keyed by original vertex/edge IDs, not spatial proximity.
    Consequently touching arm geometry, a nearby duplicate skin sheet, and
    disconnected triangles cannot be substituted for the current skin contour.
    Each recorded path segment lies within one original triangle, so its summed
    lengths are a surface path length rather than the endpoint chord length.
    """
    vertices, faces = np.asarray(vertices, dtype=float), np.asarray(faces, dtype=int)
    if not 0 <= start_vertex < len(vertices) or distance <= 0:
        raise ValueError('Invalid source vertex or requested surface distance')
    start = vertices[start_vertex]
    y = float(start[1])
    triangles = vertices[faces]
    candidates = np.flatnonzero((triangles[:, :, 1].min(1) <= y + EPS) &
                               (triangles[:, :, 1].max(1) >= y - EPS))
    segments, adjacent = [], defaultdict(list)
    for face_index in candidates:
        triangle = triangles[face_index]
        indices = faces[face_index]
        signed = triangle[:, 1] - y
        hits = {}
        for i, j in ((0, 1), (1, 2), (2, 0)):
            for k in (i, j):
                if abs(signed[k]) < EPS:
                    weights = np.zeros(3); weights[k] = 1.
                    hits[(0, int(indices[k]))] = (triangle[k], weights)
            if signed[i] * signed[j] < 0:
                t = -signed[i] / (signed[j] - signed[i])
                weights = np.zeros(3); weights[i], weights[j] = 1 - t, t
                key = (1, *sorted((int(indices[i]), int(indices[j]))))
                hits[key] = (weights @ triangle, weights)
        if len(hits) != 2:
            continue  # A tangent vertex has zero contour length.
        (a, av), (b, bv) = hits.items()
        length = float(np.linalg.norm(bv[0] - av[0]))
        if length < EPS:
            continue
        segment = {'keys': (a, b), 'positions': (av[0], bv[0]),
                   'weights': (av[1], bv[1]), 'faceIndex': int(face_index), 'length': length}
        index = len(segments)
        segments.append(segment); adjacent[a].append(index); adjacent[b].append(index)

    current = (0, int(start_vertex))
    if current not in adjacent:
        raise ValueError('GB22 source vertex has no connected horizontal skin contour')
    travelled, used, path, bindings = 0., set(), [start.copy()], []
    while travelled < distance - EPS:
        possible = []
        for index in adjacent[current]:
            if index in used:
                continue
            segment = segments[index]
            which = segment['keys'].index(current)
            destination = segment['positions'][1 - which]
            if not used:
                # At a tiny source-skin concavity both neighbors can have +Z.
                # The anterior torso direction also turns medially, whereas
                # the other neighbor heads laterally toward the arm contour.
                if destination[2] <= start[2] + EPS or \
                        lateral_sign * destination[0] > lateral_sign * start[0] + EPS:
                    continue
            possible.append((index, which))
        if len(possible) != 1:
            raise ValueError('Skin contour ended or branched; refusing an arbitrary/nearest jump')
        index, which = possible[0]
        used.add(index); segment = segments[index]
        a, b = segment['positions'][which], segment['positions'][1 - which]
        wa, wb = segment['weights'][which], segment['weights'][1 - which]
        if b[2] < a[2] - EPS:
            raise ValueError('Skin contour turned posteriorly instead of following the anterior chest')
        portion = min(1., (distance - travelled) / segment['length'])
        end = a + portion * (b - a)
        if lateral_limit is not None and lateral_sign * end[0] >= lateral_limit:
            raise ValueError('Skin path reaches the upper-arm side of the humeral reference')
        length = float(np.linalg.norm(end - a))
        binding = {'sourceTriangle': (faces[segment['faceIndex']] + 1).tolist(),
                   'startBarycentric': wa.tolist(),
                   'endBarycentric': (wa + portion * (wb - wa)).tolist(),
                   'lengthMetres': length}
        bindings.append(binding); path.append(end)
        travelled += length
        current = segment['keys'][1 - which]
        if len(used) > len(segments):
            raise ValueError('Skin contour did not reach the requested distance')
    last = bindings[-1]
    triangle = vertices[np.asarray(last['sourceTriangle']) - 1]
    normal = np.cross(triangle[1] - triangle[0], triangle[2] - triangle[0])
    normal /= max(float(np.linalg.norm(normal)), 1e-12)
    radial = path[-1] - np.array([0., y, .085])
    if np.dot(normal, radial) < 0:
        normal = -normal
    return {'position': path[-1], 'sourceTriangle': last['sourceTriangle'],
            'barycentric': last['endBarycentric'], 'normal': normal,
            'points': np.asarray(path), 'segments': bindings, 'arcLengthMetres': travelled}


def locate(side, base_anchor, mesh_for):
    """Return GB23 and auditable evidence; ``mesh_for`` returns id/vertices/faces."""
    if side not in ('left', 'right'):
        raise ValueError(side)
    sign = 1 if side == 'left' else -1
    skin_id, vertices, faces = mesh_for('skin')
    fourth_id, fourth, fourth_faces = mesh_for(f'{side} fourth rib')
    fifth_id, fifth, fifth_faces = mesh_for(f'{side} fifth rib')
    arm_id, humerus, humerus_faces = mesh_for(f'{side} humerus')
    vertices, fourth, fifth = (np.asarray(v, dtype=float) for v in (vertices, fourth, fifth))
    base = np.asarray(base_anchor['position'], dtype=float)
    source_vertex = int(base_anchor['sourceVertex']) - 1
    if np.linalg.norm(vertices[source_vertex] - base) > 1e-7:
        raise ValueError('Existing GB22 is no longer bound to its recorded source vertex')
    arm_section = section_points(humerus, humerus_faces, 1, float(base[1]))
    lateral_limit = float((sign * arm_section[:, 0]).min())
    if sign * base[0] >= lateral_limit:
        raise ValueError('Existing GB22 is not on the torso side of the local upper arm')
    result = trace_contour(vertices, faces, source_vertex, TEACHING_CUN_METRES,
                           sign, lateral_limit)

    lateral_fourth = fourth[np.argmax(sign * fourth[:, 0])]
    lateral_fifth = fifth[np.argmax(sign * fifth[:, 0])]
    reference_z = float((lateral_fourth[2] + lateral_fifth[2]) / 2)
    four_section = section_points(fourth, fourth_faces, 2, reference_z)
    five_section = section_points(fifth, fifth_faces, 2, reference_z)
    four_section = four_section[sign * four_section[:, 0] > 0]
    five_section = five_section[sign * five_section[:, 0] > 0]
    if not len(four_section) or not len(five_section):
        raise ValueError('Missing side-specific fourth/fifth-rib reference section')
    lower_fourth = four_section[np.argmin(four_section[:, 1])]
    upper_fifth = five_section[np.argmax(five_section[:, 1])]
    if not .003 < lower_fourth[1] - upper_fifth[1] < .050:
        raise ValueError('Cannot identify the local projected fourth interspace')
    expected_y = float((lower_fourth[1] + upper_fifth[1]) / 2)
    evidence = {
        'construction': 'existing-gb22-horizontal-skin-contour-relative-spacing-only',
        'reviewStatus': 'provisional-pending-professional-localization-review',
        'basePointId': 'gb22', 'basePositionMetres': base.tolist(),
        'baseSourceVertex': source_vertex + 1, 'basePositionChanged': False,
        'baseHeightAndMidaxillaryLineVerified': False,
        'fourthInterspaceHeightCorrected': False,
        'teachingCunMetres': TEACHING_CUN_METRES,
        'teachingCunBasis': 'Existing ST16 chest teaching column |X|=0.104 m / 4; model-specific teaching reference, not a measured or medically calibrated B-cun.',
        'measurement': 'Anterior (+Z) traversal of one continuous horizontal source-skin triangle contour at the unchanged GB22 height; sum of in-triangle segments, not endpoint chord or screen pixels.',
        'pathArcLengthMetres': result['arcLengthMetres'],
        'pathChordLengthMetres': float(np.linalg.norm(result['position'] - base)),
        'pathPointsMetres': result['points'].tolist(), 'pathSegments': result['segments'],
        'upperArmMedialReferenceXMetres': sign * lateral_limit,
        'sourceStructureIds': [skin_id, fourth_id, fifth_id, arm_id],
        'standardLocationReferences': [
            {'sourceId': 'gb12346-2021', 'clause': '5.11.22'},
            {'sourceId': 'gb12346-2021', 'clause': '5.11.23'},
            {'sourceId': 'WHO-2008-point-locations', 'printedPages': [182, 183]}],
        'fourthInterspaceGeometryReference': {
            'construction': 'Vertical Z-plane through mean Z of the lateral fourth/fifth-rib extrema; diagnostic only, not applied to GB22 or GB23.',
            'fourthRibLateralPointMetres': lateral_fourth.tolist(),
            'fifthRibLateralPointMetres': lateral_fifth.tolist(),
            'referenceZMetres': reference_z,
            'fourthRibLowerSectionPointMetres': lower_fourth.tolist(),
            'fifthRibUpperSectionPointMetres': upper_fifth.tolist(),
            'projectedInterspaceMidYMetres': expected_y,
            'baseYMinusReferenceMetres': float(base[1] - expected_y)},
        'limitationZh': LIMITATION_ZH,
    }
    return result, evidence
