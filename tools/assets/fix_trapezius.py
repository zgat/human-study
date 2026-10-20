#!/usr/bin/env python3
"""Replace only four historically misclassified trapezius geometry payloads."""
import json
import struct
import zipfile
from pathlib import Path
from build_models import Glb,load_obj,compact
from inspect_skin_fix import geometry
from muscle_geometry import simplify_muscles,edge_counts
from update_taxonomy_assets import atomic_json,digest

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets';MANIFEST=ROOT/'manifests'
IDS=['FJ1520','FJ1520M','FJ1521','FJ1521M']


def replace(path,quality,items,replacements):
    before,_=geometry(path)
    raw=path.read_bytes();length=struct.unpack_from('<I',raw,12)[0]
    doc=json.loads(raw[20:20+length]);blob=raw[28+length:]
    writer=Glb(quality);writer.doc=doc;writer.bin=bytearray(blob)
    for meta,source_vs,source_fs in items:
        vs,fs=replacements[quality][meta['id']]
        node=next(n for n in doc['nodes'] if n['name']==meta['id'])
        slot=node['mesh']
        # Append accessors/buffers, then point the existing named mesh at them.
        # No existing node is added/removed/reordered and no old buffer is edited.
        writer.add(meta,vs,fs)
        new_mesh=doc['meshes'].pop();doc['nodes'].pop();doc['scenes'][0]['nodes'].pop()
        doc['meshes'][slot]=new_mesh
    writer.write(path)
    after,_=geometry(path)
    assert list(before['nodes'])==list(after['nodes'])
    changed=[key for key in after['nodes'] if after['nodes'][key]!=before['nodes'][key]]
    assert changed==IDS,changed
    assert all(after['nodes'][key]==row for key,row in before['nodes'].items() if key not in IDS)
    emitted=path.read_bytes();new_length=struct.unpack_from('<I',emitted,12)[0]
    assert emitted[28+new_length:28+new_length+len(blob)]==blob
    output={'quality':quality,'path':str(path.relative_to(ROOT)),'sha256':digest(path),
        'bytes':path.stat().st_size,'structures':len(after['nodes']),
        'vertices':sum(row['position']['count'] for row in after['nodes'].values()),
        'triangles':sum(row['indices']['count']//3 for row in after['nodes'].values())}
    return before,after,output


def main():
    anatomy_path=ASSETS/'anatomy.json';anatomy=json.loads(anatomy_path.read_text())
    original={s['id']:dict(s) for s in anatomy['structures']}
    old_anatomy_sha=digest(anatomy_path)
    ground=anatomy['coordinateSystem']['sourceGroundZ']
    source=ROOT/'tools/assets/source/isa_BP3D_4.0_obj_99.zip'
    items=[]
    with zipfile.ZipFile(source) as archive:
        members={Path(name).stem:name for name in archive.namelist() if name.endswith('.obj')}
        for ident in IDS:
            vs,fs=load_obj(archive.read(members[ident]))
            vs=[(x*.001,(z-ground)*.001,-y*.001) for x,y,z in vs]
            vs,fs=compact(vs,fs)
            assert edge_counts(vs,fs)['boundaryEdges']==0
            items.append((original[ident],vs,fs))
    replacements,simplification=simplify_muscles(items,ROOT,.0015,.005)
    for row in simplification['meshes']:
        assert row['sourceTopology']['boundaryEdges']==0
        for quality in ['standard','low']:
            assert row['qualities'][quality]['topology']['boundaryEdges']==0
            assert row['qualities'][quality]['topology']['nonManifoldEdges']<=row['sourceTopology']['nonManifoldEdges']
    old_models={};new_models={};outputs=[]
    for quality in ['standard','low']:
        before,after,result=replace(ASSETS/f'models/body-{quality}.glb',quality,items,replacements)
        old_models[quality]=before;new_models[quality]=after
        old_output=next(row for row in anatomy['models'] if row['quality']==quality)
        outputs.append({**old_output,**result})
    anatomy['models']=outputs
    for s in anatomy['structures']:
        if s['id'] in IDS:s['geometrySystem']='muscle'
    atomic_json(anatomy_path,anatomy)
    registry_path=ROOT/'tools/assets/taxonomy.json';registry=json.loads(registry_path.read_text())
    for ident in IDS:registry['entries'][ident]['geometrySystem']='muscle'
    atomic_json(registry_path,registry)
    skin_path=MANIFEST/'model-skin-occlusion.json';skin=json.loads(skin_path.read_text())
    skin_sha=digest(ROOT/skin['output']['path'])
    skin['input']['previousContainerSha256']=skin['input']['sha256']
    skin['input']['sha256']=next(row['sha256'] for row in outputs if row['quality']=='low')
    skin['input']['skinGeometryPayload']=new_models['low']['nodes']['FJ2810']
    skin['input']['skinPayloadUnchangedDuringTrapeziusFix']=True
    atomic_json(skin_path,skin)
    assert digest(ROOT/skin['output']['path'])==skin_sha
    taxonomy_path=MANIFEST/'model-taxonomy.json';taxonomy=json.loads(taxonomy_path.read_text())
    taxonomy['initialAppendGeometryByteExact']=taxonomy['existingGeometryByteExact']
    taxonomy['existingGeometryByteExact']=False
    taxonomy['geometryExceptionIds']=IDS
    taxonomy['existingGeometryByteExactOutsideExceptions']=True
    taxonomy['modelsAfter']=new_models
    taxonomy['anatomyMetadata']={'path':str(anatomy_path.relative_to(ROOT)),'sha256':digest(anatomy_path)}
    taxonomy['limitsZh']=[text for text in taxonomy['limitsZh'] if '所有几何数组字节未动' not in text]
    taxonomy['limitsZh'].append('追加后另经明确授权，只将四片误归内脏的斜方肌分部切换为源闭合拓扑安全简化；其余原1227片及新8片几何数组字节不变。')
    atomic_json(taxonomy_path,taxonomy)
    build_path=MANIFEST/'model-build.json';build=json.loads(build_path.read_text())
    build['outputs']=outputs;build['anatomyMetadata']=taxonomy['anatomyMetadata']
    build['trapeziusFixManifest']='manifests/model-trapezius-fix.json'
    build['metadataAndAppendBuild']['authorizedGeometryExceptionIds']=IDS
    build['metadataAndAppendBuild']['geometryExceptionEntryPoint']='tools/assets/fix_trapezius.py'
    files={row['path'] for row in build['pipelineFiles']};files.add('tools/assets/fix_trapezius.py')
    build['pipelineFiles']=[{'path':p,'sha256':digest(ROOT/p)} for p in sorted(files)]
    atomic_json(build_path,build)
    report={'schemaVersion':1,'status':'passed','purposeZh':'修正四片被误归内脏而漏过肌肉闭合安全简化的历史聚类开口。',
        'replacedIds':IDS,'beforeAnatomySha256':old_anatomy_sha,'anatomyMetadata':build['anatomyMetadata'],
        'modelsBefore':old_models,'modelsAfter':new_models,'sourceArchiveSha256':digest(source),
        'simplification':simplification,'other1235GeometryPayloadsByteExact':True,
        'nodeIdsOrderBoundsNamesClassificationUnchanged':True,
        'skinOcclusionUnchanged':True,'skinOcclusionSha256':skin_sha,
        'limitsZh':['仅使用真实源网格；精确重合点合并与受误差预算/开边/非流形约束的边折叠，不填孔或补造。',
            '既有几何数组不在原位重写；新增四片访问器并更新原节点引用，旧BIN仍保留为一致前缀。',
            '需要医学专业视觉审校；几何拓扑检查不等于临床验收。']}
    atomic_json(MANIFEST/'model-trapezius-fix.json',report)
    print(json.dumps({'anatomyMetadata':build['anatomyMetadata'],'outputs':outputs,
        'other1235GeometryPayloadsByteExact':True,'skinOcclusionSha256':skin_sha},ensure_ascii=False,indent=2))


if __name__=='__main__':main()
