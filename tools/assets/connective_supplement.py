"""Eight explicitly authorized, genuine Z-Anatomy fascia/aponeurosis meshes."""
import hashlib
import json
import struct
from pathlib import Path
from fbx_extract import Fbx, static_meshes

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT/'tools/assets/source'
DEFINITIONS = {
    390108657: ('胸腰筋膜后层','fascia','筋膜','trunk','right'),
    655962380: ('胸腰筋膜后层','fascia','筋膜','trunk','left'),
    258389767: ('胸腰筋膜中层','fascia','筋膜','trunk','right'),
    286507229: ('胸腰筋膜中层','fascia','筋膜','trunk','left'),
    351561331: ('胸腰筋膜前层','fascia','筋膜','trunk','right'),
    787452513: ('胸腰筋膜前层','fascia','筋膜','trunk','left'),
    753149753: ('足底腱膜','aponeurosis','腱膜','lower_limb','right'),
    375911140: ('足底腱膜','aponeurosis','腱膜','lower_limb','left'),
}


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def f32(value): return struct.unpack('<f',struct.pack('<f',value))[0]
def bounds(vs): return {'min':[min(v[i] for v in vs) for i in range(3)],'max':[max(v[i] for v in vs) for i in range(3)]}


def meshes(write_obj=False):
    registration = json.loads((ROOT/'manifests/model-supplement.json').read_text())
    translation = registration['translationMetres']
    result=[]
    for mesh in static_meshes(Fbx(SOURCE/'MuscularSystem100.fbx')):
        if mesh['id'] not in DEFINITIONS: continue
        zh,kind,kind_zh,region,side=DEFINITIONS[mesh['id']]
        ident=f'ZA_{mesh["id"]}'
        # Preserve source face/vertex ordering. Round positions to the exact float32
        # values written to GLB so triangle/barycentric references match exported OBJ.
        vs=[tuple(f32(v[i]+translation[i]) for i in range(3)) for v in mesh['vertices']]
        fs=mesh['faces']
        name=('left ' if side=='left' else 'right ')+mesh['name'][:-2].lower()
        meta={'id':ident,'nodeName':ident,'nameEn':name,'nameZh':('左侧' if side=='left' else '右侧')+zh,
            'system':'connective','region':region,'layer':0,'bounds':bounds(vs),
            'tissueType':kind,'tissueTypeZh':kind_zh,'structureType':kind,
            'sourceDataset':'Z-Anatomy MuscularSystem100.fbx','sourceElementId':str(mesh['id']),
            'sourceGeometryId':str(mesh['geometryId']),'sourceLicense':'CC-BY-SA-4.0',
            'sourceConceptNameEn':mesh['name'],'sourceConceptNoteZh':'对应官方 FBX 命名的实际筋膜或腱膜网格；没有另造几何。',
            'sourceWorldMatrixCentimetres':mesh['fbxMatrixCentimetres'],
            'registrationTranslationMetres':translation,'registrationReviewStatus':'geometric-reference-unreviewed',
            'translationStatus':'reviewable','taxonomyReviewStatus':'source-checked-professional-review-pending',
            'descriptionZh':(('胸腰筋膜为腰背部的结缔组织膜层；本件仅表示源模型命名的这一层及侧别。'
                if kind=='fascia' else '足底腱膜为足底深筋膜的增厚部分；本件表示源模型对应侧别的腱膜网格。')
                +'其形态和配准供解剖关系学习，不能作为疼痛点、触发点或治疗靶点。'),
            'functionZh':('参与腰背部组织包裹、肌力传递和稳定。' if kind=='fascia' else '支持足纵弓并在负重和蹬地过程中传递张力。')}
        if write_obj:
            folder=SOURCE/'connective-target';folder.mkdir(exist_ok=True)
            lines=['# Exact GLB float32 target coordinates; metres Y-up anterior +Z; face order preserved.']
            lines.extend('v '+' '.join(repr(x) for x in v) for v in vs)
            lines.extend('f '+' '.join(str(i+1) for i in face) for face in fs)
            (folder/f'{ident}.obj').write_text('\n'.join(lines)+'\n')
        result.append((meta,vs,fs))
    result.sort(key=lambda item:list(DEFINITIONS).index(int(item[0]['sourceElementId'])))
    assert len(result)==8
    return result


def write_manifest(items):
    source=SOURCE/'MuscularSystem100.fbx'
    report={'schemaVersion':1,'sourcePath':str(source.relative_to(ROOT)),
        'sourceUrl':'https://raw.githubusercontent.com/LluisV/Z-Anatomy/PC-Version/Resources/Models/FBX/MuscularSystem100.fbx',
        'sourceSha256':digest(source),'license':'CC-BY-SA-4.0','addedMeshes':[
            {**m,'vertices':len(vs),'triangles':len(fs),'targetObj':f'tools/assets/source/connective-target/{m["id"]}.obj'}
            for m,vs,fs in items],
        'limitsZh':['沿用已记录的共同肌肉平移配准；不是逐点医学配准。',
            '局部引用必须使用真实三角面和重心坐标；bounds 仅用于视图参考。',
            '当前模型和已下载源文件未包含可确认的骶结节韧带网格，不提供其虚构 ID。']}
    path=ROOT/'manifests/model-connective-supplement.json'
    path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':
    items=meshes(write_obj=True);write_manifest(items)
    print(json.dumps([{'id':m['id'],'nameZh':m['nameZh'],'triangles':len(fs),'bounds':m['bounds']} for m,vs,fs in items],ensure_ascii=False))
