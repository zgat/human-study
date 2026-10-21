"""Exact-height, two-sided projection onto an existing source triangle mesh.

This helper supplies geometry only. The caller chooses anatomical landmarks, a
horizontal origin inside its intended body region, and the projection direction.
Input face indices are zero-based; returned sourceTriangle indices are one-based.
No failed ray is replaced with a nearest vertex or a different height.
"""
from __future__ import annotations

import math
import numpy as np


class SurfaceProjectionError(ValueError):
    """The requested exact horizontal projection could not be established."""


class DegenerateTriangleError(SurfaceProjectionError):
    """The input mesh contains a triangle without a reliable geometric normal."""


class NoIntersectionError(SurfaceProjectionError):
    """No positive, non-coplanar ray/triangle intersection exists."""


class DistanceLimitError(SurfaceProjectionError):
    """The nearest forward intersection exceeds the caller's permitted distance."""


def project_horizontal(vertices, faces, origin, direction, max_distance=.12):
    """Return an exact-Y hit and auditable source binding as a dictionary.

    Keys: ``position``, ``sourceTriangle`` (one-based vertex IDs), ``barycentric``,
    ``normal`` and ``evidence``. Distance is measured in the input coordinate units
    (metres in this project), independently of direction magnitude. ``direction[1]``
    must be exactly zero. Only positive distances greater than 1e-10 are accepted,
    so a numerical self-hit at the origin cannot replace an outward intersection.

    Both face windings intersect. The returned unit normal is the geometric face
    normal flipped, if necessary, to face along the requested ray. This convention
    is not a claim of anatomical outwardness; original winding is retained in
    evidence. Degenerate input triangles are rejected explicitly, never skipped.
    """
    vertices = np.asarray(vertices, dtype=float)
    faces = np.asarray(faces)
    origin = np.asarray(origin, dtype=float)
    direction = np.asarray(direction, dtype=float)
    if vertices.ndim != 2 or vertices.shape[1] != 3 or not len(vertices):
        raise SurfaceProjectionError('vertices must be a non-empty N x 3 array')
    if faces.ndim != 2 or faces.shape[1] != 3 or not len(faces):
        raise SurfaceProjectionError('faces must be a non-empty M x 3 triangle-index array')
    if not np.issubdtype(faces.dtype, np.integer):
        raise SurfaceProjectionError('faces must contain zero-based integer indices')
    if faces.min() < 0 or faces.max() >= len(vertices):
        raise SurfaceProjectionError('face vertex index is outside the zero-based vertices array')
    if origin.shape != (3,) or direction.shape != (3,):
        raise SurfaceProjectionError('origin and direction must each contain three coordinates')
    if not np.isfinite(vertices).all() or not np.isfinite(origin).all() or not np.isfinite(direction).all():
        raise SurfaceProjectionError('vertices, origin and direction must contain only finite values')
    if direction[1] != 0:
        raise SurfaceProjectionError('horizontal ray requires direction dy exactly 0')
    length = float(np.linalg.norm(direction))
    if not math.isfinite(length) or length == 0:
        raise SurfaceProjectionError('direction must have a finite, non-zero length')
    if not math.isfinite(max_distance) or max_distance <= 0:
        raise SurfaceProjectionError('max_distance must be finite and positive')
    direction = direction / length
    triangle = vertices[faces]
    edge1 = triangle[:, 1] - triangle[:, 0]
    edge2 = triangle[:, 2] - triangle[:, 0]
    normals = np.cross(edge1, edge2)
    normal_lengths = np.linalg.norm(normals, axis=1)
    edge_scale = np.linalg.norm(edge1, axis=1) * np.linalg.norm(edge2, axis=1)
    epsilon = np.finfo(float).eps * 32
    degenerate = normal_lengths <= epsilon * edge_scale
    if np.any(degenerate):
        index = int(np.flatnonzero(degenerate)[0])
        raise DegenerateTriangleError(f'degenerate triangle at zero-based face {index}, source vertices {(faces[index] + 1).tolist()}')
    # A horizontal ray can only intersect triangles spanning exactly origin Y.
    # This is a rejection filter, not a slab or a nearest-height substitution.
    selected = np.flatnonzero((triangle[:, :, 1].min(axis=1) <= origin[1]) &
                             (triangle[:, :, 1].max(axis=1) >= origin[1]))
    if not len(selected):
        raise NoIntersectionError(f'no triangle spans exact target Y={origin[1]:.12g}')
    tri = triangle[selected]
    e1, e2 = edge1[selected], edge2[selected]
    h = np.cross(direction, e2)
    determinant = np.einsum('ij,ij->i', e1, h)
    nonparallel = abs(determinant) > epsilon * edge_scale[selected]
    inverse = np.zeros_like(determinant)
    inverse[nonparallel] = 1.0 / determinant[nonparallel]
    delta = origin - tri[:, 0]
    u = inverse * np.einsum('ij,ij->i', delta, h)
    q = np.cross(delta, e1)
    v = inverse * (q @ direction)
    distance = inverse * np.einsum('ij,ij->i', e2, q)
    barycentric_tolerance = 1e-10
    legal = nonparallel & (u >= -barycentric_tolerance) & (v >= -barycentric_tolerance) & \
        (u + v <= 1 + barycentric_tolerance) & (distance > 1e-10)
    if not np.any(legal):
        raise NoIntersectionError('no positive two-sided intersection on the requested horizontal ray')
    candidates = np.flatnonzero(legal)
    local = int(candidates[np.argmin(distance[candidates])])
    index = int(selected[local])
    nearest = float(distance[local])
    if nearest > max_distance:
        raise DistanceLimitError(f'nearest forward intersection distance {nearest:.12g} exceeds max_distance {max_distance:.12g}')
    weights = np.array([1 - u[local] - v[local], u[local], v[local]])
    # Only remove floating-point edge overshoot; never move the ray to another face.
    weights = np.maximum(weights, 0)
    weights /= weights.sum()
    position = origin + nearest * direction
    position[1] = origin[1]
    reconstructed = weights @ triangle[index]
    residual = float(np.linalg.norm(reconstructed - position))
    if residual > 1e-10:
        raise SurfaceProjectionError(f'barycentric reconstruction residual {residual:.12g} exceeds tolerance at face {index}')
    winding_normal = normals[index] / normal_lengths[index]
    flipped = float(winding_normal @ direction) < 0
    normal = -winding_normal if flipped else winding_normal
    return {
        'position': position.tolist(),
        'sourceTriangle': (faces[index] + 1).tolist(),
        'barycentric': weights.tolist(),
        'normal': normal.tolist(),
        'evidence': {
            'construction': 'nearest-positive-two-sided-horizontal-source-triangle-ray',
            'sourceFaceIndex': index,
            'sourceFaceIndexConvention': 'zero-based',
            'sourceTriangleConvention': 'one-based source vertex IDs',
            'origin': origin.tolist(),
            'directionUnit': direction.tolist(),
            'targetY': float(origin[1]),
            'distance': nearest,
            'maxDistance': float(max_distance),
            'sourceWindingNormal': winding_normal.tolist(),
            'normalFlippedToFaceRay': flipped,
            'normalConvention': 'unit face normal oriented along ray; anatomical outwardness is caller responsibility',
            'reconstructionResidual': residual,
            'heightPreservedExactly': bool(position[1] == origin[1]),
            'nearestVertexFallback': False,
        },
    }
