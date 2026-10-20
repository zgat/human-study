// Export an actual skin surface for CPU occlusion, without modifying display GLBs.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {MeshoptSimplifier} from './vendor/meshopt_simplifier.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const inputPath = 'app/src/main/assets/models/body-low.glb';
const outputPath = 'app/src/main/assets/models/skin-occlusion.bin';
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const source = fs.readFileSync(path.join(root,inputPath));
if (source.toString('ascii',0,4) !== 'glTF' || source.readUInt32LE(4) !== 2) throw Error('GLB header');
const jsonLength = source.readUInt32LE(12);
const gltf = JSON.parse(source.toString('utf8',20,20+jsonLength));
const binaryStart = 28+jsonLength;
const node = gltf.nodes.find(n=>n.name==='FJ2810');
const primitive = gltf.meshes[node.mesh].primitives[0];
function accessor(index) {
  const a = gltf.accessors[index], view=gltf.bufferViews[a.bufferView];
  const start=binaryStart+(view.byteOffset||0)+(a.byteOffset||0);
  const n=a.count*(a.type==='VEC3'?3:1);
  if(a.componentType===5126) return Float32Array.from({length:n},(_,i)=>source.readFloatLE(start+i*4));
  if(a.componentType===5123) return Uint32Array.from({length:n},(_,i)=>source.readUInt16LE(start+i*2));
  if(a.componentType===5125) return Uint32Array.from({length:n},(_,i)=>source.readUInt32LE(start+i*4));
  throw Error('Unsupported component');
}
const positions=accessor(primitive.attributes.POSITION), indices=accessor(primitive.indices);
await MeshoptSimplifier.ready;
const flags=['LockBorder','ErrorAbsolute'];
const [simplified,error] = MeshoptSimplifier.simplify(indices,positions,3,150000,.005,flags);
const triangles=simplified.length/3;
const output=Buffer.alloc(8+triangles*36);
if(output.length>2000000) throw Error(`Occlusion mesh too large: ${triangles} triangles, ${output.length} bytes`);
output.write('MSO1',0,'ascii'); output.writeUInt32LE(triangles,4);
const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
for(let i=0;i<simplified.length;++i) for(let axis=0;axis<3;++axis) {
  const v=positions[simplified[i]*3+axis];
  if(!Number.isFinite(v)) throw Error('Non-finite coordinate');
  output.writeFloatLE(v,8+(i*3+axis)*4);
  min[axis]=Math.min(min[axis],v); max[axis]=Math.max(max[axis],v);
}
fs.writeFileSync(path.join(root,outputPath+'.tmp'),output);
fs.renameSync(path.join(root,outputPath+'.tmp'),path.join(root,outputPath));
const vendor='tools/assets/vendor/meshopt_simplifier.mjs';
const manifest={
  schemaVersion:1,modelId:'bodyparts3d-male-v4',format:{magic:'MSO1',endianness:'little',headerBytes:8,
    layout:'magic[4], uint32 triangleCount, triangleCount * 9 float32 coordinates',units:'metres',
    axes:{up:'+Y',anterior:'+Z',anatomicalLeft:'+X'}},
  input:{path:inputPath,sha256:hash(source),nodeName:'FJ2810',triangles:indices.length/3,
    geometrySource:'Official BodyParts3D 4.0 FJ2810 skin; low-tier skin exactly reuses standard-tier 1.5 mm clustered geometry'},
  simplifier:{name:'meshoptimizer MeshoptSimplifier',source:'https://github.com/zeux/meshoptimizer/tree/master/js',
    moduleUrl:'https://raw.githubusercontent.com/zeux/meshoptimizer/master/js/meshopt_simplifier.js',
    path:vendor,sha256:hash(fs.readFileSync(path.join(root,vendor))),license:'MIT',flags,
    targetTriangles:50000,maxEstimatedErrorMetres:.005,returnedEstimatedErrorMetres:error,
    vertexMovement:'No vertex movement in this second simplification; output reuses low GLB positions.'},
  output:{path:outputPath,bytes:output.length,triangles,sha256:hash(output),bounds:{min,max}},
  pipelineFiles:['tools/assets/export_occlusion.mjs','tools/assets/vendor/meshopt_simplifier.mjs',
    'licenses/meshoptimizer-MIT.txt'].map(p=>({path:p,sha256:hash(fs.readFileSync(path.join(root,p)))})),
  limitationsZh:[
    '简化器返回的是相对输入皮肤的近似误差指标，不是对原始皮肤测得的严格 Hausdorff 距离。',
    '输入皮肤与标准显示档相同，经过 1.5 mm 体素顶点聚合；原始皮肤本身存在边界孔洞，遮挡壳不保证封闭。',
    '此网格仅供穴位遮挡计算，不用于诊断、测量或医学定位；显示模型及节点完全未改动。'
  ]
};
fs.writeFileSync(path.join(root,'manifests/model-skin-occlusion.json.tmp'),JSON.stringify(manifest,null,2)+'\n');
fs.renameSync(path.join(root,'manifests/model-skin-occlusion.json.tmp'),path.join(root,'manifests/model-skin-occlusion.json'));
console.log(JSON.stringify(manifest.output));
console.log(`simplifier estimated error: ${error} metres`);
