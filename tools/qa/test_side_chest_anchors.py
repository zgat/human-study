#!/usr/bin/env python3
"""GB23 relative-spacing regression against real source skin; no worktree output."""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/content'))
import anchor_points as generator
import side_chest_anchors as side_chest


class SourceSkinTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads((generator.OUT / 'acupoints.json').read_text())
        cls.points = {p['id']: p for p in cls.data['points']}
        cls.references = {side: generator.side_chest_reference(side) for side in ('left', 'right')}

    def test_two_point_gap_is_surface_length_with_source_triangle_bindings(self):
        skin = generator.vertices('FJ2810')
        for side, (position, evidence) in self.references.items():
            base = next(i['anchor']['position'] for i in self.points['gb22']['instances'] if i['side'] == side)
            column = next(i['anchor']['position'][0] for i in self.points['st16']['instances'] if i['side'] == side)
            self.assertAlmostEqual(evidence['teachingCunMetres'], abs(column) / 4, places=10)
            self.assertEqual(evidence['basePositionMetres'], base)
            self.assertAlmostEqual(position[1], base[1], places=10)
            self.assertGreater(position[2] - base[2], .020)
            self.assertAlmostEqual(evidence['pathArcLengthMetres'], .026, places=10)
            self.assertLess(evidence['pathChordLengthMetres'], evidence['pathArcLengthMetres'])
            self.assertGreater(evidence['pathChordLengthMetres'], .025)
            path = np.asarray(evidence['pathPointsMetres'])
            np.testing.assert_allclose(path[0], base, atol=1e-7, rtol=0)
            np.testing.assert_allclose(path[-1], position, atol=1e-10, rtol=0)
            self.assertAlmostEqual(np.linalg.norm(np.diff(path, axis=0), axis=1).sum(), .026, places=10)
            self.assertGreaterEqual(len(evidence['pathSegments']), 3)
            for i, segment in enumerate(evidence['pathSegments']):
                triangle = skin[np.asarray(segment['sourceTriangle']) - 1]
                for field, expected in [('startBarycentric', path[i]), ('endBarycentric', path[i + 1])]:
                    weights = np.asarray(segment[field])
                    self.assertAlmostEqual(weights.sum(), 1, places=10)
                    self.assertGreaterEqual(weights.min(), -1e-9)
                    np.testing.assert_allclose(weights @ triangle, expected, atol=1e-10, rtol=0)
                self.assertAlmostEqual(segment['lengthMetres'], np.linalg.norm(path[i + 1] - path[i]), places=10)
            sign = 1 if side == 'left' else -1
            self.assertTrue(np.all(sign * path[:, 0] < sign * evidence['upperArmMedialReferenceXMetres']))

    def test_evidence_does_not_claim_fourth_interspace_or_midaxillary_calibration(self):
        for side, (_, evidence) in self.references.items():
            self.assertFalse(evidence['basePositionChanged'])
            self.assertFalse(evidence['baseHeightAndMidaxillaryLineVerified'])
            self.assertFalse(evidence['fourthInterspaceHeightCorrected'])
            self.assertIn('provisional', evidence['reviewStatus'])
            self.assertIn('没有校正第四肋间高度', evidence['limitationZh'])
            reference = evidence['fourthInterspaceGeometryReference']
            self.assertGreater(reference['fourthRibLowerSectionPointMetres'][1],
                               reference['fifthRibUpperSectionPointMetres'][1])
            # The existing point is substantially below the model's local
            # lateral fourth-interspace reference. Preserve that known limit.
            self.assertLess(reference['baseYMinusReferenceMetres'], -.020)
            self.assertIn(generator.find(f'{side} fourth rib'), evidence['sourceStructureIds'])
            self.assertIn(generator.find(f'{side} fifth rib'), evidence['sourceStructureIds'])

    def test_narrow_update_preserves_gb22_all_other_entries_and_concurrent_notices(self):
        before = copy.deepcopy(self.data)
        for point in before['points']:
            if point['id'] in ('gb22', 'gb23'):
                point['coordinateNoticeZh'] = '既有定位限制提示，必须保留'
            if point['id'] == 'gb23':
                # An intentionally collapsed fixture must be repaired even
                # after the repository already contains the repaired anchors.
                for instance in point['instances']:
                    base = next(i['anchor'] for i in self.points['gb22']['instances']
                                if i['side'] == instance['side'])
                    instance['anchor']['position'] = base['position'][:]
        original_root, original_out = generator.ROOT, generator.OUT
        with tempfile.TemporaryDirectory() as folder:
            temporary = Path(folder); content = temporary / 'content'; content.mkdir()
            (temporary / 'manifests').mkdir()
            (content / 'acupoints.json').write_text(json.dumps(before, ensure_ascii=False))
            landmark_bytes = (original_out / 'landmarks.json').read_bytes()
            (content / 'landmarks.json').write_bytes(landmark_bytes)
            try:
                generator.ROOT, generator.OUT = temporary, content
                with contextlib.redirect_stdout(io.StringIO()):
                    generator.main({'gb23'})
            finally:
                generator.ROOT, generator.OUT = original_root, original_out
            after = json.loads((content / 'acupoints.json').read_text())
            changed_positions = []
            for old, new in zip(before['points'], after['points']):
                self.assertEqual(old['id'], new['id'])
                if old['id'] != 'gb23':
                    self.assertEqual(old, new)
                    continue
                a, b = copy.deepcopy(old), copy.deepcopy(new)
                a.pop('instances'); b.pop('instances'); self.assertEqual(a, b)
                for old_instance, instance in zip(old['instances'], new['instances']):
                    anchor = instance['anchor']
                    if old_instance['anchor']['position'] != anchor['position']:
                        changed_positions.append(instance['id'])
                    self.assertEqual(anchor['status'], 'provisional')
                    self.assertEqual(anchor['sourceSurface'], 'FJ2810')
                    triangle = generator.SKIN[np.asarray(anchor['sourceTriangle']) - 1]
                    np.testing.assert_allclose(np.asarray(anchor['barycentric']) @ triangle,
                                               anchor['position'], atol=1e-7, rtol=0)
                    self.assertAlmostEqual(np.linalg.norm(anchor['normal']), 1., places=6)
                    self.assertEqual(anchor['sourceMeshDistanceMetres'], 0.)
                    self.assertFalse(anchor['placementEvidence']['fourthInterspaceHeightCorrected'])
            self.assertEqual(changed_positions, ['gb23-left-1', 'gb23-right-1'])
            self.assertEqual((content / 'landmarks.json').read_bytes(), landmark_bytes)
            self.assertEqual(after['counts']['medicallyReviewedInstances'], 0)


class TopologyFailureTest(unittest.TestCase):
    @staticmethod
    def curved_strip():
        angles = np.linspace(0., np.pi / 3, 13)
        vertices = np.array([[.1 * np.cos(a), y, .1 * np.sin(a)] for a in angles for y in (0., .1)])
        faces = []
        for i in range(len(angles) - 1):
            a, b = 2 * i, 2 * (i + 1)
            faces.extend(((a, a + 1, b), (b, a + 1, b + 1)))
        return vertices, np.asarray(faces)

    def test_touching_but_disconnected_arm_geometry_cannot_replace_the_skin_path(self):
        vertices, faces = self.curved_strip()
        expected = side_chest.trace_contour(vertices, faces, 0, .026)
        count = len(vertices)
        # Exactly touching coordinates with distinct source IDs must remain
        # disconnected; a proximity-based projector could choose this shortcut.
        extra = np.array([vertices[0], vertices[0] + [0, 0, .026], vertices[0] + [.02, .1, 0]])
        result = side_chest.trace_contour(np.concatenate([vertices, extra]),
            np.concatenate([faces, np.array([[count, count + 1, count + 2]])]), 0, .026)
        np.testing.assert_allclose(result['position'], expected['position'], atol=1e-12)
        self.assertTrue(all(max(s['sourceTriangle']) <= count for s in result['segments']))

    def test_a_broken_skin_contour_fails_instead_of_nearest_surface_fallback(self):
        vertices, faces = self.curved_strip()
        with self.assertRaisesRegex(ValueError, 'ended or branched'):
            side_chest.trace_contour(vertices, faces[faces[:, 0] != 2], 0, .026)

    def test_upper_arm_guard_rejects_a_path_outside_the_torso_reference(self):
        vertices, faces = self.curved_strip()
        with self.assertRaisesRegex(ValueError, 'upper-arm'):
            side_chest.trace_contour(vertices, faces, 0, .026, lateral_limit=.09)


if __name__ == '__main__':
    unittest.main()
