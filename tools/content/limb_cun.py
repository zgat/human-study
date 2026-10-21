"""Explicit longitudinal limb B-cun targets, without skin projection or I/O.

The caller supplies anatomical references in model metres (+Y is superior)::

    refs = {'patella_apex': {
        'position': (x, y, z),
        'source': {'structureIds': ['...'], 'method': '...'},
        'proxyLimitations': ['Unreviewed source-mesh landmark.'],
    }, ...}

Names describe anatomy, not arbitrary aliases: a humeral distal bound must not
be supplied as ``olecranon_tip``. A source may include ``anatomicalReference``;
when supplied it must agree with the key. No landmark is discovered here and no
result is marked medically reviewed. The scale is a model-axis projection, not
a measured skin path, a screen distance, or an individual treatment location.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
import copy
import math


POINT_IDS = frozenset({'sp8', 'ki9', 'st36', 'st37', 'st38', 'st39',
                       'st40', 'te9', 'gb31', 'gb32'})

# These endpoints and denominators are deliberately not inferred from a point's
# meridian, Chinese description, current region label, or another segment.
SEGMENTS = {
    'medial_lower_leg_15': ('patella_apex', 'medial_malleolus', 15),
    'lateral_lower_leg_16': ('popliteal_crease', 'lateral_malleolus', 16),
    'anterior_thigh_18': ('pubic_upper_margin', 'patella_base', 18),
    'lateral_thigh_19': ('greater_trochanter', 'popliteal_crease', 19),
    'upper_arm_9': ('anterior_axillary_fold', 'elbow_crease', 9),
    'posterior_upper_arm_9': ('posterior_axillary_fold', 'olecranon_tip', 9),
    'forearm_12': ('elbow_crease', 'wrist_crease', 12),
}

_ALLOWED_REFERENCES = {
    'medial_lower_leg_15': frozenset({'patella_apex', 'medial_malleolus', 'sp9', 'ki3'}),
    'lateral_lower_leg_16': frozenset({'popliteal_crease', 'lateral_malleolus', 'st35'}),
    'anterior_thigh_18': frozenset({'pubic_upper_margin', 'patella_base'}),
    'lateral_thigh_19': frozenset({'greater_trochanter', 'popliteal_crease'}),
    'upper_arm_9': frozenset({'anterior_axillary_fold', 'elbow_crease'}),
    'posterior_upper_arm_9': frozenset({'posterior_axillary_fold', 'olecranon_tip'}),
    'forearm_12': frozenset({'elbow_crease', 'wrist_crease', 'olecranon_tip'}),
}

_POINT_RULES = {
    'sp8': ('medial_lower_leg_15', 'sp9', 3, 'inferior'),
    'ki9': ('medial_lower_leg_15', 'ki3', 5, 'superior'),
    'st36': ('lateral_lower_leg_16', 'st35', 3, 'inferior'),
    'st37': ('lateral_lower_leg_16', 'st35', 6, 'inferior'),
    'st38': ('lateral_lower_leg_16', 'st35', 8, 'inferior'),
    'st39': ('lateral_lower_leg_16', 'st35', 9, 'inferior'),
    # A separate rule protects ST40 from the ST36-39 inferior-from-ST35 group.
    'st40': ('lateral_lower_leg_16', 'lateral_malleolus', 8, 'superior'),
    'te9': ('forearm_12', 'olecranon_tip', 5, 'inferior'),
    'gb31': ('lateral_thigh_19', 'popliteal_crease', 9, 'superior'),
    'gb32': ('lateral_thigh_19', 'popliteal_crease', 7, 'superior'),
}

_PROXY_LIMIT = ('Popliteal-crease Y is approximated by the supplied patellar-apex '
                'plane; the crease itself has not been identified or reviewed. '
                'Only Y is reused, not an anatomical posterior surface location.')
_LOCALIZATION_LIMIT = ('Only a longitudinal model-Y target is calculated. Landmark '
                       'identity, transverse/depth placement and skin projection '
                       'still require separate anatomical review.')


class MissingReference(ValueError):
    """A required named landmark is absent; no generic midpoint is substituted."""


def _base_evidence(segment):
    if segment not in SEGMENTS:
        raise ValueError(f'Unknown limb B-cun segment: {segment!r}')
    proximal, distal, denominator = SEGMENTS[segment]
    return {
        'schemaVersion': 1,
        'status': 'resolved',
        'reviewStatus': 'provisional-model-axis-teaching-measurement',
        'medicallyReviewed': False,
        'segment': segment,
        'proximalReference': proximal,
        'distalReference': distal,
        'denominatorCun': denominator,
        'expectedSegmentCun': denominator,
        'measurementAxis': 'model +Y longitudinal projection, metres',
        'localizationLimit': _LOCALIZATION_LIMIT,
    }


def _reference(name, refs, *, allow_popliteal_proxy):
    if name not in refs:
        if name == 'popliteal_crease' and allow_popliteal_proxy:
            apex = _reference('patella_apex', refs, allow_popliteal_proxy=False)
            return {
                'name': name,
                'position': apex['position'][:],
                'source': copy.deepcopy(apex['source']),
                'resolvedAs': 'patella_apex_y_plane_proxy',
                'proxyLimitations': [*apex['proxyLimitations'], _PROXY_LIMIT],
            }
        raise MissingReference(f'Missing explicit anatomical reference: {name}')
    raw = refs[name]
    if not isinstance(raw, Mapping):
        raise ValueError(f'{name}: reference must include position and source dictionaries')
    position = raw.get('position')
    try:
        if isinstance(position, (str, bytes)) or len(position) != 3:
            raise ValueError
        position = [float(value) for value in position]
    except (TypeError, ValueError, OverflowError):
        raise ValueError(f'{name}: position must contain three finite model-metre values') from None
    if not all(math.isfinite(value) for value in position):
        raise ValueError(f'{name}: position must contain three finite model-metre values')
    source = raw.get('source')
    if not isinstance(source, Mapping) or not source:
        raise ValueError(f'{name}: nonempty source evidence is required')
    if source.get('anatomicalReference', name) != name:
        raise ValueError(f'{name}: source anatomicalReference identifies another landmark')
    limitations = raw.get('proxyLimitations', [])
    if not isinstance(limitations, Sequence) or isinstance(limitations, (str, bytes)) or \
            not all(isinstance(value, str) for value in limitations):
        raise ValueError(f'{name}: proxyLimitations must be a list of strings')
    return {'name': name, 'position': position, 'source': copy.deepcopy(dict(source)),
            'resolvedAs': name, 'proxyLimitations': list(limitations)}


def _limitations(*references):
    return list(dict.fromkeys(value for reference in references
                              for value in reference['proxyLimitations']))


def scale(segment, refs, *, allow_popliteal_proxy=False):
    """Return ``(unit_metres_or_None, evidence)`` for an explicit segment.

    Missing or invalid references raise. The lateral-thigh segment is explicitly
    unresolved when no greater-trochanter reference exists; it never borrows the
    pubis/patella anterior-thigh scale. The patellar-apex proxy for a missing
    popliteal crease requires the opt-in flag and is recorded in the evidence.
    """
    evidence = _base_evidence(segment)
    if segment == 'lateral_thigh_19' and 'greater_trochanter' not in refs:
        evidence.update(status='unresolved', unitMetres=None,
                        missingReferences=['greater_trochanter'],
                        reason='No source-defined greater-trochanter reference; '
                               'anterior-thigh 18-cun fallback is forbidden.')
        return None, evidence
    proximal = _reference(evidence['proximalReference'], refs,
                          allow_popliteal_proxy=allow_popliteal_proxy)
    distal = _reference(evidence['distalReference'], refs,
                        allow_popliteal_proxy=allow_popliteal_proxy)
    length = proximal['position'][1] - distal['position'][1]
    if length <= 0 or not math.isfinite(length):
        raise ValueError(f'{segment}: proximal Y must be above distal Y')
    unit = length / evidence['denominatorCun']
    evidence.update(axisSpanMetres=length, unitMetres=unit,
                    chordLengthMetres=math.dist(proximal['position'], distal['position']),
                    endpointEvidence={'proximal': proximal, 'distal': distal},
                    proxyLimitations=_limitations(proximal, distal))
    return unit, evidence


def offset_target(segment, reference, cun, direction, refs, *,
                  allow_popliteal_proxy=False):
    """Return ``(target_y_or_None, evidence)`` with no surface projection.

    References are restricted by segment so wrist offsets cannot accidentally
    use the upper-arm 9-cun unit and malleolar offsets cannot use thigh 18.
    """
    _base_evidence(segment)  # Validate before indexing the allowed-reference map.
    if reference not in _ALLOWED_REFERENCES[segment]:
        raise ValueError(f'{reference!r} is not a reference for {segment}')
    if direction not in ('superior', 'inferior'):
        raise ValueError('direction must be superior or inferior')
    try:
        distance = float(cun)
    except (TypeError, ValueError, OverflowError):
        raise ValueError('cun must be a finite nonnegative distance') from None
    if not math.isfinite(distance) or distance < 0:
        raise ValueError('cun must be a finite nonnegative distance')
    unit, evidence = scale(segment, refs, allow_popliteal_proxy=allow_popliteal_proxy)
    evidence.update(targetReference=reference, direction=direction, requestedCun=distance)
    if unit is None:
        evidence.update(targetY=None, expectedOffsetMetres=None)
        return None, evidence
    origin = _reference(reference, refs, allow_popliteal_proxy=allow_popliteal_proxy)
    signed_offset = distance * unit * (1 if direction == 'superior' else -1)
    y = origin['position'][1] + signed_offset
    low = evidence['endpointEvidence']['distal']['position'][1]
    high = evidence['endpointEvidence']['proximal']['position'][1]
    if not math.isfinite(y) or y < low - 1e-9 or y > high + 1e-9:
        raise ValueError(f'{segment}: requested target lies outside its explicit endpoints')
    evidence.update(targetY=y, referenceY=origin['position'][1],
                    expectedOffsetMetres=signed_offset,
                    resultingOffsetCun=(y - origin['position'][1]) / unit,
                    referenceEvidence=origin,
                    proxyLimitations=list(dict.fromkeys(
                        [*evidence['proxyLimitations'], *origin['proxyLimitations']])))
    return y, evidence


def point_target(point_id, refs, *, allow_popliteal_proxy=False):
    """Apply an explicit point rule; unknown point IDs raise instead of guessing.

    IDs are the catalogue's lowercase IDs. ST40 is handled by its own immutable
    rule, independent of ST36-39 and independent of the order of calls.
    """
    if point_id not in _POINT_RULES:
        raise ValueError(f'No explicit limb B-cun point rule for {point_id!r}')
    segment, reference, cun, direction = _POINT_RULES[point_id]
    y, evidence = offset_target(segment, reference, cun, direction, refs,
                               allow_popliteal_proxy=allow_popliteal_proxy)
    evidence['pointId'] = point_id
    return y, evidence
