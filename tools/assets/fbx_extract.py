#!/usr/bin/env python3
"""Minimal read-only FBX binary geometry reader (7.4 / 7.5), stdlib only.

This is deliberately limited to static meshes. Unsupported deformation / pivot
transforms are reported instead of silently inventing transformed geometry.
"""
from __future__ import annotations
import array
import struct
import zlib
import math
from pathlib import Path


class Fbx:
    def __init__(self, path):
        self.data = Path(path).read_bytes()
        if not self.data.startswith(b'Kaydara FBX Binary'):
            raise ValueError('Only binary FBX is supported')
        self.version = struct.unpack_from('<I', self.data, 23)[0]
        self.wide = self.version >= 7500
        self.header = 25 if self.wide else 13
        self.pos = 27
        self.nodes = []
        while self.pos < len(self.data):
            node = self.node()
            if node is None:
                break
            self.nodes.append(node)

    def value(self):
        t = chr(self.data[self.pos]); self.pos += 1
        formats = {'Y': 'h', 'C': '?', 'I': 'i', 'F': 'f', 'D': 'd', 'L': 'q'}
        if t in formats:
            fmt = '<' + formats[t]
            v = struct.unpack_from(fmt, self.data, self.pos)[0]
            self.pos += struct.calcsize(fmt)
            return v
        if t in ['S', 'R']:
            n = struct.unpack_from('<I', self.data, self.pos)[0]; self.pos += 4
            v = self.data[self.pos:self.pos+n]; self.pos += n
            return v.decode(errors='replace') if t == 'S' else v
        if t in 'fdlibc':
            n, encoding, size = struct.unpack_from('<III', self.data, self.pos); self.pos += 12
            raw = self.data[self.pos:self.pos+size]; self.pos += size
            raw = zlib.decompress(raw) if encoding == 1 else raw
            v = array.array({'f': 'f', 'd': 'd', 'l': 'q', 'i': 'i', 'b': 'b', 'c': 'b'}[t])
            v.frombytes(raw)
            if len(v) != n:
                raise ValueError('FBX array length mismatch')
            return v
        raise ValueError('Unsupported FBX value type ' + t)

    def node(self):
        end, count, prop_size, name_len = struct.unpack_from('<QQQB' if self.wide else '<IIIB', self.data, self.pos)
        self.pos += self.header
        if end == 0:
            return None
        name = self.data[self.pos:self.pos+name_len].decode(errors='replace'); self.pos += name_len
        values = [self.value() for _ in range(count)]
        children = []
        while self.pos < end:
            child = self.node()
            if child is None:
                break
            children.append(child)
        self.pos = end
        return {'name': name, 'values': values, 'children': children}

    def find(self, name):
        return next(n for n in self.nodes if n['name'] == name)


def properties(node):
    result = {}
    for c in node['children']:
        if c['name'] == 'Properties70':
            for p in c['children']:
                result[p['values'][0]] = p['values'][4:]
    return result


def identity():
    return [[float(i == j) for j in range(4)] for i in range(4)]


def multiply(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def local_transform(node):
    p = properties(node)
    for key in ['RotationOffset', 'RotationPivot', 'ScalingOffset', 'ScalingPivot',
                'PreRotation', 'PostRotation', 'GeometricTranslation', 'GeometricRotation']:
        if key in p and any(abs(x) > 1e-5 for x in p[key]):
            raise ValueError('Unsupported nonzero FBX transform: ' + key)
    if p.get('RotationOrder', [0])[0] != 0:
        raise ValueError('Unsupported FBX Euler rotation order')
    t = p.get('Lcl Translation', [0., 0., 0.])
    r = p.get('Lcl Rotation', [0., 0., 0.])
    s = p.get('Lcl Scaling', [1., 1., 1.])
    out = identity()
    for axis in range(3):
        rr = identity(); a = math.radians(r[axis]); c, si = math.cos(a), math.sin(a)
        j, k = (axis + 1) % 3, (axis + 2) % 3
        rr[j][j] = rr[k][k] = c; rr[j][k] = -si; rr[k][j] = si
        out = multiply(rr, out)
    for i in range(3):
        for j in range(3):
            out[i][j] *= s[j]
        out[i][3] = t[i]
    return out


def static_meshes(fbx):
    objects = fbx.find('Objects')['children']
    models = {n['values'][0]: n for n in objects if n['name'] == 'Model'}
    geometries = {n['values'][0]: n for n in objects if n['name'] == 'Geometry'}
    parents, mesh_models = {}, {}
    for n in fbx.find('Connections')['children']:
        values = n['values']
        if values[0] != 'OO':
            continue
        child, parent = values[1:3]
        if child in models:
            parents[child] = parent
        if child in geometries:
            mesh_models.setdefault(child, []).append(parent)
    transforms = {}
    def world(mid):
        if mid not in models:
            return identity()
        if mid not in transforms:
            transforms[mid] = multiply(world(parents.get(mid)), local_transform(models[mid]))
        return transforms[mid]
    for gid, geometry in geometries.items():
        arrays = {c['name']: c['values'][0] for c in geometry['children'] if c['values']}
        if 'Vertices' not in arrays or 'PolygonVertexIndex' not in arrays:
            continue
        raw_vs, raw_fs = arrays['Vertices'], arrays['PolygonVertexIndex']
        faces, polygon = [], []
        for i in raw_fs:
            polygon.append(-i - 1 if i < 0 else i)
            if i < 0:
                for j in range(1, len(polygon)-1):
                    faces.append((polygon[0], polygon[j], polygon[j+1]))
                polygon = []
        for mid in mesh_models.get(gid, []):
            matrix = world(mid)
            vertices = []
            for j in range(0, len(raw_vs), 3):
                v = list(raw_vs[j:j+3]) + [1.]
                vertices.append(tuple(sum(matrix[i][k] * v[k] for k in range(4)) * .01 for i in range(3)))
            # FBX global coordinates are centimetres, Y up and anterior +Z.
            name = models[mid]['values'][1].split('\0')[0]
            det = (matrix[0][0] * (matrix[1][1]*matrix[2][2]-matrix[1][2]*matrix[2][1])
                   - matrix[0][1] * (matrix[1][0]*matrix[2][2]-matrix[1][2]*matrix[2][0])
                   + matrix[0][2] * (matrix[1][0]*matrix[2][1]-matrix[1][1]*matrix[2][0]))
            transformed_faces = [(a,c,b) for a,b,c in faces] if det < 0 else faces
            yield {'id': mid, 'geometryId': gid, 'name': name, 'vertices': vertices,
                   'faces': transformed_faces, 'fbxMatrixCentimetres': matrix}


if __name__ == '__main__':
    import sys
    f = Fbx(sys.argv[1])
    objects = f.find('Objects')['children']
    print('FBX', f.version, 'global', properties(f.find('GlobalSettings')))
    print('objects', {k: sum(n['name'] == k for n in objects) for k in ['Geometry', 'Model']})
    for n in objects:
        if n['name'] == 'Model':
            print('MODEL', n['values'][:3], properties(n))
        if n['name'] == 'Geometry':
            v = next(c['values'][0] for c in n['children'] if c['name'] == 'Vertices')
            print('GEOMETRY', n['values'][:3], 'verts', len(v)//3,
                  'bounds', [[min(v[i::3]), max(v[i::3])] for i in range(3)])
