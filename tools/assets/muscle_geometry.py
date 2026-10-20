"""Bounded, topology-preserving simplification of source muscle meshes."""
import array
import collections
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


def edge_counts(vs, fs):
    welded = {}
    remap = [welded.setdefault(tuple(v), len(welded)) for v in vs]
    edges = collections.Counter()
    for face in fs:
        a, b, c = [remap[i] for i in face]
        if len({a, b, c}) < 3:
            continue
        for p, q in ((a, b), (b, c), (c, a)):
            edges[tuple(sorted((p, q)))] += 1
    return {'boundaryEdges': sum(n == 1 for n in edges.values()),
            'nonManifoldEdges': sum(n > 2 for n in edges.values())}


def simplify_muscles(selected, root, standard_error_metres, low_error_metres):
    """Never move vertices; preserve each mesh's existing boundary / manifold constraints."""
    work = root/'.tools/model-work'
    work.mkdir(parents=True, exist_ok=True)
    source = {}
    qualities = {'standard': {'maxErrorMetres': standard_error_metres},
                 'low': {'maxErrorMetres': low_error_metres}}
    with tempfile.TemporaryDirectory(prefix='muscle-', dir=work) as directory:
        folder = Path(directory)
        metadata = []
        with (folder/'source.bin').open('wb') as f:
            for meta, vs, fs in selected:
                if meta['system'] != 'muscle':
                    continue
                pos = array.array('f', (x for v in vs for x in v))
                indices = array.array('I', (i for face in fs for i in face))
                if sys.byteorder != 'little':
                    pos.byteswap(); indices.byteswap()
                position_offset = f.tell(); f.write(pos.tobytes())
                index_offset = f.tell(); f.write(indices.tobytes())
                # Match exact float32 values used by the simplifier and emitted GLB.
                flat = [x[0] for x in struct.iter_unpack('<f', pos.tobytes())]
                source[meta['id']] = ([tuple(flat[i:i+3]) for i in range(0, len(flat), 3)], fs)
                metadata.append({'id': meta['id'], 'vertices': len(vs), 'triangles': len(fs),
                                 'positionOffset': position_offset, 'indexOffset': index_offset})
        (folder/'source.json').write_text(json.dumps({'qualities': qualities, 'meshes': metadata}))
        subprocess.run(['node', str(root/'tools/assets/simplify_muscles.mjs'),
                        str(folder/'source.json'), str(folder/'source.bin'),
                        str(folder/'result.json'), str(folder/'result.bin')], check=True)
        result = json.loads((folder/'result.json').read_text())
        binary = (folder/'result.bin').read_bytes()
    geometry = {q: {} for q in qualities}
    for row in result['meshes']:
        vs, original_faces = source[row['id']]
        original_topology = edge_counts(vs, original_faces)
        row['sourceTopology'] = original_topology
        for quality, settings in row['qualities'].items():
            offset, count = settings['offset'], settings['triangles'] * 3
            indices = [x[0] for x in struct.iter_unpack('<I', binary[offset:offset+count*4])]
            fs = [tuple(indices[i:i+3]) for i in range(0, len(indices), 3)]
            topology = edge_counts(vs, fs)
            settings['candidateTopology'] = topology
            # A simplifier must never open a source-closed mesh or add non-manifold edges.
            # Retain genuine source geometry if that check or the bounded error fails.
            acceptable = (topology['boundaryEdges'] <= original_topology['boundaryEdges']
                          and topology['nonManifoldEdges'] <= original_topology['nonManifoldEdges']
                          and settings['estimatedErrorMetres'] <= qualities[quality]['maxErrorMetres'] * 1.00001)
            settings['fallbackToSource'] = settings.get('fallbackToSource', False) or not acceptable
            if not acceptable:
                fs = original_faces
                settings['estimatedErrorMetres'] = 0
                topology = original_topology
            used = sorted({i for face in fs for i in face})
            remap = {old: new for new, old in enumerate(used)}
            geometry[quality][row['id']] = ([vs[i] for i in used],
                                          [tuple(remap[i] for i in face) for face in fs])
            settings.update(triangles=len(fs), topology=topology)
            settings.pop('offset')
    result.update(qualities=qualities, algorithm='meshoptimizer 1.3 topology-preserving edge collapse',
                  positions='Original source positions rounded to float32; no vertex movement.',
                  preprocessing='Merge only exact float32-equal source vertex positions; never cluster nearby coordinates.',
                  candidateTargetRatios=[.05,.10,.175,.3,.55,.75,.9],
                  sourceFile='tools/assets/vendor/meshopt_simplifier.mjs')
    return geometry, result
