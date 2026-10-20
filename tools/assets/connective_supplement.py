"""Explicitly enumerated genuine Z-Anatomy connective-tissue meshes.

The first eight entries keep their historical metadata and order.  Further
entries add source sheets or retinacula, never inferred force-line geometry.
"""
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

# (source model ID, Chinese name, tissue type, region, side, source-named location).
# Wrist flexor retinacula and iliotibial tracts are deliberately omitted here:
# the existing FJ nodes already represent those structures.  Tensor fasciae
# latae is a muscle, not a fascia sheet, and is also excluded.
ADDITIONAL_DEFINITIONS = [
    (84510682,'帽状腱膜','aponeurosis','head_neck','right','颅顶'),
    (849343592,'帽状腱膜','aponeurosis','head_neck','left','颅顶'),
    (661418671,'颞筋膜浅层','fascia','head_neck','right','颞区'),
    (772631811,'颞筋膜浅层','fascia','head_neck','left','颞区'),
    (633010665,'咬肌筋膜','fascia','head_neck','right','咬肌表面'),
    (629531657,'咬肌筋膜','fascia','head_neck','left','咬肌表面'),
    (853384501,'颈筋膜封套层','fascia','head_neck','right','颈部'),
    (446380389,'颈筋膜封套层','fascia','head_neck','left','颈部'),
    (518493617,'胸筋膜','fascia','trunk','right','胸前壁'),
    (480595436,'胸筋膜','fascia','trunk','left','胸前壁'),
    (182375068,'腹部包被筋膜（源模型命名）','fascia','trunk','right','腹壁'),
    (210225933,'腹部包被筋膜（源模型命名）','fascia','trunk','left','腹壁'),
    (451051828,'锁胸筋膜','fascia','trunk','right','胸前壁深部'),
    (866142449,'锁胸筋膜','fascia','trunk','left','胸前壁深部'),
    (688771959,'腹横筋膜','fascia','trunk',None,'腹壁内面'),
    (200191819,'膈筋膜（源模型命名）','fascia','trunk',None,'膈区'),
    (632156718,'梨状肌筋膜','fascia','trunk','right','盆部梨状肌区域'),
    (392888165,'梨状肌筋膜','fascia','trunk','left','盆部梨状肌区域'),
    (491848518,'髂腰筋膜','fascia','trunk','right','髂腰肌区域'),
    (408214219,'髂腰筋膜','fascia','trunk','left','髂腰肌区域'),
    (211792739,'三角肌筋膜','fascia','upper_limb','right','肩部三角肌表面'),
    (869110256,'三角肌筋膜','fascia','upper_limb','left','肩部三角肌表面'),
    (311271772,'臂筋膜','fascia','upper_limb','right','上臂'),
    (911661179,'臂筋膜','fascia','upper_limb','left','上臂'),
    (333450372,'前臂筋膜','fascia','upper_limb','right','前臂'),
    (244669373,'前臂筋膜','fascia','upper_limb','left','前臂'),
    (647872028,'手背筋膜','fascia','upper_limb','right','手背'),
    (916496480,'手背筋膜','fascia','upper_limb','left','手背'),
    (547570908,'掌腱膜','aponeurosis','upper_limb','right','手掌'),
    (675376731,'掌腱膜','aponeurosis','upper_limb','left','手掌'),
    (131836467,'阔筋膜','fascia','lower_limb','right','大腿'),
    (664437283,'阔筋膜','fascia','lower_limb','left','大腿'),
    (743661520,'腘筋膜','fascia','lower_limb','right','膝后腘区'),
    (392175318,'腘筋膜','fascia','lower_limb','left','膝后腘区'),
    (769068412,'小腿筋膜','fascia','lower_limb','right','小腿'),
    (27084790,'小腿筋膜','fascia','lower_limb','left','小腿'),
    (330125342,'腕伸肌支持带','retinaculum','upper_limb','right','腕背侧'),
    (971929399,'腕伸肌支持带','retinaculum','upper_limb','left','腕背侧'),
    (497593817,'髌外侧支持带','retinaculum','lower_limb','right','髌骨外侧'),
    (391652619,'髌外侧支持带','retinaculum','lower_limb','left','髌骨外侧'),
    (344109556,'髌内侧支持带','retinaculum','lower_limb','right','髌骨内侧'),
    (925501647,'髌内侧支持带','retinaculum','lower_limb','left','髌骨内侧'),
    (316486140,'伸肌上支持带（踝）','retinaculum','lower_limb','right','踝前上部'),
    (134091191,'伸肌上支持带（踝）','retinaculum','lower_limb','left','踝前上部'),
    (372542288,'伸肌下支持带（踝）','retinaculum','lower_limb','right','踝前下部'),
    (360316970,'伸肌下支持带（踝）','retinaculum','lower_limb','left','踝前下部'),
    (653592180,'腓骨肌上支持带','retinaculum','lower_limb','right','外踝后上方'),
    (106520629,'腓骨肌上支持带','retinaculum','lower_limb','left','外踝后上方'),
    (419234530,'腓骨肌下支持带','retinaculum','lower_limb','right','外踝下方'),
    (365024117,'腓骨肌下支持带','retinaculum','lower_limb','left','外踝下方'),
    (818253022,'屈肌支持带（踝）','retinaculum','lower_limb','right','内踝后方'),
    (888530236,'屈肌支持带（踝）','retinaculum','lower_limb','left','内踝后方'),
]
ADDITIONAL_BY_ID = {row[0]: row[1:] for row in ADDITIONAL_DEFINITIONS}
ALL_IDS = list(DEFINITIONS) + list(ADDITIONAL_BY_ID)
EXCLUDED_SOURCE_IDS = {
    625532537:'Tensor fasciae latae.r is a skeletal muscle, not fascia.',
    188008333:'Tensor fasciae latae.l is a skeletal muscle, not fascia.',
    739238967:'Iliotibial tract.r already represented by FJ1423.',
    118911115:'Iliotibial tract.l already represented by FJ1423M.',
    516571620:'Flexor retinaculum of wrist.r already represented by FJ1471.',
    759905954:'Flexor retinaculum of wrist.l already represented by FJ1471M.',
}


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def f32(value): return struct.unpack('<f',struct.pack('<f',value))[0]
def bounds(vs): return {'min':[min(v[i] for v in vs) for i in range(3)],'max':[max(v[i] for v in vs) for i in range(3)]}


def meshes(write_obj=False):
    registration = json.loads((ROOT/'manifests/model-supplement.json').read_text())
    translation = registration['translationMetres']
    result=[]
    for mesh in static_meshes(Fbx(SOURCE/'MuscularSystem100.fbx')):
        if mesh['id'] not in ALL_IDS: continue
        is_original=mesh['id'] in DEFINITIONS
        if is_original:
            zh,kind,kind_zh,region,side=DEFINITIONS[mesh['id']]
        else:
            zh,kind,region,side,location=ADDITIONAL_BY_ID[mesh['id']]
            kind_zh={'fascia':'筋膜','aponeurosis':'腱膜','retinaculum':'支持带'}[kind]
        ident=f'ZA_{mesh["id"]}'
        # Preserve source face/vertex ordering. Round positions to the exact float32
        # values written to GLB so triangle/barycentric references match exported OBJ.
        vs=[tuple(f32(v[i]+translation[i]) for i in range(3)) for v in mesh['vertices']]
        fs=mesh['faces']
        prefix={'left':'left ','right':'right ',None:''}[side]
        source_name=mesh['name'][:-2] if side else mesh['name']
        name=prefix+source_name.lower()
        meta={'id':ident,'nodeName':ident,'nameEn':name,'nameZh':{'left':'左侧','right':'右侧',None:''}[side]+zh,
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
        if not is_original:
            meta.update(
                geometrySystem='connective',geometryNameEn=name,
                descriptionZh=(f'{meta["nameZh"]}，位于{location}。这是源模型按该名称提供的实际{kind_zh}网格；'
                    '中文名称与局部边界仍待专业审校。形态供解剖观察，不表示标准力线、疼痛点或治疗靶点。'),
                functionZh=('支持带为局部增厚的纤维性结构，约束其邻近肌腱的走行；具体功能需结合局部解剖学习。'
                    if kind=='retinaculum' else '结缔组织膜层参与局部组织的包裹、连接或支持；单个网格不能确定远程传力路线或传力大小。'),
                sourceConceptNoteZh='逐个核对 Z-Anatomy 官方 FBX 的命名及模型 ID；仅导入源有网格，不把组织范围解释为传力路径。',
                descriptionSources=[{'title':'Z-Anatomy MuscularSystem100.fbx source names and geometry',
                    'url':'https://github.com/LluisV/Z-Anatomy/blob/PC-Version/Resources/Models/FBX/MuscularSystem100.fbx'}])
        if write_obj:
            folder=SOURCE/'connective-target';folder.mkdir(exist_ok=True)
            lines=['# Exact GLB float32 target coordinates; metres Y-up anterior +Z; face order preserved.']
            lines.extend('v '+' '.join(repr(x) for x in v) for v in vs)
            lines.extend('f '+' '.join(str(i+1) for i in face) for face in fs)
            (folder/f'{ident}.obj').write_text('\n'.join(lines)+'\n')
        result.append((meta,vs,fs))
    result.sort(key=lambda item:ALL_IDS.index(int(item[0]['sourceElementId'])))
    assert len(result)==len(ALL_IDS)==60
    return result


def write_manifest(items):
    source=SOURCE/'MuscularSystem100.fbx'
    report={'schemaVersion':2,'sourcePath':str(source.relative_to(ROOT)),
        'sourceUrl':'https://raw.githubusercontent.com/LluisV/Z-Anatomy/PC-Version/Resources/Models/FBX/MuscularSystem100.fbx',
        'sourceSha256':digest(source),'license':'CC-BY-SA-4.0','addedMeshes':[
            {**m,'vertices':len(vs),'triangles':len(fs),'targetObj':f'tools/assets/source/connective-target/{m["id"]}.obj'}
            for m,vs,fs in items],
        'excludedSourceModels':[{'sourceElementId':str(ident),'reason':reason} for ident,reason in EXCLUDED_SOURCE_IDS.items()],
        'geometryPolicy':'Source float32 positions and source triangle order retained unchanged at both quality tiers.',
        'limitsZh':['沿用已记录的共同肌肉平移配准；不是逐点医学配准。',
            '包括筋膜、腱膜和支持带；connective 显示分组不表示所有条目均为狭义筋膜。',
            '局部引用必须使用真实三角面和重心坐标；bounds 仅用于视图参考。',
            '当前模型和已下载源文件未包含可确认的骶结节韧带网格，不提供其虚构 ID。']}
    path=ROOT/'manifests/model-connective-supplement.json'
    path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':
    items=meshes(write_obj=True);write_manifest(items)
    print(json.dumps([{'id':m['id'],'nameZh':m['nameZh'],'triangles':len(fs),'bounds':m['bounds']} for m,vs,fs in items],ensure_ascii=False))
