// Topology-preserving edge collapse on genuine source meshes. No voxel weld / hole fill.
import fs from 'node:fs';
import {MeshoptSimplifier} from './vendor/meshopt_simplifier.mjs';

const [indexPath, binaryPath, outputIndexPath, outputBinaryPath] = process.argv.slice(2);
const input = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
const bytes = fs.readFileSync(binaryPath);
await MeshoptSimplifier.ready;
let offset = 0;
const buffers = [], rows = [];
const flags = ['LockBorder', 'ErrorAbsolute'];
function edgeCounts(indices, vertexCount) {
    const edges = new Map();
    for (let i = 0; i < indices.length; i += 3) {
        const a = indices[i], b = indices[i+1], c = indices[i+2];
        if (a === b || b === c || c === a) continue;
        for (const [p,q] of [[a,b],[b,c],[c,a]]) {
            const key = Math.min(p,q) * vertexCount + Math.max(p,q);
            edges.set(key, (edges.get(key) || 0) + 1);
        }
    }
    let boundaryEdges = 0, nonManifoldEdges = 0;
    for (const n of edges.values()) { boundaryEdges += n === 1; nonManifoldEdges += n > 2; }
    return {boundaryEdges, nonManifoldEdges};
}
for (const mesh of input.meshes) {
    const sourcePositions = Float32Array.from({length: mesh.vertices * 3}, (_, i) =>
        bytes.readFloatLE(mesh.positionOffset + i * 4));
    const sourceIndices = Uint32Array.from({length: mesh.triangles * 3}, (_, i) =>
        bytes.readUInt32LE(mesh.indexOffset + i * 4));
    // Source OBJs repeat exact geometric vertices at indexed seams. Weld only byte-equal
    // float32 positions; unlike voxel clustering this never merges nearby surfaces or moves them.
    const positions = [], representatives = [], remap = [], weld = new Map();
    for (let i = 0; i < sourcePositions.length; i += 3) {
        const key = `${sourcePositions[i]},${sourcePositions[i+1]},${sourcePositions[i+2]}`;
        if (!weld.has(key)) {
            weld.set(key, weld.size); representatives.push(i/3);
            positions.push(sourcePositions[i],sourcePositions[i+1],sourcePositions[i+2]);
        }
        remap.push(weld.get(key));
    }
    const p = new Float32Array(positions);
    const indices = Uint32Array.from(sourceIndices, i => remap[i]);
    const originalTopology = edgeCounts(indices, positions.length / 3);
    const row = {id: mesh.id, weldedSourceVertices: positions.length / 3, qualities: {}};
    for (const [quality, settings] of Object.entries(input.qualities)) {
        const attempts = [];
        let result = sourceIndices, error = 0, target = mesh.triangles;
        const ratios = settings.maxErrorMetres <= 0 || mesh.triangles < 120 ? [] :
            [.05, .10, .175, .3, .55, .75, .9];
        for (const ratio of ratios) {
            target = Math.max(4, Math.floor(mesh.triangles * ratio));
            const [candidate, candidateError] = MeshoptSimplifier.simplify(
                indices, p, 3, target * 3, settings.maxErrorMetres, flags);
            const topology = edgeCounts(candidate, positions.length / 3);
            const accepted = topology.boundaryEdges <= originalTopology.boundaryEdges &&
                topology.nonManifoldEdges <= originalTopology.nonManifoldEdges &&
                candidateError <= settings.maxErrorMetres * 1.00001;
            attempts.push({targetRatio:ratio, triangles:candidate.length/3,
                estimatedErrorMetres:candidateError, topology, accepted});
            if (accepted) {
                result = Uint32Array.from(candidate, i => representatives[i]);
                error = candidateError;
                break;
            }
        }
        const out = Buffer.alloc(result.length * 4);
        for (let i = 0; i < result.length; i++) out.writeUInt32LE(result[i], i * 4);
        row.qualities[quality] = {offset, triangles: result.length / 3,
            sourceTriangles: mesh.triangles, targetTriangles: target,
            estimatedErrorMetres: error, attempts,
            fallbackToSource: attempts.length > 0 && !attempts.some(a => a.accepted)};
        offset += out.length;
        buffers.push(out);
    }
    rows.push(row);
}
fs.writeFileSync(outputBinaryPath, Buffer.concat(buffers));
fs.writeFileSync(outputIndexPath, JSON.stringify({flags, meshes: rows}));
