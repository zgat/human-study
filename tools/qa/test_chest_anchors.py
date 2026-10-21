#!/usr/bin/env python3
"""Upper-chest source-geometry and local-update regression; no worktree outputs."""
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
import chest_anchors


class SourceGeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads((generator.OUT / 'acupoints.json').read_text())
        cls.points = {p['id']: p for p in cls.data['points']}
        cls.references = {side: generator.chest_reference(side) for side in ('left', 'right')}

    def test_neck_classification_cannot_bypass_shared_chest_column(self):
        # The old neck fallback produced X=35 mm, while the chest column is 104 mm.
        self.assertEqual(self.points['st12']['region'], '颈前部')
        for side in ('left', 'right'):
            existing_column = next(i['anchor']['position'][0]
                                   for i in self.points['st15']['instances'] if i['side'] == side)
            for pid in sorted(chest_anchors.POINT_IDS):
                inst = next(i for i in self.points[pid]['instances'] if i['side'] == side)
                position, refs, method = generator.map_point(self.points[pid], inst, {})
                self.assertAlmostEqual(position[0], existing_column, places=9)
                self.assertIn(generator.find(side + ' clavicle'), refs)
                self.assertIn('local-geometry-reference', method)

    def test_points_follow_local_bone_boundaries_and_first_interspace(self):
        for side, (positions, evidence) in self.references.items():
            upper = evidence['clavicleSuperiorSectionPoint'][1]
            lower = evidence['clavicleInferiorSectionPoint'][1]
            self.assertGreater(positions['st12'][1], upper)
            self.assertLess(positions['st13'][1], lower)
            self.assertLess(positions['st14'][1], positions['st13'][1])
            # Protect against the original ST13/ST14 pile-up of only 2.8--3.2 mm.
            self.assertGreater(positions['st13'][1] - positions['st14'][1], .005)
            self.assertGreater(positions['st14'][1], evidence['secondRibUpperRadialProjectionY'])
            self.assertLess(positions['st14'][1], evidence['firstRibLowerRadialProjectionY'])
            self.assertIn('not a newly measured B-cun', evidence['columnBasis'])
            self.assertIn('pending-professional', evidence['reviewStatus'])

    def test_local_regeneration_changes_only_six_anchors_and_keeps_surface_binding(self):
        original_root, original_out = generator.ROOT, generator.OUT
        with tempfile.TemporaryDirectory() as folder:
            temporary = Path(folder)
            content = temporary / 'content'
            content.mkdir()
            (temporary / 'manifests').mkdir()
            (content / 'acupoints.json').write_text(json.dumps(self.data, ensure_ascii=False))
            landmark_bytes = (original_out / 'landmarks.json').read_bytes()
            (content / 'landmarks.json').write_bytes(landmark_bytes)
            try:
                generator.ROOT, generator.OUT = temporary, content
                with contextlib.redirect_stdout(io.StringIO()):
                    generator.main(set(chest_anchors.POINT_IDS))
            finally:
                generator.ROOT, generator.OUT = original_root, original_out
            result = json.loads((content / 'acupoints.json').read_text())
            changed_instances = []
            for before, after in zip(self.data['points'], result['points']):
                self.assertEqual(before['id'], after['id'])
                if before['id'] not in chest_anchors.POINT_IDS:
                    self.assertEqual(before, after)
                    continue
                before_text, after_text = copy.deepcopy(before), copy.deepcopy(after)
                before_text.pop('instances')
                after_text.pop('instances')
                self.assertEqual(before_text, after_text)
                for inst in after['instances']:
                    anchor = inst['anchor']
                    changed_instances.append(inst['id'])
                    skin = generator.vertices(anchor['sourceSurface'])
                    triangle = skin[np.asarray(anchor['sourceTriangle']) - 1]
                    weights = np.asarray(anchor['barycentric'])
                    self.assertAlmostEqual(weights.sum(), 1, places=9)
                    self.assertGreaterEqual(weights.min(), -1e-7)
                    bound = (triangle * weights[:, None]).sum(0)
                    np.testing.assert_allclose(bound, anchor['position'], atol=1e-7, rtol=0)
                    self.assertEqual(anchor['status'], 'provisional')
                    self.assertEqual(anchor['sourceSurface'], 'FJ2810')
                    self.assertIn('placementEvidence', anchor)
            self.assertEqual(len(changed_instances), 6)
            self.assertEqual((content / 'landmarks.json').read_bytes(), landmark_bytes)
            self.assertEqual(result['counts']['medicallyReviewedInstances'], 0)


class GeometryFailureTest(unittest.TestCase):
    def test_ray_is_two_sided_but_rejects_the_opposite_half_ray(self):
        triangle = np.array([[[1., -1., -1.], [1., 1., -1.], [1., 0., 1.]]])
        self.assertTrue(chest_anchors.ray_hits(triangle, [0, 0, 0], [1, 0, 0]))
        self.assertTrue(chest_anchors.ray_hits(triangle[:, ::-1], [0, 0, 0], [1, 0, 0]))
        self.assertFalse(chest_anchors.ray_hits(triangle, [0, 0, 0], [-1, 0, 0]))

    def test_missing_clavicle_section_fails_instead_of_using_other_vertices(self):
        triangle = np.array([[[0., 0., 0.], [0., 1., 0.], [0., 0., 1.]]])
        with self.assertRaisesRegex(ValueError, 'does not intersect'):
            chest_anchors.plane_section(triangle, .104)


if __name__ == '__main__':
    unittest.main()
