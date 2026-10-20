#!/usr/bin/env python3
"""Append source connective-tissue sheets without rebuilding existing anatomy.

The 1239 existing nodes, primitives, material assignments and BIN prefix remain
byte-identical.  This protects all existing triangle/barycentric anchors.  Raw
FBX is needed only to regenerate assets, never for an ordinary Android build.
"""
import hashlib
import json
import math
import struct
from collections import Counter
from pathlib import Path

from build_models import Glb, PALETTES
from connective_supplement import meshes, write_manifest, EXCLUDED_SOURCE_IDS
from inspect_skin_fix import geometry

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets'
MANIFEST=ROOT/'manifests'
REPORT=MANIFEST/'model-fascia-expansion.json'
BASE_COUNT=1239


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def object_digest(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def write_json(path,value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');temporary.replace(path)
def archive(path):
    if not path.exists():return
    target=path.with_name(path.stem+'-before-fascia-expansion'+path.suffix)
    if not target.exists():target.write_bytes(path.read_bytes())


def parse_glb(path):
    raw=path.read_bytes()
    magic,version,total=struct.unpack_from('<4sII',raw)
    assert (magic,version,total)==(b'glTF',2,len(raw))
    length,kind=struct.unpack_from('<I4s',raw,12);assert kind==b'JSON'
    document=json.loads(raw[20:20+length])
    binary_size,kind=struct.unpack_from('<I4s',raw,20+length);assert kind==b'BIN\0'
    binary=raw[28+length:];assert len(binary)==binary_size
    return document,binary


def source_stats(vertices,faces):
    assert vertices and faces
    assert all(math.isfinite(x) for v in vertices for x in v)
    assert all(len(f)==3 and all(0<=i<len(vertices) for i in f) for f in faces)
    edges=Counter();degenerate=0
    for a,b,c in faces:
        u=[vertices[b][i]-vertices[a][i] for i in range(3)]
        v=[vertices[c][i]-vertices[a][i] for i in range(3)]
        area_squared=sum((u[(i+1)%3]*v[(i+2)%3]-u[(i+2)%3]*v[(i+1)%3])**2 for i in range(3))
        degenerate+=int(area_squared<=1e-30)
        for edge in [(a,b),(b,c),(c,a)]:edges[tuple(sorted(edge))]+=1
    return {'vertices':len(vertices),'triangles':len(faces),
        'boundaryEdges':sum(count==1 for count in edges.values()),
        'nonManifoldEdges':sum(count>2 for count in edges.values()),
        'zeroOrNearZeroAreaTriangles':degenerate,
        'boundaryPolicy':'Source sheets may have natural boundary edges; no artificial capping or force-line geometry added.'}


def append_glb(path,quality,added):
    original_doc,original_bin=parse_glb(path)
    original_fingerprints,_=geometry(path)
    exporter=Glb(quality)
    assert [m['name'] for m in original_doc['materials']]==list(PALETTES)
    exporter.doc=original_doc;exporter.bin=bytearray(original_bin)
    exporter.vertices=sum(node['position']['count'] for node in original_fingerprints['nodes'].values())
    exporter.triangles=sum(node['indices']['count']//3 for node in original_fingerprints['nodes'].values())
    # Retain exact JSON values of every existing node/mesh/accessor/material.
    original_arrays={key:json.loads(json.dumps(original_doc[key])) for key in
        ['nodes','meshes','accessors','bufferViews','materials']}
    original_scene=list(original_doc['scenes'][0]['nodes'])
    for metadata,vertices,faces in added:exporter.add(metadata,vertices,faces)
    exporter.write(path)
    assert path.stat().st_size<100*1024*1024, 'GLB exceeds normal GitHub blob limit'
    after_doc,after_bin=parse_glb(path)
    for key,values in original_arrays.items():assert after_doc[key][:len(values)]==values,key
    assert after_doc['scenes'][0]['nodes'][:len(original_scene)]==original_scene
    assert after_bin[:len(original_bin)]==original_bin
    current_fingerprints,_=geometry(path)
    assert {key:current_fingerprints['nodes'][key] for key in original_fingerprints['nodes']}==original_fingerprints['nodes']
    return original_fingerprints,current_fingerprints,{
        'quality':quality,'path':str(path.relative_to(ROOT)),'sha256':digest(path),'bytes':path.stat().st_size,
        'structures':len(after_doc['nodes']),'vertices':exporter.vertices,'triangles':exporter.triangles,
        'existingBinPrefixSha256':hashlib.sha256(original_bin).hexdigest(),
        'existingBinPrefixBytes':len(original_bin),'existingBinPrefixUnchanged':True,
        'existingNodePrimitiveMaterialAccessorJsonUnchanged':True}


def main():
    anatomy_path=ASSETS/'anatomy.json'
    anatomy=json.loads(anatomy_path.read_text())
    original_structures=anatomy['structures']
    original_ids={s['id'] for s in original_structures}
    assert len(original_structures)==BASE_COUNT, 'Append is single-use; current assets are not the expected 1239-node baseline.'
    original_anatomy_digest=digest(anatomy_path)
    original_metadata_digest=object_digest(original_structures)
    items=meshes(write_obj=True)
    additions=[item for item in items if item[0]['id'] not in original_ids]
    assert len(items)==60 and len(additions)==52
    # Archive the affected provenance reports before replacing current pointers.
    for name in ['model-connective-supplement.json','model-build.json','model-supplement.json',
                 'model-skin-occlusion.json','model-taxonomy.json','model-validation.json']:
        archive(MANIFEST/name)
    # The registry is source-controlled so a future full model build knows the
    # same explicit allowlist. Existing classification rows are untouched.
    taxonomy_path=ROOT/'tools/assets/taxonomy.json'
    registry=json.loads(taxonomy_path.read_text());previous_entries=dict(registry['entries'])
    for metadata,_,_ in additions:
        registry['entries'][metadata['id']]={key:metadata[key] for key in
            ['nameEn','nameZh','system','geometrySystem','geometryNameEn','tissueType','tissueTypeZh',
             'sourceConceptNameEn','sourceConceptNoteZh','taxonomyReviewStatus','descriptionZh','functionZh','descriptionSources']}
        registry['entries'][metadata['id']]['taxonomyPreviousSystem']='connective'
    assert {key:registry['entries'][key] for key in previous_entries}==previous_entries
    write_json(taxonomy_path,registry)
    write_manifest(items)
    anatomy['structures']=original_structures+[metadata for metadata,_,_ in additions]
    before={};after={};outputs=[]
    previous_models={model['quality']:model for model in anatomy['models']}
    for quality in ['standard','low']:
        before[quality],after[quality],result=append_glb(ASSETS/f'models/body-{quality}.glb',quality,additions)
        outputs.append({**previous_models[quality],**result})
    assert object_digest(anatomy['structures'][:BASE_COUNT])==original_metadata_digest
    assert all(after['standard']['nodes'][m['id']]==after['low']['nodes'][m['id']] for m,_,_ in additions)
    anatomy['models']=outputs
    write_json(anatomy_path,anatomy)
    occlusion_path=MANIFEST/'model-skin-occlusion.json'
    occlusion=json.loads(occlusion_path.read_text());skin_output=ROOT/occlusion['output']['path']
    skin_hash=digest(skin_output);low=next(row for row in outputs if row['quality']=='low')
    occlusion['input']['beforeFasciaExpansionContainerSha256']=occlusion['input']['sha256']
    occlusion['input']['sha256']=low['sha256']
    occlusion['input']['skinGeometryPayload']=after['low']['nodes']['FJ2810']
    occlusion['input']['skinPayloadUnchangedDuringFasciaAppend']=True
    write_json(occlusion_path,occlusion)
    supplement_path=MANIFEST/'model-supplement.json';supplement=json.loads(supplement_path.read_text())
    imported={row['id'] for row in supplement['addedMeshes']}
    for metadata,_,_ in additions:
        if metadata['id'] not in imported:
            supplement['addedMeshes'].append({'id':metadata['id'],'sourceModelName':metadata['sourceConceptNameEn'],
                'system':'connective','nameZh':metadata['nameZh'],'sourceGeometryId':metadata['sourceGeometryId'],
                'bounds':metadata['bounds']})
    supplement['addedMeshCountsBySystem']=dict(Counter(row.get('system','muscle') for row in supplement['addedMeshes']))
    supplement['scopeZh']='明确白名单：缺失骨骼肌74个网格及真实结缔组织60个网格（筋膜、腱膜、支持带）；未导入NC器官。'
    write_json(supplement_path,supplement)
    taxonomy_report_path=MANIFEST/'model-taxonomy.json';taxonomy_report=json.loads(taxonomy_report_path.read_text())
    taxonomy_report.update(totalStructures=len(anatomy['structures']),
        countsBySystem=dict(Counter(s['system'] for s in anatomy['structures'])),
        countsByTissueType=dict(Counter(s['tissueType'] for s in anatomy['structures'])),
        anatomyMetadata={'path':str(anatomy_path.relative_to(ROOT)),'sha256':digest(anatomy_path)},
        modelsAfter=after,fasciaExpansionManifest='manifests/model-fascia-expansion.json')
    taxonomy_report['connectiveMeshes']=[{'id':s['id'],'nameZh':s['nameZh'],'nameEn':s['nameEn'],
        'tissueType':s['tissueType'],'bounds':s['bounds'],'nodeName':s['nodeName']}
        for s in anatomy['structures'] if s['system']=='connective']
    write_json(taxonomy_report_path,taxonomy_report)
    build_path=MANIFEST/'model-build.json';build=json.loads(build_path.read_text())
    pipeline={row['path'] for row in build['pipelineFiles']}
    pipeline.update('tools/assets/'+name for name in
        ['connective_supplement.py','append_fascia_assets.py','taxonomy.json','verify_models.py'])
    build.update(outputs=outputs,countsBySystem=taxonomy_report['countsBySystem'],
        anatomyMetadata=taxonomy_report['anatomyMetadata'],
        connectiveSupplementManifest='manifests/model-connective-supplement.json',
        fasciaExpansionManifest='manifests/model-fascia-expansion.json',
        pipelineFiles=[{'path':p,'sha256':digest(ROOT/p)} for p in sorted(pipeline)],
        metadataAndAppendBuild={'entryPoint':'tools/assets/append_fascia_assets.py',
            'existingGeometryPolicy':'Every existing node/primitive/material/accessor value and complete BIN prefix retained byte-exact.',
            'newGeometryPolicy':'Source-resolution thin sheets and retinacula retained with identical topology in both tiers.'})
    write_json(build_path,build)
    report={'schemaVersion':1,'status':'passed','purposeZh':'以真实筋膜解剖为主体追加源有膜层，不生成所谓标准力线。',
        'source':{'path':'tools/assets/source/MuscularSystem100.fbx',
            'sha256':digest(ROOT/'tools/assets/source/MuscularSystem100.fbx'),'license':'CC-BY-SA-4.0'},
        'baselineStructures':BASE_COUNT,'addedStructures':len(additions),'totalStructures':len(anatomy['structures']),
        'addedByTissueType':dict(Counter(m['tissueType'] for m,_,_ in additions)),
        'beforeAnatomySha256':original_anatomy_digest,'originalStructureMetadataSha256':original_metadata_digest,
        'originalStructureMetadataValuesUnchanged':True,'existingGeometryPayloadsByteExact':True,
        'existingNodePrimitiveMaterialAccessorJsonUnchanged':True,'newTierGeometryByteExact':True,
        'skinOcclusionUnchanged':digest(skin_output)==skin_hash,'skinOcclusionSha256':skin_hash,
        'anatomyMetadata':build['anatomyMetadata'],'modelsBefore':before,'modelsAfter':after,
        'outputs':outputs,'excludedSourceModels':EXCLUDED_SOURCE_IDS,
        'registrationTranslationMetres':supplement['translationMetres'],
        'addedMeshes':[{**m,**source_stats(vertices,faces),'targetPayloads':after['standard']['nodes'][m['id']]}
            for m,vertices,faces in additions],
        'checks':['All 1239 previous structure metadata objects unchanged',
            'Old node/mesh/accessor/material JSON arrays retained without reclassification',
            'Old complete BIN prefix and every POSITION/NORMAL/index payload unchanged',
            '52 appended source IDs unique, with identical standard/low geometry',
            'All current model containers below 100 MiB', 'Source sheets retain natural boundaries'],
        'limitsZh':['共同肌肉参考配准只有平移，不是逐个筋膜的医学配准；仍待专业视觉校对。',
            '部分源薄片存在非流形边，逐项计数原样记录；未自动补洞或重建表面。',
            '支持带为独立组织类型；不能因为属于结缔组织显示分组就一概称为筋膜。',
            '源网格不等于经过共识确认的远程传力路径，不表达传力方向或大小。',
            '本检查核验源文件和导出几何，未运行Android或验证帧率。']}
    write_json(REPORT,report)
    print(json.dumps({key:report[key] for key in ['status','baselineStructures','addedStructures','totalStructures','addedByTissueType','outputs']},ensure_ascii=False,indent=2))


if __name__=='__main__':main()
