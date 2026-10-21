"""Model-specific, unreviewed upper-chest references for ST12--ST14.

These references repair a region-dispatch error; they are not a clinical point
localization standard.  The existing 104 mm chest column is retained so this
local repair does not redefine the other stomach-channel chest points.
"""
from __future__ import annotations

import numpy as np

POINT_IDS = frozenset({'st12', 'st13', 'st14'})
COLUMN_METRES = .104
SCAN_STEP_METRES = .0005


def plane_section(triangles, x):
    """Intersect source triangles with an exact sagittal plane, without a slab."""
    triangles = np.asarray(triangles, dtype=float)
    hits = []
    for i, j in ((0, 1), (1, 2), (2, 0)):
        a, b = triangles[:, i], triangles[:, j]
        dx = b[:, 0] - a[:, 0]
        crosses = ((a[:, 0] - x) * (b[:, 0] - x) <= 0) & (abs(dx) > 1e-12)
        t = (x - a[crosses, 0]) / dx[crosses]
        hits.append(a[crosses] + t[:, None] * (b[crosses] - a[crosses]))
    result = np.concatenate(hits)
    if len(result) < 3:
        raise ValueError('Upper-chest column does not intersect the source clavicle')
    return result


def ray_hits(triangles, origin, direction):
    """Two-sided source-triangle intersection on the outward half-ray."""
    triangles = np.asarray(triangles, dtype=float)
    origin, direction = np.asarray(origin), np.asarray(direction)
    e1, e2 = triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0]
    h = np.cross(direction, e2)
    det = np.einsum('ij,ij->i', e1, h)
    valid = abs(det) > 1e-12
    inv = np.zeros_like(det)
    inv[valid] = 1 / det[valid]
    delta = origin - triangles[:, 0]
    u = inv * np.einsum('ij,ij->i', delta, h)
    q = np.cross(delta, e1)
    v = inv * (q @ direction)
    distance = inv * np.einsum('ij,ij->i', e2, q)
    return bool(np.any(valid & (u >= -1e-9) & (v >= -1e-9) &
                       (u + v <= 1 + 1e-9) & (distance > 0)))


def locate(side, triangles_for, surface):
    """Return skin points and their auditable source-geometry construction.

    ``surface(x, y, face)`` must intersect the skin along Z and preserve X/Y.
    The first interspace is a radial chest-reference projection: at each height
    a ray starts at the front/back midline skin midpoint and points toward the
    shared skin column.  It is needed because the first rib does not cross the
    104 mm sagittal plane.  No nearest-vertex or whole-rib median fallback is used.
    """
    if side not in ('left', 'right'):
        raise ValueError(side)
    x = COLUMN_METRES * (1 if side == 'left' else -1)
    ids = {}

    def mesh(name):
        ident, triangles = triangles_for(f'{side} {name}')
        ids[name] = ident
        return np.asarray(triangles, dtype=float)

    clavicle = mesh('clavicle')
    first = np.concatenate([mesh('first rib'), mesh('first costal cartilage')])
    second = np.concatenate([mesh('second rib'), mesh('second costal cartilage')])
    section = plane_section(clavicle, x)
    lower = section[np.argmin(section[:, 1])]
    upper = section[np.argmax(section[:, 1])]
    # Explicit small display-reference clearance, scaled to this source section.
    # It is recorded below and is not presented as an acupuncture prescription.
    clearance = float(np.clip((upper[1] - lower[1]) * .2, .002, .004))

    def skin(y, column=x, face='front'):
        value = np.asarray(surface(column, float(y), face), dtype=float)
        if max(abs(value[0] - column), abs(value[1] - y)) > 1e-8:
            raise ValueError('No exact skin ray hit; refusing a nearest-vertex fallback')
        return value

    def occupied(triangles, y):
        target = skin(y)
        centre_z = (skin(y, 0)[2] + skin(y, 0, 'back')[2]) / 2
        origin = np.array([0., y, centre_z])
        direction = target - origin
        direction /= np.linalg.norm(direction)
        return ray_hits(triangles, origin, direction)

    # Search only the anterior first/second-rib neighborhood.  The lowest first
    # rib crossing and the highest second crossing below it bound this gap.
    lo = float(second[:, :, 1].min())
    hi = min(float(first[:, :, 1].min()) + .030, float(lower[1]))
    samples = np.arange(lo, hi + SCAN_STEP_METRES / 2, SCAN_STEP_METRES)
    first_crossings = [i for i, y in enumerate(samples) if occupied(first, y)]
    if not first_crossings or first_crossings[0] == 0:
        raise ValueError('Cannot bracket the anterior lower first-rib boundary')
    first_index = first_crossings[0]
    second_crossings = [i for i in range(first_index) if occupied(second, samples[i])]
    if not second_crossings or second_crossings[-1] + 1 >= first_index:
        raise ValueError('Cannot bracket a first interspace below the first rib')

    def boundary(triangles, a, b, begins):
        for _ in range(12):
            mid = (a + b) / 2
            if occupied(triangles, mid) == begins:
                b = mid
            else:
                a = mid
        return float((a + b) / 2)

    first_lower = boundary(first, samples[first_index - 1], samples[first_index], True)
    j = second_crossings[-1]
    second_upper = boundary(second, samples[j], samples[j + 1], False)
    gap = first_lower - second_upper
    if not .005 < gap < .040:
        raise ValueError(f'Unexpected projected first-interspace width: {gap}')
    interval_y = (first_lower + second_upper) / 2
    positions = {'st12': skin(upper[1] + clearance),
                 'st13': skin(lower[1] - clearance),
                 'st14': skin(interval_y)}
    if not positions['st12'][1] > positions['st13'][1] > positions['st14'][1]:
        raise ValueError('Upper-chest source references are not in anatomical order')
    evidence = {
        'construction': 'local-upper-chest-geometry-reference',
        'reviewStatus': 'provisional-pending-professional-localization-review',
        'columnMetres': x,
        'columnBasis': 'Existing ST13-ST18 chest teaching column; 4 x 0.026 m retained, not a newly measured B-cun calibration.',
        'clavicleInferiorSectionPoint': lower.tolist(),
        'clavicleSuperiorSectionPoint': upper.tolist(),
        'clavicleReferenceClearanceMetres': clearance,
        'firstRibLowerRadialProjectionY': first_lower,
        'secondRibUpperRadialProjectionY': second_upper,
        'firstInterspaceProjectionWidthMetres': gap,
        'ribReferenceProjection': 'Outward ray from midpoint of anterior/posterior midline skin to shared chest column at the same Y.',
        'boundarySearchStepMetres': SCAN_STEP_METRES,
        'boundaryRefinementSteps': 12,
        'sourceStructureIds': list(ids.values()),
        'limitationZh': '本模型局部几何教学参考；沿用既有胸部旁开列，肋间区采用径向体表投影；并非逐穴医学审校或个体取穴坐标。',
    }
    return positions, evidence
