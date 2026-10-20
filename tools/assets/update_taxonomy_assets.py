#!/usr/bin/env python3
"""Append authorized fascia while preserving every existing geometry payload byte."""
import hashlib
import json
import struct
from collections import Counter
from pathlib import Path
from build_models import Glb, PALETTES
from connective_supplement import meshes,write_manifest
from inspect_skin_fix import geometry
from muscle_learning import apply_learning
from taxonomy import apply_taxonomy,registry

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets'
MANIFEST=ROOT/'manifests'


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def atomic_json(path,value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');temporary.replace(path)


def append_glb(path,quality,added,structures):
    before,_=geometry(path)
    raw=path.read_bytes();jslen=struct.unpack_from('<I',raw,12)[0]
    doc=json.loads(raw[20:20+jslen]);binary=raw[28+jslen:]
    original_count=len(doc['nodes'])
    exporter=Glb(quality)
    for material in exporter.doc['materials']:
        if not any(m['name']==material['name'] for m in doc['materials']):doc['materials'].append(material)
    exporter.doc=doc;exporter.bin=bytearray(binary)
    exporter.vertices=sum(row['position']['count'] for row in before['nodes'].values())
    exporter.triangles=sum(row['indices']['count']//3 for row in before['nodes'].values())
    metadata={s['id']:s for s in structures}
    # Reclassify labels and material assignment only; old accessor/buffer bytes never change.
    for node in doc['nodes']:
        s=metadata[node['name']]
        node['extras'].update({key:s[key] for key in ['id','system','region','layer']})
        for primitive in doc['meshes'][node['mesh']]['primitives']:
            primitive['material']=next(i for i,m in enumerate(doc['materials']) if m['name']==s['system'])
    for meta,vs,fs in added:
        if meta['id'] not in before['nodes']:exporter.add(metadata[meta['id']],vs,fs)
    exporter.write(path)
    after,_=geometry(path)
    assert {key:after['nodes'][key] for key in before['nodes']}==before['nodes']
    assert list(after['nodes'])[:original_count]==list(before['nodes'])
    # All old BIN bytes survive as an identical prefix, including unreferenced padding.
    emitted=path.read_bytes();new_jslen=struct.unpack_from('<I',emitted,12)[0]
    assert emitted[28+new_jslen:28+new_jslen+len(binary)]==binary
    return before,after,{'quality':quality,'path':str(path.relative_to(ROOT)),
        'sha256':digest(path),'bytes':path.stat().st_size,'structures':len(after['nodes']),
        'vertices':exporter.vertices,'triangles':exporter.triangles}


def main():
    anatomy_path=ASSETS/'anatomy.json';anatomy=json.loads(anatomy_path.read_text())
    original={s['id']:dict(s) for s in anatomy['structures']}
    original_anatomy_sha=digest(anatomy_path)
    additions=meshes(write_obj=True);write_manifest(additions)
    for meta,_,_ in additions:
        if meta['id'] not in original:anatomy['structures'].append(meta)
    taxonomy=apply_taxonomy(anatomy['structures'])
    learning=apply_learning(anatomy['structures'])
    for s in anatomy['structures']:
        if s['id'] in original:
            assert s['bounds']==original[s['id']]['bounds'] and s['nodeName']==original[s['id']]['nodeName']
    before_models={};after_models={};outputs=[]
    for quality in ['standard','low']:
        path=ASSETS/f'models/body-{quality}.glb'
        before,after,result=append_glb(path,quality,additions,anatomy['structures'])
        before_models[quality]=before;after_models[quality]=after
        previous=next(row for row in anatomy['models'] if row['quality']==quality)
        outputs.append({**previous,**result})
    anatomy['models']=outputs
    anatomy['taxonomy']={'schemaVersion':1,'systems':registry()['systemLabels'],
        'reviewStatus':'source-checked-professional-review-pending',
        'notesZh':registry()['policyZh']}
    atomic_json(anatomy_path,anatomy)
    # The occlusion surface is byte-identical. Its enclosing GLB changed only by
    # taxonomy metadata and appended meshes; update container provenance accordingly.
    occlusion_path=MANIFEST/'model-skin-occlusion.json'
    occlusion=json.loads(occlusion_path.read_text())
    occlusion_sha=digest(ROOT/occlusion['output']['path'])
    low=next(row for row in outputs if row['quality']=='low')
    occlusion['input']['previousContainerSha256']=occlusion['input']['sha256']
    occlusion['input']['sha256']=low['sha256']
    occlusion['input']['skinGeometryPayload']=after_models['low']['nodes']['FJ2810']
    occlusion['input']['skinPayloadUnchangedDuringTaxonomyAppend']=True
    atomic_json(occlusion_path,occlusion)
    assert digest(ROOT/occlusion['output']['path'])==occlusion_sha
    supplement_path=MANIFEST/'model-supplement.json'
    supplement=json.loads(supplement_path.read_text())
    imported={row['id'] for row in supplement['addedMeshes']}
    for m,_,_ in additions:
        if m['id'] not in imported:
            supplement['addedMeshes'].append({'id':m['id'],'sourceModelName':m['sourceConceptNameEn'],
                'system':'connective','nameZh':m['nameZh'],'sourceGeometryId':m['sourceGeometryId'],'bounds':m['bounds']})
    supplement['addedMeshCountsBySystem']={'muscle':sum(row.get('system','muscle')=='muscle' for row in supplement['addedMeshes']),
        'connective':sum(row.get('system')=='connective' for row in supplement['addedMeshes'])}
    supplement['scopeZh']='明确白名单：缺失骨骼肌74个网格及真实结缔组织60个网格（筋膜、腱膜、支持带）；未导入NC器官。'
    atomic_json(supplement_path,supplement)
    build_path=MANIFEST/'model-build.json';build=json.loads(build_path.read_text())
    build.update(outputs=outputs,countsBySystem=taxonomy['countsBySystem'],
        countsByMuscleLayer=dict(Counter(s['layer'] for s in anatomy['structures'] if s['system']=='muscle')),
        muscleContentCoverage=learning,taxonomyManifest='manifests/model-taxonomy.json',
        connectiveSupplementManifest='manifests/model-connective-supplement.json',
        anatomyMetadata={'path':str(anatomy_path.relative_to(ROOT)),'sha256':digest(anatomy_path)})
    extra=['taxonomy.py','taxonomy.json','audit_taxonomy.py','connective_supplement.py','update_taxonomy_assets.py']
    pipeline={row['path'] for row in build['pipelineFiles']}
    pipeline.update(f'tools/assets/{name}' for name in extra)
    build['pipelineFiles']=[{'path':p,'sha256':digest(ROOT/p)} for p in sorted(pipeline)]
    build['licenseFiles']=[{'path':str(p.relative_to(ROOT)),'sha256':digest(p)} for p in sorted((ROOT/'licenses').glob('*')) if p.is_file()]
    build['metadataAndAppendBuild']={'entryPoint':'tools/assets/update_taxonomy_assets.py',
        'existingGeometryPolicy':'All previous node POSITION/NORMAL/index payloads and original BIN prefix retained byte-exact.',
        'newGeometryPolicy':'Explicit source fascia/aponeurosis/retinaculum meshes retained at source resolution in both tiers.'}
    atomic_json(build_path,build)
    changes=[]
    for s in anatomy['structures']:
        old=original.get(s['id'])
        if old and any(old.get(k)!=s.get(k) for k in ['nameZh','nameEn','system','layer','sourceConceptId']):
            changes.append({'id':s['id'],'before':{k:old.get(k) for k in ['nameZh','nameEn','system','layer','sourceConceptId']},
                'after':{k:s.get(k) for k in ['nameZh','nameEn','system','layer','sourceConceptId']},
                'sourceConceptNoteZh':s['sourceConceptNoteZh']})
    report={'schemaVersion':1,'status':'passed',**taxonomy,'beforeAnatomySha256':original_anatomy_sha,
        'anatomyMetadata':build['anatomyMetadata'],'totalStructures':len(anatomy['structures']),
        'existingStructures':len(original),'addedStructureIds':[m['id'] for m,_,_ in additions],
        'existingGeometryByteExact':True,'existingNodeOrderAndBoundsUnchanged':True,
        'skinOcclusionUnchanged':True,'skinOcclusionSha256':occlusion_sha,
        'modelsBefore':before_models,'modelsAfter':after_models,'changes':changes,
        'connectiveMeshes':[{'id':s['id'],'nameZh':s['nameZh'],'nameEn':s['nameEn'],'tissueType':s['tissueType'],
            'bounds':s['bounds'],'nodeName':s['nodeName']} for s in anatomy['structures'] if s['system']=='connective'],
        'learningContentCoverage':learning,
        'limitsZh':['原有节点只改分类/名称/材质索引；所有几何数组字节未动。',
            '新增筋膜使用既有几何参考配准，需专业视觉审校；局部bounds不是精准靶点。',
            '13个空间参考模型独立保留于space，不冒充实体内脏，也没有删除真实器官。']}
    atomic_json(MANIFEST/'model-taxonomy.json',report)
    print(json.dumps({'structures':len(anatomy['structures']),'countsBySystem':taxonomy['countsBySystem'],
        'anatomySha256':digest(anatomy_path),'models':outputs,'skinOcclusionUnchanged':True},ensure_ascii=False,indent=2))


if __name__=='__main__':main()
