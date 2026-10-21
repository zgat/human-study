#!/usr/bin/env python3
"""Pure anatomical-reference/segment regressions; no model or content writes."""
import copy
import math
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'content'))
from limb_cun import MissingReference, offset_target, point_target, scale


def reference(name, y, x=0., z=0., limitations=()):
    return {'position': [x, y, z],
            'source': {'anatomicalReference': name, 'structureIds': ['fixture-' + name],
                       'method': 'explicit synthetic landmark; not source-mesh validation'},
            'proxyLimitations': list(limitations)}


def references():
    # Deliberately different scales/reference heights expose accidental reuse:
    # medial leg .030, lateral leg .025, front thigh .025, lateral thigh .020,
    # anterior arm .020, posterior arm .022, forearm .015 metres per B-cun.
    return {name: reference(name, y) for name, y in {
        'patella_apex': .70, 'patella_base': .75, 'medial_malleolus': .25,
        'popliteal_crease': .69, 'lateral_malleolus': .29,
        'pubic_upper_margin': 1.20, 'greater_trochanter': 1.07,
        'sp9': .66, 'ki3': .265, 'st35': .675,
        'elbow_crease': 1.12, 'wrist_crease': .94, 'olecranon_tip': 1.13,
        'anterior_axillary_fold': 1.30, 'posterior_axillary_fold': 1.328,
    }.items()}


class SegmentScaleTest(unittest.TestCase):
    def test_explicit_endpoint_pairs_have_distinct_correct_denominators(self):
        refs = references()
        for segment, denominator, unit, proximal, distal in (
            ('medial_lower_leg_15', 15, .030, 'patella_apex', 'medial_malleolus'),
            ('lateral_lower_leg_16', 16, .025, 'popliteal_crease', 'lateral_malleolus'),
            ('anterior_thigh_18', 18, .025, 'pubic_upper_margin', 'patella_base'),
            ('lateral_thigh_19', 19, .020, 'greater_trochanter', 'popliteal_crease'),
            ('upper_arm_9', 9, .020, 'anterior_axillary_fold', 'elbow_crease'),
            ('posterior_upper_arm_9', 9, .022, 'posterior_axillary_fold', 'olecranon_tip'),
            ('forearm_12', 12, .015, 'elbow_crease', 'wrist_crease'),
        ):
            with self.subTest(segment=segment):
                actual, evidence = scale(segment, refs)
                self.assertAlmostEqual(actual, unit)
                self.assertEqual(evidence['denominatorCun'], denominator)
                self.assertEqual(evidence['expectedSegmentCun'], denominator)
                self.assertEqual(evidence['proximalReference'], proximal)
                self.assertEqual(evidence['distalReference'], distal)
                self.assertAlmostEqual(evidence['axisSpanMetres'], denominator * unit)
                self.assertFalse(evidence['medicallyReviewed'])

    def test_y_span_does_not_silently_become_three_dimensional_chord(self):
        refs = references()
        refs['patella_apex']['position'][0] = .10
        unit, evidence = scale('medial_lower_leg_15', refs)
        self.assertAlmostEqual(unit, .030)
        self.assertAlmostEqual(evidence['chordLengthMetres'], math.hypot(.45, .10))
        self.assertGreater(evidence['chordLengthMetres'], evidence['axisSpanMetres'])

    def test_popliteal_proxy_requires_opt_in_and_uses_apex_not_base(self):
        refs = references(); del refs['popliteal_crease']
        with self.assertRaisesRegex(MissingReference, 'popliteal_crease'):
            scale('lateral_lower_leg_16', refs)
        unit, evidence = scale('lateral_lower_leg_16', refs, allow_popliteal_proxy=True)
        self.assertAlmostEqual(unit, (.70 - .29) / 16)
        self.assertNotAlmostEqual(unit, (.75 - .29) / 16)
        proxy = evidence['endpointEvidence']['proximal']
        self.assertEqual(proxy['resolvedAs'], 'patella_apex_y_plane_proxy')
        self.assertEqual(proxy['source']['anatomicalReference'], 'patella_apex')
        self.assertTrue(any('crease itself has not been identified' in value
                            for value in evidence['proxyLimitations']))

    def test_missing_gt_is_unresolved_even_when_anterior_thigh_is_available(self):
        refs = references(); del refs['greater_trochanter']
        front, _ = scale('anterior_thigh_18', refs)
        self.assertAlmostEqual(front, .025)
        for point_id in ('gb31', 'gb32'):
            target, evidence = point_target(point_id, refs)
            self.assertIsNone(target)
            self.assertEqual(evidence['status'], 'unresolved')
            self.assertEqual(evidence['missingReferences'], ['greater_trochanter'])
            self.assertEqual(evidence['denominatorCun'], 19)
            self.assertIsNone(evidence['unitMetres'])
            self.assertIn('fallback is forbidden', evidence['reason'])

    def test_wrong_segment_or_reference_cannot_be_used_for_an_offset(self):
        refs = references()
        for segment, landmark in (
            ('upper_arm_9', 'wrist_crease'),
            ('forearm_12', 'anterior_axillary_fold'),
            ('anterior_thigh_18', 'lateral_malleolus'),
            ('lateral_thigh_19', 'pubic_upper_margin'),
            ('medial_lower_leg_15', 'lateral_malleolus'),
            ('lateral_lower_leg_16', 'medial_malleolus'),
        ):
            with self.subTest(segment=segment, landmark=landmark):
                with self.assertRaisesRegex(ValueError, 'not a reference'):
                    offset_target(segment, landmark, 2, 'superior', refs)
        with self.assertRaisesRegex(ValueError, 'Unknown'):
            scale('generic_limb', refs)


class ExplicitPointRuleTest(unittest.TestCase):
    def test_sp8_and_ki9_use_reference_points_not_generic_ankle_midpoints(self):
        refs = references()
        y, evidence = point_target('sp8', refs)
        self.assertAlmostEqual(y, .66 - 3 * .030)
        self.assertEqual(evidence['targetReference'], 'sp9')
        self.assertAlmostEqual(evidence['expectedOffsetMetres'], -.090)
        y, evidence = point_target('ki9', refs)
        self.assertAlmostEqual(y, .265 + 5 * .030)
        self.assertEqual(evidence['targetReference'], 'ki3')
        self.assertAlmostEqual(evidence['resultingOffsetCun'], 5)
        del refs['sp9']
        with self.assertRaisesRegex(MissingReference, 'sp9'):
            point_target('sp8', refs)
        del refs['ki3']
        with self.assertRaisesRegex(MissingReference, 'ki3'):
            point_target('ki9', refs)

    def test_st40_is_above_lateral_ankle_even_when_st36_to_39_use_st35(self):
        refs = references()
        for point_id, distance in (('st36', 3), ('st37', 6), ('st38', 8), ('st39', 9)):
            y, evidence = point_target(point_id, refs)
            self.assertAlmostEqual(y, .675 - distance * .025)
            self.assertEqual(evidence['targetReference'], 'st35')
        y, evidence = point_target('st40', refs)
        self.assertAlmostEqual(y, .29 + 8 * .025)
        self.assertNotAlmostEqual(y, .675 - 8 * .025)
        self.assertEqual(evidence['targetReference'], 'lateral_malleolus')
        self.assertEqual(evidence['direction'], 'superior')
        # ST35 may be absent or changed without moving ST40; no group fallback.
        del refs['st35']
        self.assertAlmostEqual(point_target('st40', refs)[0], y)
        with self.assertRaisesRegex(MissingReference, 'st35'):
            point_target('st36', refs)

    def test_te9_uses_real_olecranon_reference_and_forearm_unit(self):
        refs = references()
        y, evidence = point_target('te9', refs)
        self.assertAlmostEqual(y, 1.13 - 5 * .015)
        self.assertNotAlmostEqual(y, 1.12 - 5 * .015)
        self.assertNotAlmostEqual(y, 1.13 - 5 * .020)
        self.assertEqual(evidence['denominatorCun'], 12)
        self.assertEqual(evidence['referenceEvidence']['source']['anatomicalReference'], 'olecranon_tip')
        del refs['olecranon_tip']
        with self.assertRaisesRegex(MissingReference, 'olecranon_tip'):
            point_target('te9', refs)

    def test_gb31_32_use_nine_seven_and_lateral_thigh_19(self):
        refs = references()
        for point_id, distance in (('gb31', 9), ('gb32', 7)):
            y, evidence = point_target(point_id, refs)
            self.assertAlmostEqual(y, .69 + distance * .020)
            self.assertNotAlmostEqual(y, .75 + (distance - 2) * .025)
            self.assertEqual(evidence['targetReference'], 'popliteal_crease')
            self.assertEqual(evidence['denominatorCun'], 19)

    def test_input_and_returned_provenance_are_independent(self):
        refs = references(); before = copy.deepcopy(refs)
        _, evidence = point_target('te9', refs)
        evidence['referenceEvidence']['source']['structureIds'].append('changed')
        evidence['endpointEvidence']['distal']['position'][1] = 99
        self.assertEqual(refs, before)
        self.assertAlmostEqual(point_target('te9', refs)[0], 1.055)

    def test_missing_mislabeled_nonfinite_and_out_of_segment_inputs_fail(self):
        refs = references()
        with self.assertRaisesRegex(ValueError, 'No explicit'):
            point_target('gb23', refs)
        for value in (float('nan'), float('inf'), -1, None):
            with self.subTest(cun=value):
                with self.assertRaisesRegex(ValueError, 'finite nonnegative'):
                    offset_target('forearm_12', 'wrist_crease', value, 'superior', refs)
        with self.assertRaisesRegex(ValueError, 'outside'):
            offset_target('forearm_12', 'wrist_crease', 13, 'superior', refs)
        with self.assertRaisesRegex(ValueError, 'direction'):
            offset_target('forearm_12', 'wrist_crease', 1, 'up-ish', refs)
        bad = copy.deepcopy(refs)
        bad['olecranon_tip']['source']['anatomicalReference'] = 'elbow_crease'
        with self.assertRaisesRegex(ValueError, 'another landmark'):
            point_target('te9', bad)
        bad = copy.deepcopy(refs); bad['patella_apex']['position'][1] = float('nan')
        with self.assertRaisesRegex(ValueError, 'finite'):
            scale('medial_lower_leg_15', bad)
        bad = copy.deepcopy(refs); bad['medial_malleolus']['position'][1] = .80
        with self.assertRaisesRegex(ValueError, 'above distal'):
            scale('medial_lower_leg_15', bad)
        bad = copy.deepcopy(refs); bad['patella_apex']['source'] = {}
        with self.assertRaisesRegex(ValueError, 'source evidence'):
            scale('medial_lower_leg_15', bad)


if __name__ == '__main__':
    unittest.main()
