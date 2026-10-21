#!/usr/bin/env python3
"""Exact horizontal source-surface geometry tests; no content/model writes."""
from pathlib import Path
import sys
import unittest

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/content'))
from cun_surface import (project_horizontal, SurfaceProjectionError,
                         DegenerateTriangleError, NoIntersectionError, DistanceLimitError)


def x_plane(x):
    return np.array([[x, -.05, -.05], [x, .05, -.05], [x, .05, .05], [x, -.05, .05]])


FACES = np.array([[0, 1, 2], [0, 2, 3]])


class HorizontalProjectionTest(unittest.TestCase):
    def assert_binding(self, result, vertices, y):
        indices = np.asarray(result['sourceTriangle']) - 1
        weights = np.asarray(result['barycentric'])
        self.assertTrue(np.all(indices >= 0))
        self.assertAlmostEqual(float(weights.sum()), 1., places=13)
        self.assertGreaterEqual(float(weights.min()), 0.)
        np.testing.assert_allclose(weights @ np.asarray(vertices)[indices], result['position'], atol=1e-12, rtol=0)
        self.assertEqual(result['position'][1], y)
        self.assertAlmostEqual(float(np.linalg.norm(result['normal'])), 1., places=13)
        self.assertFalse(result['evidence']['nearestVertexFallback'])

    def test_exact_height_and_one_based_source_triangle(self):
        vertices = x_plane(.04)
        origin = [0., .013, .011]
        result = project_horizontal(vertices, FACES, origin, [1., 0., 0.])
        np.testing.assert_allclose(result['position'], [.04, .013, .011], atol=1e-14, rtol=0)
        self.assert_binding(result, vertices, origin[1])
        self.assertIn(result['sourceTriangle'], [[1, 2, 3], [1, 3, 4]])

    def test_two_layers_choose_nearest_forward_layer_even_with_reversed_winding(self):
        vertices = np.concatenate([x_plane(.07), x_plane(.03)])
        faces = np.concatenate([FACES, (FACES + 4)[:, ::-1]])
        result = project_horizontal(vertices, faces, [0., .013, .011], [7., 0., 0.])
        self.assertAlmostEqual(result['position'][0], .03, places=13)
        self.assertTrue(all(index > 4 for index in result['sourceTriangle']))
        self.assert_binding(result, vertices, .013)

    def test_a_layer_behind_origin_cannot_replace_positive_intersection(self):
        vertices = np.concatenate([x_plane(.03), x_plane(.07)])
        result = project_horizontal(vertices, np.concatenate([FACES, FACES + 4]), [.05, 0., .01], [1., 0., 0.])
        self.assertAlmostEqual(result['position'][0], .07, places=13)
        self.assertAlmostEqual(result['evidence']['distance'], .02, places=13)

    def test_four_horizontal_directions_do_not_depend_on_face_culling(self):
        for axis, sign in [(0, 1), (0, -1), (2, 1), (2, -1)]:
            with self.subTest(axis=axis, sign=sign):
                vertices = x_plane(.04 * sign)
                if axis == 2:
                    vertices = vertices[:, [2, 1, 0]]
                direction = [0., 0., 0.]
                direction[axis] = sign
                result = project_horizontal(vertices, FACES[:, ::-1], [0., .013, 0.], direction)
                self.assertAlmostEqual(result['position'][axis], .04 * sign, places=13)
                self.assert_binding(result, vertices, .013)

    def test_tilted_plane_oblique_horizontal_ray_and_barycentric_reconstruction(self):
        vertices = np.array([[.04 + .4*y + .25*z, y, z] for y, z in [(-.05, -.05), (.05, -.05), (.05, .05), (-.05, .05)]])
        origin = [0., .013, .01]
        result = project_horizontal(vertices, FACES, origin, [2., 0., 1.])
        parameter = (.04 + .4*.013 + .25*.01) / (2 - .25)
        np.testing.assert_allclose(result['position'], [2*parameter, .013, .01 + parameter], atol=1e-13, rtol=0)
        self.assert_binding(result, vertices, origin[1])

    def test_reverse_ray_and_parallel_ray_refuse_nearest_fallback(self):
        for direction in ([-1., 0., 0.], [0., 0., 1.]):
            with self.subTest(direction=direction), self.assertRaisesRegex(NoIntersectionError, 'no positive'):
                project_horizontal(x_plane(.04), FACES, [0., .01, 0.], direction)

    def test_outside_triangle_and_missing_exact_height_fail(self):
        with self.assertRaises(NoIntersectionError):
            project_horizontal(x_plane(.04), FACES, [0., .01, .2], [1., 0., 0.])
        with self.assertRaisesRegex(NoIntersectionError, 'exact target Y'):
            project_horizontal(x_plane(.04), FACES, [0., .05001, 0.], [1., 0., 0.])

    def test_distance_limit_and_boundary(self):
        with self.assertRaisesRegex(DistanceLimitError, 'exceeds max_distance'):
            project_horizontal(x_plane(.13), FACES, [0., .01, 0.], [1., 0., 0.])
        result = project_horizontal(x_plane(.04), FACES, [0., .01, 0.], [1., 0., 0.], max_distance=.04)
        self.assertAlmostEqual(result['evidence']['distance'], .04, places=13)

    def test_degenerate_triangle_is_rejected_explicitly(self):
        vertices = [[.04, 0., 0.], [.04, .01, 0.], [.04, .02, 0.]]
        with self.assertRaisesRegex(DegenerateTriangleError, 'degenerate triangle'):
            project_horizontal(vertices, [[0, 1, 2]], [0., .01, 0.], [1., 0., 0.])

    def test_invalid_direction_and_indices_are_rejected(self):
        for direction in ([0., 0., 0.], [1., 1e-16, 0.], [float('nan'), 0., 0.]):
            with self.subTest(direction=direction), self.assertRaises(SurfaceProjectionError):
                project_horizontal(x_plane(.04), FACES, [0., .01, 0.], direction)
        with self.assertRaisesRegex(SurfaceProjectionError, 'outside'):
            project_horizontal(x_plane(.04), [[0, 1, 4]], [0., .01, 0.], [1., 0., 0.])
        with self.assertRaisesRegex(SurfaceProjectionError, 'integer'):
            project_horizontal(x_plane(.04), [[0., 1., 2.]], [0., .01, 0.], [1., 0., 0.])


if __name__ == '__main__':
    unittest.main()
