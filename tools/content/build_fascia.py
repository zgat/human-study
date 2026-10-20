#!/usr/bin/env python3
"""Build research-backed, non-directional fascial relations on actual GLB triangles.
Python standard library only. Re-run after the final standard/low model rebuild.
The reference regions remain provisional; no clinical target is generated.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets'
SIDES = ('right', 'left')
ZH = {'right': '右侧', 'left': '左侧'}
IDS = {
    'right': {'ld': 'ZA_172472462', 'tlf': 'ZA_390108657', 'gm': 'FJ1418', 'at': 'FJ1405',
              'heel': 'FJ3360', 'pa': 'ZA_753149753', 'bf': 'FJ1395', 'hip': 'FJ3152'},
    'left': {'ld': 'ZA_817053160', 'tlf': 'ZA_655962380', 'gm': 'FJ1418M', 'at': 'FJ1405M',
             'heel': 'FJ3256', 'pa': 'ZA_375911140', 'bf': 'FJ1395M', 'hip': 'FJ3288'}
}


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def add(a, b): return tuple(x+y for x,y in zip(a,b))
def sub(a, b): return tuple(x-y for x,y in zip(a,b))
def mul(a, x): return tuple(v*x for v in a)
def dot(a, b): return sum(x*y for x,y in zip(a,b))
def norm(a): return math.sqrt(dot(a,a))
def unit(a): return mul(a, 1/max(norm(a), 1e-20))
def cross(a,b): return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def rounded(a): return [round(v,10) for v in a]


class Model:
    def __init__(self, path):
        self.path=path; self.sha256=digest(path); self.raw=path.read_bytes()
        assert self.raw[:4]==b'glTF'
        length=struct.unpack_from('<I',self.raw,12)[0]
        self.doc=json.loads(self.raw[20:20+length]); self.blob=memoryview(self.raw)[28+length:]
        self.nodes={n['name']:n for n in self.doc['nodes'] if 'mesh' in n}; self.cache={}
    def accessor(self,index):
        a=self.doc['accessors'][index]; v=self.doc['bufferViews'][a['bufferView']]
        width={'VEC3':3,'SCALAR':1}[a['type']]; fmt={5123:'H',5125:'I',5126:'f'}[a['componentType']]
        stride=v.get('byteStride',struct.calcsize(fmt)*width)
        offset=v.get('byteOffset',0)+a.get('byteOffset',0)
        rows=[struct.unpack_from('<'+fmt*width,self.blob,offset+i*stride) for i in range(a['count'])]
        return rows if width>1 else [r[0] for r in rows]
    def mesh(self,key):
        if key in self.cache:return self.cache[key]
        node=self.nodes[key]
        assert not any(k in node for k in ('matrix','translation','rotation','scale')), 'Expected baked world coordinates'
        primitive=self.doc['meshes'][node['mesh']]['primitives'][0]
        assert primitive.get('mode',4)==4
        vertices=self.accessor(primitive['attributes']['POSITION']); indices=self.accessor(primitive['indices'])
        normals=self.accessor(primitive['attributes']['NORMAL']) if 'NORMAL' in primitive['attributes'] else None
        faces=[tuple(indices[i:i+3]) for i in range(0,len(indices),3)]
        lo=tuple(min(v[i] for v in vertices) for i in range(3)); hi=tuple(max(v[i] for v in vertices) for i in range(3))
        centers=[mul(add(add(vertices[f[0]],vertices[f[1]]),vertices[f[2]]),1/3) for f in faces]
        geometry_hash=hashlib.sha256(json.dumps([vertices,faces],separators=(',',':')).encode()).hexdigest()
        result={'vertices':vertices,'faces':faces,'normals':normals,'lo':lo,'hi':hi,'centers':centers,'sha256':geometry_hash}
        self.cache[key]=result;return result


def seed(mesh, fx, fy, fz):
    return tuple(a+(b-a)*f for a,b,f in zip(mesh['lo'],mesh['hi'],(fx,fy,fz)))


def pick(model,key,wanted,predicate=None):
    mesh=model.mesh(key)
    candidates=[i for i,p in enumerate(mesh['centers']) if predicate is None or predicate(p,mesh)]
    if not candidates:raise ValueError('Anatomical candidate region empty: '+key)
    index=min(candidates,key=lambda i:dot(sub(mesh['centers'][i],wanted),sub(mesh['centers'][i],wanted)))
    face=mesh['faces'][index]; a,b,c=[mesh['vertices'][i] for i in face]
    face_normal=cross(sub(b,a),sub(c,a))
    assert norm(face_normal)>1e-12,(key,index,'degenerate target triangle')
    normal=unit(tuple(sum(mesh['normals'][i][j] for i in face) for j in range(3))) if mesh['normals'] else unit(face_normal)
    return {'position':mesh['centers'][index],'normal':normal,'sourceGeometry':{
        'sourceModel':'models/body-standard.glb','modelSha256':model.sha256,'meshGeometrySha256':mesh['sha256'],
        'nodeName':key,'primitiveIndex':0,'triangleIndex':index,'vertexIndices':list(face),
        'indexBase':0,'barycentric':[1/3,1/3,1/3]}}


def target(model,key,tid,name,description,side,wanted,predicate,rule,view='body',radius=.012):
    selected=pick(model,key,wanted,predicate)
    selected['sourceGeometry']['selectionRuleZh']=rule
    return {'id':tid,'nameZh':name+'连接参考区','position':rounded(selected['position']),
        'normal':rounded(selected['normal']),'structureIds':[key],'descriptionZh':description+
        '位置取自该模型真实三角面内的代表点，仅表示邻近参考区，尚未完成专业解剖定位审校。',
        'side':side,'viewId':view,'reviewStatus':'provisional','regionRadiusM':radius,
        'sourceGeometry':selected['sourceGeometry']}


def source(sid,title,url,year,kind,note):
    return {'id':sid,'title':title,'url':url,'year':year,'evidenceType':kind,'noteZh':note}


SOURCES={
 'vleeming1995':source('vleeming1995','The posterior layer of the thoracolumbar fascia. Its function in load transfer from spine to legs',
    'https://pubmed.ncbi.nlm.nih.gov/7701385/',1995,'cadaver_anatomy_and_loading',
    '10具人体标本的尸解与牵拉观察，报告后层位移及部分跨侧张力传递；不是活体个体的完整力场。'),
 'carvalhais2013':source('carvalhais2013','Myofascial force transmission between the latissimus dorsi and gluteus maximus muscles: an in vivo experiment',
    'https://pubmed.ncbi.nlm.nih.gov/23394717/',2013,'human_in_vivo_indirect_mechanics',
    '37名受试者；改变背阔肌张力后，对侧髋被动角度或刚度发生变化。髋变量是间接指标，未逐点测量筋膜内力矢量。'),
 'stecco2013':source('stecco2013','Plantar fascia anatomy and its relationship with Achilles tendon and paratenon',
    'https://pubmed.ncbi.nlm.nih.gov/24028383/',2013,'cadaver_histology_and_imaging',
    '尸解、组织学及影像研究支持跟骨周围腱周膜、骨膜与足底腱膜的关系；不能概括为所有成人跟腱主体直接连续成足底腱膜。'),
 'erdemir2004':source('erdemir2004','Dynamic loading of the plantar aponeurosis in walking',
    'https://pubmed.ncbi.nlm.nih.gov/14996881/',2004,'cadaver_dynamic_gait_simulation',
    '7只尸体足的步态模拟测到足底腱膜张力与跟腱力相关。动态负荷结果不能作为本静态模型的真实力值或方向。'),
 'wingerden1993':source('wingerden1993','A functional-anatomical approach to the spine-pelvis mechanism: interaction between the biceps femoris muscle and the sacrotuberous ligament',
    'https://pubmed.ncbi.nlm.nih.gov/20058466/',1993,'cadaver_anatomy_and_loading',
    '对股二头肌腱施力会影响骶结节韧带张力；研究不提供适用于所有人的固定临床靶点。'),
 'miguelperez2024':source('miguelperez2024','Anatomical Relationships of the Proximal Attachment of the Hamstring Muscles with Neighboring Structures',
    'https://doi.org/10.3390/diagnostics14161725',2024,'cadaver_ultrasound_and_histology',
    '66具冷冻标本的97侧半骨盆，经超声、尸解与组织学研究辨认近端腘绳肌邻接组织。骶结节韧带浅层与股二头肌长头的连续关系不等于个体治疗点。')
}


def path(pid,side,targets):
    return {'id':pid,'side':side,'points':[t['position'] for t in targets],'targetIds':[t['id'] for t in targets],
        'kind':'unoriented_connection_schematic','reviewStatus':'provisional',
        'notAForceVector':True,'direction':None,'forceMagnitude':None,
        'descriptionZh':'连接参考区之间的无方向关系示意；折线不表示完整组织走向、纤维方向或实际受力路径。'}


def build_connections(model):
    connections=[]
    common='无箭头连线用于说明研究报道的结构关系，不表示实际力矢量、力值、疼痛来源或治疗靶点。'
    targets=[];paths=[]
    for side in SIDES:
        other='left' if side=='right' else 'right'; ids=IDS[side]; opposite=IDS[other]
        ld=model.mesh(ids['ld']); gm=model.mesh(opposite['gm'])
        ld_seed=seed(ld,.84 if side=='right' else .16,.20,.45)
        a=target(model,ids['ld'],f'ld-tlf-{side}-ld',ZH[side]+'背阔肌下内侧',
            '背阔肌下内侧腱膜邻区；本模型未把背阔肌腱膜另分成独立网格。',side,ld_seed,
            lambda p,m:p[1]<m['lo'][1]+.38*(m['hi'][1]-m['lo'][1]) and abs(p[0])<.075,
            '在背阔肌下38%且距正中线75毫米以内的真实面中，选择近下内侧参考位置。')
        b=target(model,ids['tlf'],f'ld-tlf-{side}-tlf-ipsi',ZH[side]+'胸腰筋膜后层',
            '源模型标注的胸腰筋膜后层，邻近同侧背阔肌下内侧参考区。',side,a['position'],None,
            '在同侧胸腰筋膜后层真实面中选择最接近背阔肌参考位置的面。')
        gm_seed=seed(gm,.82 if other=='right' else .18,.90,.35)
        d=target(model,opposite['gm'],f'ld-tlf-{side}-gm',ZH[other]+'臀大肌上内侧',
            '对侧臀大肌上内侧的邻接参考区，非整块肌肉的几何中心。',other,gm_seed,
            lambda p,m:p[1]>m['hi'][1]-.09 and abs(p[0])<.065,
            '在对侧臀大肌上90毫米且距正中线65毫米以内的真实面中选择上内侧面。')
        c=target(model,opposite['tlf'],f'ld-tlf-{side}-tlf-contra',ZH[other]+'胸腰筋膜后层下部',
            '源模型胸腰筋膜后层下部，邻近对侧臀大肌上内侧参考区。',other,d['position'],None,
            '在对侧胸腰筋膜后层真实面中选择最接近臀大肌上内侧参考位置的面。')
        own=[a,b,c,d];targets+=own;paths.append(path(f'ld-tlf-{side}',side,own))
    connections.append({'id':'latissimus-thoracolumbar-contralateral-gluteus','nameZh':'背阔肌—胸腰筋膜—对侧臀大肌',
        'descriptionZh':'通过胸腰筋膜后层联系的跨侧结构关系。'+common,
        'evidenceZh':'尸解牵拉研究报告胸腰筋膜后层的位移和跨侧传递；活体研究报告背阔肌张力改变对侧髋被动变量。证据支持局部力学耦合的可能，不能确定此模型每点的受力方向。',
        'color':0xff79d1bd,'structureIds':list(dict.fromkeys(k for t in targets for k in t['structureIds'])),
        'paths':paths,'targets':targets,'sourceRefs':[SOURCES['vleeming1995'],SOURCES['carvalhais2013']],
        'geometryCoverageZh':'背阔肌、双侧胸腰筋膜后层和对侧臀大肌均有真实网格；连接处的细纤维与个体附着范围未被逐层建模。',
        'unmappedStructures':['背阔肌腱膜的独立分区','连接处显微纤维'],'reviewStatus':'provisional'})
    targets=[];paths=[]
    for side in SIDES:
        ids=IDS[side]; heel=model.mesh(ids['heel']); at=model.mesh(ids['at']); pa=model.mesh(ids['pa'])
        heel_seed=seed(heel,.5,.48,.04)
        a=target(model,ids['at'],f'heel-{side}-at',ZH[side]+'跟腱远端',
            '跟腱远端在跟骨后方的附着邻区。',side,heel_seed,
            lambda p,m:p[1]<m['lo'][1]+.25*(m['hi'][1]-m['lo'][1]),
            '仅在跟腱远端25%的真实面中选择最接近跟骨后部的面。')
        b=target(model,ids['heel'],f'heel-{side}-posterior',ZH[side]+'跟骨后部骨膜邻区',
            '本点锚于跟骨后部骨表面，作为骨膜邻区参照；没有独立的跟骨骨膜网格。',side,a['position'],
            lambda p,m:p[2]<m['lo'][2]+.45*(m['hi'][2]-m['lo'][2]),
            '在跟骨后45%的真实骨面中选择最接近跟腱远端参考点的面。')
        d=target(model,ids['pa'],f'heel-{side}-pa',ZH[side]+'足底腱膜近端',
            '足底腱膜靠近跟骨的近端参考区；不代表全部纤维的精确起点。',side,seed(pa,.5,.50,.02),
            lambda p,m:p[2]<m['lo'][2]+.16*(m['hi'][2]-m['lo'][2]),
            '在真实足底腱膜靠后16%的面中选择近跟骨参考位置。',view='sole')
        c=target(model,ids['heel'],f'heel-{side}-plantar',ZH[side]+'跟骨足底侧骨膜邻区',
            '本点锚于跟骨足底侧真实骨表面，作为足底腱膜近端与骨膜邻区参照；没有独立骨膜网格。',side,d['position'],
            lambda p,m:p[1]<m['lo'][1]+.62*(m['hi'][1]-m['lo'][1]) and p[2]<m['lo'][2]+.60*(m['hi'][2]-m['lo'][2]),
            '在跟骨下62%及后60%的真实骨面中选择最接近足底腱膜近端的面。',view='sole')
        own=[a,b,c,d];targets+=own;paths.append(path(f'heel-{side}',side,own))
    connections.append({'id':'achilles-calcaneal-region-plantar-aponeurosis','nameZh':'跟腱—跟骨邻区—足底腱膜',
        'descriptionZh':'跟腱腱周膜、跟骨周围组织与足底腱膜的局部关系。'+common,
        'evidenceZh':'尸解和组织学研究报道经跟骨骨膜的腱周膜连接；尸体足步态模拟显示跟腱负荷与足底腱膜张力相关。不能据此把成人跟腱主体画成直接连续的一根腱膜。',
        'color':0xffd7bd7b,'structureIds':list(dict.fromkeys(k for t in targets for k in t['structureIds'])),
        'paths':paths,'targets':targets,'sourceRefs':[SOURCES['stecco2013'],SOURCES['erdemir2004']],
        'geometryCoverageZh':'跟腱、跟骨与足底腱膜具有真实网格。骨膜邻区只锚在骨表面作参照；骨膜及跟腱腱周膜没有独立网格。',
        'unmappedStructures':['跟骨骨膜','跟腱腱周膜'],'reviewStatus':'provisional'})
    targets=[];paths=[]
    for side in SIDES:
        ids=IDS[side];bf=model.mesh(ids['bf'])
        a=target(model,ids['bf'],f'hamstring-{side}-bf',ZH[side]+'股二头肌长头近端',
            '股二头肌长头近端腱的模型邻区；短头不纳入此连接。',side,seed(bf,.5,.99,.5),
            lambda p,m:p[1]>m['hi'][1]-.028,
            '仅在长头近端28毫米内的真实三角面中选取代表位置。')
        b=target(model,ids['hip'],f'hamstring-{side}-ischium',ZH[side]+'坐骨结节邻区',
            '真实髋骨的下部、后侧坐骨结节邻区参照。骶结节韧带当前未提供网格，本点不是韧带实体或韧带附着边界。',side,a['position'],
            lambda p,m:p[1]<m['lo'][1]+.055 and p[2]<m['lo'][2]+.60*(m['hi'][2]-m['lo'][2]),
            '限制在真实髋骨最下55毫米及后60%的面中，选择最接近长头近端的骨面；禁止使用全髋骨中心。',radius=.015)
        own=[a,b];targets+=own;paths.append(path(f'hamstring-{side}',side,own))
    connections.append({'id':'biceps-long-head-ischial-sacrotuberous-region','nameZh':'股二头肌长头—坐骨结节邻区',
        'descriptionZh':'用于理解股二头肌长头与骶结节韧带在近端的研究关系。当前仅展示长头和坐骨结节参考区，不描画缺失韧带。'+common,
        'evidenceZh':'原始尸解施力实验报道股二头肌腱牵拉影响骶结节韧带张力；后续超声、尸解与组织学描述长头近端及韧带分层关系。连接存在变异，研究不能给出统一治疗点。',
        'color':0xffb8a5de,'structureIds':list(dict.fromkeys(k for t in targets for k in t['structureIds'])),
        'paths':paths,'targets':targets,'sourceRefs':[SOURCES['wingerden1993'],SOURCES['miguelperez2024']],
        'geometryCoverageZh':'股二头肌长头与髋骨为真实网格。坐骨结节邻区在局部骨面上选点；骶结节韧带缺失，未构造其假网格或跨骨盆假连线。',
        'unmappedStructures':['骶结节韧带','长头近端与韧带的个体纤维连接'],'reviewStatus':'provisional'})
    return connections


def point_triangle_distance(point,a,b,c):
    ab=sub(b,a);ac=sub(c,a);ap=sub(point,a);aa=dot(ab,ab);bb=dot(ab,ac);cc=dot(ac,ac)
    den=aa*cc-bb*bb
    if den>1e-24:
        u=(dot(ap,ab)*cc-dot(ap,ac)*bb)/den;v=(dot(ap,ac)*aa-dot(ap,ab)*bb)/den
        if u>=0 and v>=0 and u+v<=1:return norm(sub(point,add(a,add(mul(ab,u),mul(ac,v)))))
    def segment(x,y):
        xy=sub(y,x);t=max(0,min(1,dot(sub(point,x),xy)/max(dot(xy,xy),1e-24)))
        return norm(sub(point,add(x,mul(xy,t))))
    return min(segment(a,b),segment(a,c),segment(b,c))


def validate(connections,standard,low,anatomy):
    rows=[];ids=set();all_structure_ids={s['id'] for s in anatomy['structures']}
    for connection in connections:
        assert len(connection['paths'])==2
        assert set(connection['structureIds'])<=all_structure_ids
        for t in connection['targets']:
            assert t['id'] not in ids;ids.add(t['id']);g=t['sourceGeometry'];m=standard.mesh(g['nodeName'])
            tri=m['faces'][g['triangleIndex']];assert list(tri)==g['vertexIndices'];assert abs(sum(g['barycentric'])-1)<1e-12
            reconstructed=tuple(sum(m['vertices'][i][j]*weight for i,weight in zip(tri,g['barycentric'])) for j in range(3))
            error=norm(sub(reconstructed,t['position']));assert error<1e-8
            assert abs(norm(t['normal'])-1)<1e-8
            assert all(math.isfinite(x) for x in t['position']+t['normal'])
            assert (t['position'][0]>0) == (t['side']=='left'), ('side mismatch',t['id'])
            lm=low.mesh(g['nodeName']);position=t['position']
            distance=min(point_triangle_distance(position,*(lm['vertices'][i] for i in face)) for face in lm['faces'])
            assert distance<.012,(t['id'],'low-quality surface displacement >12mm',distance)
            # A bone proxy is a surface region, never its whole-bone center.
            center=mul(add(m['lo'],m['hi']),.5)
            if g['nodeName'] in ('FJ3152','FJ3288'):assert norm(sub(position,center))>.065
            rows.append({'targetId':t['id'],'nodeName':g['nodeName'],'triangleIndex':g['triangleIndex'],
                'standardReconstructionErrorM':error,'distanceToLowSurfaceM':distance,'normalUnitError':abs(norm(t['normal'])-1),
                'selectionRuleZh':g['selectionRuleZh']})
        byid={t['id']:t for t in connection['targets']}
        for p in connection['paths']:
            assert p['kind']=='unoriented_connection_schematic' and p['notAForceVector']
            assert len(p['points'])==len(p['targetIds'])>=2
            assert all(byid[tid]['position']==point for tid,point in zip(p['targetIds'],p['points']))
    return rows


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.parse_args()
    standard=Model(ASSETS/'models/body-standard.glb');low=Model(ASSETS/'models/body-low.glb')
    required={key for side in IDS.values() for key in side.values()}
    missing=required-standard.nodes.keys()
    if missing:raise RuntimeError('追加真实筋膜网格尚未进入标准GLB，请等待资产构建完成: '+','.join(sorted(missing)))
    anatomy_path=ASSETS/'anatomy.json';anatomy=json.loads(anatomy_path.read_text())
    connections=build_connections(standard);audit=validate(connections,standard,low,anatomy)
    document={'schemaVersion':1,'modelId':anatomy['modelId'],'sex':'male',
        'displayPolicy':{'lineKind':'unoriented_connection_schematic','forceVectors':False,'forceMagnitudes':False,
            'targetMeaningZh':'连接参考区：真实模型局部表面上的教学参照，不是触发点、治疗点或个体病灶。',
            'professionalAnatomicalReviewedTargets':0},
        'geometryLicenseNoteZh':'参考区坐标来自BodyParts3D与Z-Anatomy的派生模型，沿用随包提供的模型署名与CC BY-SA 4.0许可；研究说明为自行概述，未复制文献图版或长段原文。',
        'terminologyZh':'单个筋膜与更广义的筋膜系统分别标识。腱、腱膜和韧带保留其解剖名称；关系示意不构成国际或国家筋膜链点位标准。',
        'terminologySources':[
            {'title':'FIPAT Terminologia Anatomica 2, 2019; IFAA adopted 2020','url':'https://cdn.dal.ca/content/dam/dalhousie/pdf/library/FIPAT/TA2/FIPAT-TA2-Front-Matter.pdf',
             'licenseNoteZh':'术语本身为公有领域；完整出版物为CC BY-ND 4.0。此数据未复制标准全文或完整表格。'},
            {'title':'Fascial nomenclature: Update on related consensus process, 2019','url':'https://pubmed.ncbi.nlm.nih.gov/31183880/'},
            {'title':'全国科学技术名词审定委员会：人体解剖学名词第二版（2014）','url':'https://www.cnterm.cn/jggk/sdfwyh/rtjpxmcsdwyh/','noteZh':'作为中文核名基准；当前译名未逐条完成专业审定。'}],
        'connections':connections}
    destination=ASSETS/'content/fascia.json';destination.write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n')
    report={'schemaVersion':1,'generator':{'path':str(Path(__file__).relative_to(ROOT)),'sha256':digest(Path(__file__))},'reproduceCommand':'python3 tools/content/build_fascia.py','purposeZh':'核验筋膜关系示意的真实网格表面来源与双档坐标一致性，不是医学审校。',
        'inputs':[{'path':str(m.path.relative_to(ROOT)),'sha256':m.sha256} for m in (standard,low)]+[{'path':str(anatomy_path.relative_to(ROOT)),'sha256':digest(anatomy_path)}],
        'outputs':[{'path':str(destination.relative_to(ROOT)),'sha256':digest(destination)}],
        'connections':len(connections),'instances':sum(len(c['paths']) for c in connections),
        'targets':len(audit),'professionalAnatomicalReviewedTargets':0,'clinicalTargets':0,
        'maxReconstructionErrorM':max(r['standardReconstructionErrorM'] for r in audit),
        'maxDistanceToLowSurfaceM':max(r['distanceToLowSurfaceM'] for r in audit),'targetAudit':audit,
        'limitsZh':['所有参考区仍待专业解剖定位审校。','连接线只表达文献关系，不代表真实纤维轨迹或力矢量。',
                    '骶结节韧带、跟骨骨膜与跟腱腱周膜没有独立网格，未假造。',
                    'Z-Anatomy与BodyParts3D的共同配准为教学参考，不能等同个体精确配准。']}
    out=ROOT/'manifests/fascia-content.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('connections','instances','targets','professionalAnatomicalReviewedTargets','maxReconstructionErrorM','maxDistanceToLowSurfaceM')},ensure_ascii=False))


if __name__=='__main__':main()
