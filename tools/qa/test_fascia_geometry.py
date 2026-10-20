#!/usr/bin/env python3
"""Regression checks for delivered fascial geometry, using the real bundled GLB.

These are host geometry/content checks, not medical review or Android visual tests.
"""
import hashlib
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/content'))
from build_fascia import Model, norm, sub, point_triangle_distance


class FasciaGeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.content_path = ROOT / 'app/src/main/assets/content/fascia.json'
        cls.content = json.loads(cls.content_path.read_text())
        cls.groups = cls.content['connections']
        cls.model = Model(ROOT / 'app/src/main/assets/models/body-standard.glb')

    def test_original_twenty_reference_regions_are_unchanged(self):
        targets = [target for group in self.groups[:3] for target in group['targets']]
        self.assertEqual(20, len(targets))
        digest = hashlib.sha256(json.dumps(targets, ensure_ascii=False, sort_keys=True,
            separators=(',', ':')).encode()).hexdigest()
        self.assertEqual('db9b2f0f316f433a596427888410e24635d823fe923a2a441a31b808d917ee26', digest)

    def test_seven_distinct_bilateral_research_groups_keep_evidence_and_limits(self):
        self.assertEqual(7, len(self.groups))
        self.assertEqual(7, len({group['color'] for group in self.groups}))
        for group in self.groups:
            self.assertEqual({'left', 'right'}, {path['side'] for path in group['paths']})
            self.assertEqual('local_research_relation', group['coverageKind'])
            self.assertTrue(group['sourceRefs'] and group['bodyRegionsZh'] and group['unmappedStructures'])
            self.assertEqual('provisional', group['reviewStatus'])
            for path in group['paths']:
                self.assertTrue(path['notAForceVector'])
                self.assertIsNone(path['direction'])
                self.assertIsNone(path['forceMagnitude'])

    def test_whole_tissue_context_is_visible_beyond_attachment_bridges(self):
        minimum_lengths = {'context-ld-': .25, 'context-biceps-long-': .30,
            'context-itb-': .45, 'context-achilles-': .15, 'context-plantar-': .10,
            'context-palmaris-': .18}
        seen = set()
        for group in self.groups:
            for path in group['paths']:
                for prefix, minimum in minimum_lengths.items():
                    if path['id'].startswith(prefix):
                        length = sum(norm(sub(a, b)) for a, b in zip(path['points'], path['points'][1:]))
                        self.assertGreater(length, minimum, path['id'])
                        seen.add(prefix)
        self.assertEqual(set(minimum_lengths), seen)

    def test_context_samples_reconstruct_from_actual_triangles(self):
        for group in self.groups:
            for path in group['paths']:
                if path['kind'] != 'anatomical_context_schematic':
                    continue
                self.assertFalse(path['targetIds'])
                self.assertEqual(len(path['points']), len(path['normals']))
                self.assertEqual(len(path['points']), len(path['sourceGeometrySamples']))
                for point, normal, source in zip(path['points'], path['normals'], path['sourceGeometrySamples']):
                    mesh = self.model.mesh(source['nodeName'])
                    triangle = mesh['faces'][source['triangleIndex']]
                    self.assertEqual(list(triangle), source['vertexIndices'])
                    self.assertEqual(self.model.sha256, source['modelSha256'])
                    self.assertEqual(mesh['sha256'], source['meshGeometrySha256'])
                    weights = source['barycentric']
                    self.assertTrue(all(-1e-12 <= weight <= 1 + 1e-12 for weight in weights))
                    self.assertAlmostEqual(1, sum(weights), places=12)
                    rebuilt = [sum(mesh['vertices'][index][axis] * weight
                        for index, weight in zip(triangle, weights)) for axis in range(3)]
                    self.assertLess(norm(sub(rebuilt, point)), 1e-8)
                    self.assertAlmostEqual(1, norm(normal), places=8)

    def test_every_context_segment_stays_on_a_real_connected_triangle(self):
        for group in self.groups:
            for path in group['paths']:
                if path['kind'] != 'anatomical_context_schematic':
                    continue
                points = path['points']; sources = path['sourceGeometrySamples']
                for index, (a, b) in enumerate(zip(points, points[1:])):
                    self.assertLessEqual(norm(sub(a, b)), .008000001, path['id'])
                    previous = sources[index]; current = sources[index + 1]
                    self.assertEqual(previous['nodeName'], current['nodeName'])
                    mesh = self.model.mesh(current['nodeName'])
                    # When provenance switches triangles, the preceding point is
                    # the actual common-edge point, so the whole next segment is
                    # within the current triangle rather than a chord across space.
                    vertices = [mesh['vertices'][v] for v in mesh['faces'][current['triangleIndex']]]
                    self.assertLess(point_triangle_distance(a, *vertices), 1e-8, path['id'])
                    self.assertLess(point_triangle_distance(b, *vertices), 1e-8, path['id'])

    def test_surface_tightening_keeps_endpoints_and_never_increases_length(self):
        for group in self.groups:
            for path in group['paths']:
                if path['kind'] != 'anatomical_context_schematic':
                    continue
                result = path['surfaceOptimization']
                self.assertTrue(result['fixedEndpoints'])
                self.assertEqual(0, result['maximumOffSurfaceDisplacementM'])
                self.assertLessEqual(result['afterEdgeStringLengthM'], result['beforeEdgeMidpointLengthM'] + 1e-12)
                self.assertLessEqual(result['afterEdgeStringLengthM'], result['beforeCenterWalkLengthM'] + 1e-12)
                actual_length = sum(norm(sub(a, b)) for a, b in zip(path['points'], path['points'][1:]))
                self.assertAlmostEqual(actual_length, result['afterEdgeStringLengthM'], places=7)
                for index in (0, -1):
                    source = path['sourceGeometrySamples'][index]
                    mesh = self.model.mesh(source['nodeName'])
                    self.assertLess(norm(sub(path['points'][index], mesh['centers'][source['triangleIndex']])), 1e-8)
                    for weight in source['barycentric']:
                        self.assertAlmostEqual(1 / 3, weight, places=12)

    def test_new_relation_bridges_are_local_and_tra_branches_are_separate(self):
        for group in self.groups[3:]:
            for path in group['paths']:
                if path['kind'] == 'unoriented_connection_schematic':
                    self.assertLess(sum(norm(sub(a, b)) for a, b in zip(path['points'], path['points'][1:])), .05)
        tra = self.groups[-1]
        for side in ('left', 'right'):
            branches = [path for path in tra['paths'] if path['side'] == side
                and path['kind'] == 'unoriented_connection_schematic']
            self.assertEqual(2, len(branches))
            self.assertEqual({f'tra-tlf-{side}-middle', f'tra-tlf-{side}-posterior'},
                {path['targetIds'][-1] for path in branches})

    def test_manifest_refers_to_this_content_and_both_model_qualities(self):
        report_path = ROOT / 'manifests/fascia-content.json'
        if not report_path.exists():
            report_path = ROOT / 'docs/validation/fascia-content.json'
        report = json.loads(report_path.read_text())
        self.assertEqual(14, report['instances'])
        self.assertEqual(38, report['targets'])
        self.assertEqual(20, report['originalTargetsUnchanged'])
        self.assertEqual(16, report['relationPaths'])
        self.assertEqual(32, report['contextPaths'])
        self.assertEqual(0, report['professionalAnatomicalReviewedTargets'])
        self.assertLess(report['maxDistanceToLowSurfaceM'], .012)
        self.assertEqual(hashlib.sha256(self.content_path.read_bytes()).hexdigest(), report['outputs'][0]['sha256'])
        for item in report['inputs']:
            self.assertEqual(hashlib.sha256((ROOT / item['path']).read_bytes()).hexdigest(), item['sha256'])


if __name__ == '__main__':
    unittest.main()
