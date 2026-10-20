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
import heapq
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets'
SIDES = ('right', 'left')
ZH = {'right': '右侧', 'left': '左侧'}
IDS = {
    'right': {'ld': 'ZA_172472462', 'tlf': 'ZA_390108657', 'gm': 'FJ1418', 'at': 'FJ1405',
              'heel': 'FJ3360', 'pa': 'ZA_753149753', 'bf': 'FJ1395', 'hip': 'FJ3152',
              'calf': 'FJ1397', 'itb': 'FJ1423', 'tfl': 'FJ1438', 'pl': 'FJ1502', 'fr': 'FJ1471',
              'tra': 'ZA_795120383', 'tlfm': 'ZA_258389767'},
    'left': {'ld': 'ZA_817053160', 'tlf': 'ZA_655962380', 'gm': 'FJ1418M', 'at': 'FJ1405M',
             'heel': 'FJ3256', 'pa': 'ZA_375911140', 'bf': 'FJ1395M', 'hip': 'FJ3288',
             'calf': 'FJ1397M', 'itb': 'FJ1423M', 'tfl': 'FJ1438M', 'pl': 'FJ1502M', 'fr': 'FJ1471M',
             'tra': 'ZA_881393607', 'tlfm': 'ZA_286507229'}
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


def sample(model,key,index,barycentric=None):
    mesh=model.mesh(key)
    face=mesh['faces'][index]; a,b,c=[mesh['vertices'][i] for i in face]
    face_normal=cross(sub(b,a),sub(c,a))
    assert norm(face_normal)>1e-12,(key,index,'degenerate target triangle')
    weights=[1/3,1/3,1/3] if barycentric is None else barycentric
    normal=unit(tuple(sum(mesh['normals'][i][j]*weight for i,weight in zip(face,weights)) for j in range(3))) if mesh['normals'] else unit(face_normal)
    # Keep the original centroid arithmetic byte-for-byte for the 20 preserved targets.
    if barycentric is None:
        normal=unit(tuple(sum(mesh['normals'][i][j] for i in face) for j in range(3))) if mesh['normals'] else unit(face_normal)
        position=mesh['centers'][index]
    else:position=tuple(sum(mesh['vertices'][i][j]*weight for i,weight in zip(face,weights)) for j in range(3))
    return {'position':position,'normal':normal,'sourceGeometry':{
        'sourceModel':'models/body-standard.glb','modelSha256':model.sha256,'meshGeometrySha256':mesh['sha256'],
        'nodeName':key,'primitiveIndex':0,'triangleIndex':index,'vertexIndices':list(face),
        'indexBase':0,'barycentric':weights}}


def pick(model,key,wanted,predicate=None):
    mesh=model.mesh(key)
    candidates=[i for i,p in enumerate(mesh['centers']) if predicate is None or predicate(p,mesh)]
    if not candidates:raise ValueError('Anatomical candidate region empty: '+key)
    index=min(candidates,key=lambda i:dot(sub(mesh['centers'][i],wanted),sub(mesh['centers'][i],wanted)))
    return sample(model,key,index)


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
    '66具冷冻标本的97侧半骨盆，经超声、尸解与组织学研究辨认近端腘绳肌邻接组织。骶结节韧带浅层与股二头肌长头的连续关系不等于个体治疗点。'),
 'stecco2013gluteus':source('stecco2013gluteus','The anatomical and functional relation between gluteus maximus and fascia lata',
    'https://pubmed.ncbi.nlm.nih.gov/24139012/',2013,'cadaver_anatomy',
    '6具尸体的解剖研究观察到臀大肌向阔筋膜、髂胫束的广泛附着；支持结构连续关系，未测得当前模型的实际受力。'),
 'birnbaum2004':source('birnbaum2004','Anatomical and biomechanical investigations of the iliotibial tract',
    'https://pubmed.ncbi.nlm.nih.gov/15378277/',2004,'cadaver_anatomy_and_mechanics',
    '18具未固定尸体的解剖与力学研究报告阔筋膜张肌插入髂胫束，后者分浅深层；此处仅表示连接关系，不把研究工况下的力值赋予静态模型。'),
 'stecco2007palmaris':source('stecco2007palmaris','Tendinous muscular insertions onto the deep fascia of the upper limb. First part: anatomical study',
    'https://pubmed.ncbi.nlm.nih.gov/17574470/',2007,'cadaver_anatomy',
    '30条上肢尸解观察掌长肌向腕屈肌支持带及鱼际表面筋膜的腱性扩展；当前显示已有支持带邻区，未独立建模掌腱膜或鱼际筋膜。'),
 'barker2007anatomy':source('barker2007anatomy','The middle layer of lumbar fascia and attachments to lumbar transverse processes: implications for segmental control and fracture',
    'https://pubmed.ncbi.nlm.nih.gov/17924150/',2007,'cadaver_anatomy',
    '18具尸体的双侧解剖观察中层外侧与腹横肌腱膜、肌束连续；当前网格未细分附着纤维和全部腱膜。'),
 'barker2004':source('barker2004','Tensile transmission across the lumbar fasciae in unembalmed cadavers: effects of tension to various muscular attachments',
    'https://pubmed.ncbi.nlm.nih.gov/14722403/',2004,'cadaver_loading',
    '8具未固定尸体在10N肌附着处牵拉下，用L3测力及标记位移观察到腹横肌向腰筋膜中层和后层的张力传递；示意分支不是个体力矢量或定量力场。')
}


def path(pid,side,targets):
    return {'id':pid,'side':side,'points':[t['position'] for t in targets],'targetIds':[t['id'] for t in targets],
        'normals':[t['normal'] for t in targets],'sourceGeometrySamples':[t['sourceGeometry'] for t in targets],
        'kind':'unoriented_connection_schematic','reviewStatus':'provisional',
        'notAForceVector':True,'direction':None,'forceMagnitude':None,
        'descriptionZh':'连接参考区之间的无方向关系示意；折线不表示完整组织走向、纤维方向或实际受力路径。'}


def tighten_surface_corridor(mesh,walk):
    """Shorten the fixed triangle corridor by moving crossings only along real edges.

    Each variable is one edge parameter in [0,1]. The sum of adjacent segment
    lengths is convex in that variable. Coordinate descent never moves a crossing
    outside its shared edge, so consecutive crossings remain in one real triangle.
    """
    edges=[]
    for a,b in zip(walk,walk[1:]):
        shared=set(mesh['vertices'][i] for i in mesh['faces'][a]) & set(mesh['vertices'][i] for i in mesh['faces'][b])
        assert len(shared)>=2,(a,b,'surface walk faces do not share an edge')
        edges.append(sorted(shared)[:2])
    parameters=[.5]*len(edges)
    positions=[mesh['centers'][walk[0]]]+[mul(add(*edge),.5) for edge in edges]+[mesh['centers'][walk[-1]]]
    center_length=sum(norm(sub(mesh['centers'][a],mid))+norm(sub(mid,mesh['centers'][b]))
        for a,b,mid in zip(walk,walk[1:],positions[1:-1]))
    before=sum(norm(sub(a,b)) for a,b in zip(positions,positions[1:]))
    sweeps=0
    for sweep in range(36):
        greatest_change=0.
        # Alternate sweep direction to avoid favouring one anatomical endpoint.
        order=range(len(edges)) if sweep%2==0 else range(len(edges)-1,-1,-1)
        for index in order:
            edge=edges[index];delta=sub(edge[1],edge[0]);prev=positions[index];next_point=positions[index+2]
            def position(t):return add(edge[0],mul(delta,t))
            def cost(t):
                point=position(t)
                return norm(sub(prev,point))+norm(sub(next_point,point))
            lo=0.;hi=1.
            for _ in range(30):
                left=(2*lo+hi)/3;right=(lo+2*hi)/3
                if cost(left)<=cost(right):hi=right
                else:lo=left
            candidates=(parameters[index],0.,1.,(lo+hi)/2)
            t=min(candidates,key=cost);point=position(t)
            greatest_change=max(greatest_change,norm(sub(point,positions[index+1])))
            parameters[index]=t;positions[index+1]=point
        sweeps=sweep+1
        if greatest_change<1e-7:break
    after=sum(norm(sub(a,b)) for a,b in zip(positions,positions[1:]))
    assert after<=before+1e-12 and after<=center_length+1e-12
    assert positions[0]==mesh['centers'][walk[0]] and positions[-1]==mesh['centers'][walk[-1]]
    return edges,parameters,{'method':'shared_edge_constrained_coordinate_descent','sweeps':sweeps,
        'fixedEndpoints':True,'beforeCenterWalkLengthM':center_length,'beforeEdgeMidpointLengthM':before,
        'afterEdgeStringLengthM':after,'lengthReductionM':center_length-after,
        'maximumOffSurfaceDisplacementM':0.}


def surface_context(model,key,pid,side,start,end,rule):
    """Walk connected, genuine triangles; samples remain real surface barycentres.

    This shortest surface walk is a display aid for tissue extent, NOT a reconstruction
    of fibre orientation. Exact-position edge keys bridge GLB normal seams without
    welding nearby distinct vertices or crossing disconnected anatomical surfaces.
    """
    mesh=model.mesh(key); first=start['sourceGeometry']['triangleIndex']; last=end['sourceGeometry']['triangleIndex']
    if 'neighbors' not in mesh:
        edges={};neighbors=[set() for _ in mesh['faces']]
        for index,face in enumerate(mesh['faces']):
            for a,b in ((face[0],face[1]),(face[1],face[2]),(face[2],face[0])):
                edge=tuple(sorted((mesh['vertices'][a],mesh['vertices'][b])))
                for other in edges.get(edge,()):neighbors[index].add(other);neighbors[other].add(index)
                edges.setdefault(edge,[]).append(index)
        mesh['neighbors']=neighbors
    # The source atlas preserves separate muscle slips/sheets. Do not invent a bridge
    # across disconnected pieces. Keep the attachment end, and resolve the other
    # extent sample onto the same genuine connected sheet.
    component=set();pending=[last]
    while pending:
        current=pending.pop()
        if current in component:continue
        component.add(current);pending.extend(mesh['neighbors'][current]-component)
    requested_first=first
    if first not in component:
        first=min(component,key=lambda index:norm(sub(mesh['centers'][index],start['position'])))
    queue=[(0.,first)]; distances={first:0.};previous={}
    while queue:
        distance,current=heapq.heappop(queue)
        if distance!=distances[current]:continue
        if current==last:break
        for other in sorted(mesh['neighbors'][current]):
            candidate=distance+norm(sub(mesh['centers'][current],mesh['centers'][other]))
            if candidate<distances.get(other,float('inf')):
                distances[other]=candidate;previous[other]=current;heapq.heappush(queue,(candidate,other))
    if last not in distances:raise ValueError(f'Disconnected context endpoints: {key}/{pid}')
    walk=[last]
    while walk[-1]!=first:walk.append(previous[walk[-1]])
    walk.reverse()
    edges,parameters,optimization=tighten_surface_corridor(mesh,walk)
    # Tighten a string through shared edges, with its ends fixed at the same actual
    # face centres. Every segment remains inside a common real triangle. No free
    # 3D spline, screen-space smoothing or inferred anatomical fibre is generated.
    samples=[sample(model,key,walk[0])]
    center_weights=[1/3,1/3,1/3]
    def append_segment(index,from_weights,to_weights):
        begin=sample(model,key,index,from_weights);end=sample(model,key,index,to_weights)
        if norm(sub(begin['position'],end['position']))<1e-12:return
        pieces=max(1,math.ceil(norm(sub(begin['position'],end['position']))/.008))
        for step in range(1,pieces+1):
            fraction=step/pieces
            weights=[a+(b-a)*fraction for a,b in zip(from_weights,to_weights)]
            samples.append(sample(model,key,index,weights))
    def crossing_weights(face,edge,t):
        return [(1-t if mesh['vertices'][i]==edge[0] else t if mesh['vertices'][i]==edge[1] else 0.)
            for i in face]
    for index,triangle in enumerate(walk):
        face=mesh['faces'][triangle]
        enter=center_weights if index==0 else crossing_weights(face,edges[index-1],parameters[index-1])
        leave=center_weights if index==len(walk)-1 else crossing_weights(face,edges[index],parameters[index])
        append_segment(triangle,enter,leave)
    for item in samples:item['sourceGeometry']['selectionRuleZh']=rule
    return {'id':pid,'side':side,'points':[rounded(s['position']) for s in samples],
        'normals':[rounded(s['normal']) for s in samples],
        'sourceGeometrySamples':[s['sourceGeometry'] for s in samples],
        'targetIds':[],'structureIds':[key],'kind':'anatomical_context_schematic','reviewStatus':'provisional',
        'notAForceVector':True,'direction':None,'forceMagnitude':None,
        'connectedSurfaceTriangles':len(component),
        'surfaceOptimization':optimization,
        'startResolutionShiftM':norm(sub(mesh['centers'][first],mesh['centers'][requested_first])),
        'endpointPolicyZh':'保持末端参考面；近端范围候选若位于另一独立肌束或薄片，只在末端所属真实连通曲面内重新取最近面，不跨缺口补线。',
        'surfaceWalkTriangleCount':len(walk),'sampleSpacingPolicyZh':'固定真实起止面中心，在相邻三角面共有边内约束收紧折线路径，再在每个真实三角面内以重心坐标细分至最多8毫米间隔；不使用自由样条，不跨曲面或缺口平滑。',
        'descriptionZh':'涉及组织的范围参考段，采样点来自真实网格表面；表面最短走向只用于看清位置及范围，不代表纤维方向、精确附着范围、整条筋膜链或实测受力路径。'}


def context_between_seeds(model,key,pid,side,start_fractions,end_fractions,rule):
    mesh=model.mesh(key)
    return surface_context(model,key,pid,side,pick(model,key,seed(mesh,*start_fractions)),
        pick(model,key,seed(mesh,*end_fractions)),rule)


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
    add_context_paths(model,connections)
    connections.extend(build_additional_connections(model,common))
    regions={
        'latissimus-thoracolumbar-contralateral-gluteus':['背部','腰部','臀部'],
        'achilles-calcaneal-region-plantar-aponeurosis':['小腿后侧','踝后部','足底'],
        'biceps-long-head-ischial-sacrotuberous-region':['大腿后侧','坐骨邻区'],
        'gluteus-maximus-iliotibial-tract':['臀部','大腿外侧'],
        'tensor-fasciae-latae-iliotibial-tract':['髋外侧','大腿外侧'],
        'palmaris-longus-flexor-retinaculum':['前臂前侧','腕掌侧'],
        'transversus-abdominis-thoracolumbar-fascia':['腹侧壁','腰部']}
    for connection in connections:
        connection['coverageKind']='local_research_relation'
        connection['bodyRegionsZh']=regions[connection['id']]
        connection['preferredViewNormal']=([0,0,1] if connection['id'] in
            ('tensor-fasciae-latae-iliotibial-tract','palmaris-longus-flexor-retinaculum') else [0,0,-1])
    return connections


def add_context_paths(model,connections):
    byid={connection['id']:connection for connection in connections}
    for side in SIDES:
        ids=IDS[side];other='left' if side=='right' else 'right';opposite=IDS[other]
        ld=byid['latissimus-thoracolumbar-contralateral-gluteus']
        original={t['id']:t for t in ld['targets']}
        mesh=model.mesh(ids['ld'])
        ld['paths'].append(surface_context(model,ids['ld'],f'context-ld-{side}',side,
            pick(model,ids['ld'],seed(mesh,.15 if side=='right' else .85,.88,.25)),
            original[f'ld-tlf-{side}-ld'],
            '背阔肌上外侧到既有下内侧连接参考区的真实相邻三角面表面走向；仅表示涉及组织范围。'))
        ld['paths'].append(context_between_seeds(model,ids['tlf'],f'context-tlf-{side}',side,
            (.45,.94,.1),(.45,.06,.1),'胸腰筋膜后层上、下部真实面的表面范围参考，不表示纤维或张力方向。'))
        ld['paths'].append(context_between_seeds(model,opposite['gm'],f'context-gm-cross-{side}',other,
            (.82 if other=='right' else .18,.90,.12),(.35 if other=='right' else .65,.10,.12),
            '对侧臀大肌后表面的上部至下部范围参考；不延伸成完整后功能线。'))
        heel=byid['achilles-calcaneal-region-plantar-aponeurosis']; targets={t['id']:t for t in heel['targets']}
        calf=ids['calf'];heel['paths'].append(context_between_seeds(model,calf,f'context-calf-{side}',side,
            (.5,.92,.08),(.5,.06,.08),'腓肠肌内侧头后表面近端至远端的组织范围参考；不表示力线方向。'))
        at=model.mesh(ids['at'])
        heel['paths'].append(surface_context(model,ids['at'],f'context-achilles-{side}',side,
            pick(model,ids['at'],seed(at,.5,.94,.15)),targets[f'heel-{side}-at'],
            '跟腱近端至既有远端连接区的真实表面范围参考；不表示单根腱纤维走向。'))
        pa=model.mesh(ids['pa'])
        heel['paths'].append(surface_context(model,ids['pa'],f'context-plantar-{side}',side,
            targets[f'heel-{side}-pa'],pick(model,ids['pa'],seed(pa,.5,.25,.92)),
            '既有足底腱膜近端参考区至远端前足邻区的真实表面范围参考；不改变跟骨桥接解释。'))
        heel['structureIds']=list(dict.fromkeys(heel['structureIds']+[calf]))
        ham=byid['biceps-long-head-ischial-sacrotuberous-region'];targets={t['id']:t for t in ham['targets']}
        bf=model.mesh(ids['bf'])
        ham['paths'].append(surface_context(model,ids['bf'],f'context-biceps-long-{side}',side,
            targets[f'hamstring-{side}-bf'],pick(model,ids['bf'],seed(bf,.5,.06,.10)),
            '股二头肌长头既有近端参考区至远端肌腱邻区的后表面范围参考；缺失骶结节韧带没有被补画。'))


def build_additional_connections(model,common):
    connections=[]
    definitions=[
        ('gluteus-maximus-iliotibial-tract','臀大肌—髂胫束',0xffed987e,
         '臀大肌向阔筋膜及髂胫束的解剖附着关系。',
         '尸解研究报告臀大肌向阔筋膜及髂胫束的大范围附着。此处仅呈现模型中的邻接参考区与组织范围，不提供个体力值。',
         ['stecco2013gluteus'],'臀大肌与髂胫束均为真实网格；各肌纤维向阔筋膜的附着分区没有逐层拆分。',['阔筋膜全部分层','臀大肌向筋膜的个体纤维附着范围']),
        ('tensor-fasciae-latae-iliotibial-tract','阔筋膜张肌—髂胫束',0xff8cb5ee,
         '阔筋膜张肌远端与髂胫束的附着关系。',
         '尸解与力学研究记述阔筋膜张肌插入髂胫束，以及髂胫束的深浅层。当前单一髂胫束网格未复现研究中的全部层次。',
         ['birnbaum2004'],'阔筋膜张肌和髂胫束均为真实网格；源模型未分出髂胫束各层及完整包绕关系。',['髂胫束深浅层分区']),
        ('palmaris-longus-flexor-retinaculum','掌长肌—腕屈肌支持带',0xffe5abdf,
         '掌长肌腱与腕屈肌支持带邻区的解剖关系。',
         '上肢尸解观察到掌长肌向屈肌支持带及鱼际筋膜的腱性扩展。本图只显示已有掌长肌及支持带，不把未建模鱼际筋膜、掌腱膜补成直接连续结构。',
         ['stecco2007palmaris'],'掌长肌与腕屈肌支持带均为真实网格；远端扩展纤维未逐束建模，掌腱膜与鱼际筋膜未进入当前GLB。',['掌腱膜','鱼际筋膜','掌长肌远端扩展纤维']),
        ('transversus-abdominis-thoracolumbar-fascia','腹横肌—胸腰筋膜中后层',0xffa8cc78,
         '腹横肌腱膜与胸腰筋膜中层、后层的分支关系。',
         '尸解研究观察胸腰筋膜中层外侧与腹横肌腱膜、肌束连续；牵拉实验报告向中层和后层的张力传递。两条局部虚线分支表示研究关系，不代表同一根纤维穿行所有层。',
         ['barker2004','barker2007anatomy'],'腹横肌、胸腰筋膜中层及后层均为真实网格；腹横肌腱膜与外侧缝未独立拆网，不能显示其显微分支。',['腹横肌腱膜独立分区','胸腰筋膜外侧缝的微细连接'])]
    for cid,name,color,description,evidence,refs,coverage,missing in definitions:
        targets=[];paths=[]
        for side in SIDES:
            ids=IDS[side];outer=.04 if side=='right' else .96
            if cid=='gluteus-maximus-iliotibial-tract':
                gm=model.mesh(ids['gm']);itb=model.mesh(ids['itb'])
                a=target(model,ids['gm'],f'gm-itb-{side}-gm',ZH[side]+'臀大肌外侧附着邻区',
                    '臀大肌外侧靠近髂胫束的表面邻区；不代表全部浅层纤维的附着边界。',side,seed(itb,.5,.73,.25),
                    lambda p,m:abs(p[0])>max(abs(m['lo'][0]),abs(m['hi'][0]))*.72 and p[1]>m['lo'][1]+.30*(m['hi'][1]-m['lo'][1]),
                    '在臀大肌外侧28%且高于下30%的真实面中，选择靠近髂胫束上段的面。')
                b=target(model,ids['itb'],f'gm-itb-{side}-itb',ZH[side]+'髂胫束近端后部',
                    '真实髂胫束上段邻近臀大肌的参考面，非个体附着边界。',side,a['position'],
                    lambda p,m:p[1]>m['lo'][1]+.55*(m['hi'][1]-m['lo'][1]),
                    '在髂胫束近端45%的真实面中选择最接近臀大肌外侧参考区的面。')
                own=[a,b];paths.append(path(f'gm-itb-{side}',side,own))
                paths.append(context_between_seeds(model,ids['gm'],f'context-gm-lateral-{side}',side,
                    (.8 if side=='right' else .2,.90,.10),(outer,.38,.18),'臀大肌后表面至外侧邻区的组织范围参考；不是固定纤维方向。'))
                paths.append(context_between_seeds(model,ids['itb'],f'context-itb-gm-{side}',side,
                    (outer,.94,.5),(outer,.04,.5),'髂胫束近端至远端的实际外侧表面范围参考；不表示张力矢量。'))
            elif cid=='tensor-fasciae-latae-iliotibial-tract':
                tfl=model.mesh(ids['tfl'])
                a=target(model,ids['tfl'],f'tfl-itb-{side}-tfl',ZH[side]+'阔筋膜张肌远端',
                    '阔筋膜张肌远端腱膜邻区。',side,seed(tfl,outer,.03,.50),
                    lambda p,m:p[1]<m['lo'][1]+.20*(m['hi'][1]-m['lo'][1]),
                    '在阔筋膜张肌远端20%的真实面中选择远端外侧参考位置。')
                b=target(model,ids['itb'],f'tfl-itb-{side}-itb',ZH[side]+'髂胫束前上部',
                    '髂胫束前上部邻近阔筋膜张肌的真实表面参考区。',side,a['position'],
                    lambda p,m:p[1]>m['lo'][1]+.58*(m['hi'][1]-m['lo'][1]),
                    '在髂胫束近端42%的真实面中选择最接近阔筋膜张肌远端的面。')
                own=[a,b];paths.append(path(f'tfl-itb-{side}',side,own))
                paths.append(surface_context(model,ids['tfl'],f'context-tfl-{side}',side,
                    pick(model,ids['tfl'],seed(tfl,outer,.94,.5)),a,'阔筋膜张肌近端至远端的实际表面范围参考。'))
                paths.append(context_between_seeds(model,ids['itb'],f'context-itb-tfl-{side}',side,
                    (outer,.94,.65),(outer,.04,.65),'髂胫束实际外侧表面的长段参考；并非完整外侧筋膜链。'))
            elif cid=='palmaris-longus-flexor-retinaculum':
                pl=model.mesh(ids['pl']);fr=model.mesh(ids['fr']);wanted=seed(fr,.5,.5,.90)
                a=target(model,ids['pl'],f'palmaris-{side}-tendon',ZH[side]+'掌长肌腕部肌腱',
                    '掌长肌腱在腕掌侧与支持带邻近的真实表面区；掌长肌存在常见个体变异。',side,wanted,
                    lambda p,m:fr['lo'][1]-.015<p[1]<fr['hi'][1]+.025,
                    '限制在腕屈肌支持带上下邻近范围内，选择掌长肌最靠近支持带掌侧的真实面。')
                b=target(model,ids['fr'],f'palmaris-{side}-retinaculum',ZH[side]+'腕屈肌支持带掌侧',
                    '真实腕屈肌支持带掌侧的邻接参考区，不作为腕管穿刺或手术定位。',side,a['position'],
                    lambda p,m:p[2]>m['lo'][2]+.45*(m['hi'][2]-m['lo'][2]),
                    '在腕屈肌支持带前55%的真实面中选择最接近掌长肌腱参考区的面。',radius=.008)
                own=[a,b];paths.append(path(f'palmaris-{side}',side,own))
                paths.append(surface_context(model,ids['pl'],f'context-palmaris-{side}',side,
                    pick(model,ids['pl'],seed(pl,.5,.94,.8)),a,'掌长肌前臂近端至腕部肌腱的实际表面范围参考，不补画缺失掌腱膜。'))
                paths.append(context_between_seeds(model,ids['fr'],f'context-retinaculum-{side}',side,
                    (.08,.5,.9),(.92,.5,.9),'腕屈肌支持带掌侧横向范围参考，不表示纤维牵拉方向。'))
            else:
                tra=model.mesh(ids['tra']);mid=model.mesh(ids['tlfm']);posterior=model.mesh(ids['tlf'])
                a=target(model,ids['tra'],f'tra-tlf-{side}-tra',ZH[side]+'腹横肌后外侧腱膜邻区',
                    '腹横肌后外侧靠近胸腰筋膜的表面邻区；源模型没有分开标出腱膜。',side,seed(mid,.5,.55,.9),
                    lambda p,m:p[2]<m['lo'][2]+.25*(m['hi'][2]-m['lo'][2]) and mid['lo'][1]<p[1]<mid['hi'][1],
                    '在腹横肌后25%及胸腰筋膜中层对应高度的真实面中选择邻近后外侧腱膜区域。')
                b=target(model,ids['tlfm'],f'tra-tlf-{side}-middle',ZH[side]+'胸腰筋膜中层外侧',
                    '胸腰筋膜中层外侧的真实面参考区；不代表腱膜纤维的附着范围。',side,a['position'],
                    lambda p,m:abs(p[0])>min(abs(m['lo'][0]),abs(m['hi'][0]))+.55*(max(abs(m['lo'][0]),abs(m['hi'][0]))-min(abs(m['lo'][0]),abs(m['hi'][0]))),
                    '在胸腰筋膜中层外侧45%的真实面中选择最靠近腹横肌后外侧参考区的面。')
                c=target(model,ids['tlf'],f'tra-tlf-{side}-posterior',ZH[side]+'胸腰筋膜后层外侧',
                    '胸腰筋膜后层外侧参考区；虚线分支用于区别中、后层研究关系。',side,a['position'],
                    lambda p,m:abs(p[0])>.55*max(abs(m['lo'][0]),abs(m['hi'][0])),
                    '在胸腰筋膜后层外侧45%的真实面中选择最靠近腹横肌后外侧参考区的面。')
                own=[a,b,c];paths.extend((path(f'tra-tlf-{side}-middle',side,[a,b]),path(f'tra-tlf-{side}-posterior',side,[a,c])))
                paths.append(context_between_seeds(model,ids['tra'],f'context-tra-{side}',side,
                    (.40 if side=='right' else .60,.58,.88),(.40 if side=='right' else .60,.58,.06),
                    '腹横肌前外侧至后外侧真实表面的横向范围参考；不是源模型未提供的肌纤维测量。'))
                for tissue in ('tlfm','tlf'):
                    paths.append(context_between_seeds(model,ids[tissue],f'context-tra-{tissue}-{side}',side,
                        (.5,.92,.5),(.5,.08,.5),'所涉及胸腰筋膜层的上下范围参考，来源为实际相邻三角面。'))
            targets+=own
        connections.append({'id':cid,'nameZh':name,'descriptionZh':description+common,'evidenceZh':evidence,
            'color':color,'structureIds':list(dict.fromkeys(key for p in paths for key in p.get('structureIds',[])))+
                list(dict.fromkeys(key for t in targets for key in t['structureIds'])),
            'paths':paths,'targets':targets,'sourceRefs':[SOURCES[key] for key in refs],
            'geometryCoverageZh':coverage,'unmappedStructures':missing,'reviewStatus':'provisional'})
        connections[-1]['structureIds']=list(dict.fromkeys(connections[-1]['structureIds']))
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
    rows=[];ids=set();path_ids=set();all_structure_ids={s['id'] for s in anatomy['structures']}
    for connection in connections:
        assert {p['side'] for p in connection['paths']}==set(SIDES)
        assert connection['coverageKind']=='local_research_relation' and connection['bodyRegionsZh']
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
            assert p['id'] not in path_ids;path_ids.add(p['id'])
            assert p['kind'] in ('unoriented_connection_schematic','anatomical_context_schematic') and p['notAForceVector']
            assert p['direction'] is None and p['forceMagnitude'] is None
            assert len(p['points'])==len(p['normals'])==len(p['sourceGeometrySamples'])>=2
            if p['kind']=='unoriented_connection_schematic':
                assert len(p['points'])==len(p['targetIds'])
                assert all(byid[tid]['position']==point for tid,point in zip(p['targetIds'],p['points']))
            else:
                assert not p['targetIds'] and p['surfaceWalkTriangleCount']>=2
                assert max(norm(sub(a,b)) for a,b in zip(p['points'],p['points'][1:]))<=.008000001
                optimization=p['surfaceOptimization']
                assert optimization['fixedEndpoints'] and optimization['maximumOffSurfaceDisplacementM']==0
                assert optimization['afterEdgeStringLengthM']<=optimization['beforeEdgeMidpointLengthM']+1e-12
            for index,(position,normal,g) in enumerate(zip(p['points'],p['normals'],p['sourceGeometrySamples'])):
                mesh=standard.mesh(g['nodeName']);tri=mesh['faces'][g['triangleIndex']]
                assert list(tri)==g['vertexIndices'] and g['modelSha256']==standard.sha256
                assert g['meshGeometrySha256']==mesh['sha256'] and abs(sum(g['barycentric'])-1)<1e-12
                reconstructed=tuple(sum(mesh['vertices'][i][axis]*weight for i,weight in zip(tri,g['barycentric'])) for axis in range(3))
                error=norm(sub(position,reconstructed));assert error<1e-8
                assert abs(norm(normal)-1)<1e-8 and all(math.isfinite(v) for v in position+normal)
                if p['kind']=='anatomical_context_schematic':
                    assert (position[0]>0)==(p['side']=='left'),('path side mismatch',p['id'],index)
                lm=low.mesh(g['nodeName']);distance=distance_to_mesh(position,lm)
                assert distance<.012,(p['id'],index,'low-quality context displacement >12mm',distance)
                rows.append({'pathId':p['id'],'kind':p['kind'],'sampleIndex':index,'nodeName':g['nodeName'],
                    'standardReconstructionErrorM':error,'distanceToLowSurfaceM':distance})
    return rows


def distance_to_mesh(position,mesh):
    if 'faceRadii' not in mesh:
        mesh['faceRadii']=[max(norm(sub(mesh['vertices'][i],center)) for i in face)
            for face,center in zip(mesh['faces'],mesh['centers'])]
    best=min(norm(sub(position,p)) for p in mesh['centers'])
    for face,center,radius in zip(mesh['faces'],mesh['centers'],mesh['faceRadii']):
        if norm(sub(position,center))-radius<=best:
            best=min(best,point_triangle_distance(position,*(mesh['vertices'][i] for i in face)))
    return best


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.parse_args()
    standard=Model(ASSETS/'models/body-standard.glb');low=Model(ASSETS/'models/body-low.glb')
    required={key for side in IDS.values() for key in side.values()}
    missing=required-standard.nodes.keys()
    if missing:raise RuntimeError('追加真实筋膜网格尚未进入标准GLB，请等待资产构建完成: '+','.join(sorted(missing)))
    anatomy_path=ASSETS/'anatomy.json';anatomy=json.loads(anatomy_path.read_text())
    connections=build_connections(standard);audit=validate(connections,standard,low,anatomy)
    original_targets=[t for connection in connections[:3] for t in connection['targets']]
    original_targets_sha=hashlib.sha256(json.dumps(original_targets,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    assert original_targets_sha=='db9b2f0f316f433a596427888410e24635d823fe923a2a441a31b808d917ee26','Original 20 targets changed'
    document={'schemaVersion':2,'modelId':anatomy['modelId'],'sex':'male',
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
        'connections':len(connections),'instances':sum(len({p['side'] for p in c['paths']}) for c in connections),
        'relationPaths':sum(p['kind']=='unoriented_connection_schematic' for c in connections for p in c['paths']),
        'contextPaths':sum(p['kind']=='anatomical_context_schematic' for c in connections for p in c['paths']),
        'targets':sum(len(c['targets']) for c in connections),'professionalAnatomicalReviewedTargets':0,'clinicalTargets':0,
        'originalTargetsUnchanged':len(original_targets),'originalTargetsCanonicalSha256':original_targets_sha,
        'pathKinds':{kind:sum(p['kind']==kind for c in connections for p in c['paths'])
            for kind in ('unoriented_connection_schematic','anatomical_context_schematic')},
        'contextSamples':sum(len(p['points']) for c in connections for p in c['paths'] if p['kind']=='anatomical_context_schematic'),
        'pathMetrics':[{'id':p['id'],'connectionId':c['id'],'kind':p['kind'],'side':p['side'],
            'points':len(p['points']),'lengthM':sum(norm(sub(a,b)) for a,b in zip(p['points'],p['points'][1:])),
            'maximumSampleGapM':max(norm(sub(a,b)) for a,b in zip(p['points'],p['points'][1:])),
            'connectedSurfaceTriangles':p.get('connectedSurfaceTriangles'),
            'startResolutionShiftM':p.get('startResolutionShiftM',0.),
            'surfaceOptimization':p.get('surfaceOptimization')}
            for c in connections for p in c['paths']],
        'maxReconstructionErrorM':max(r['standardReconstructionErrorM'] for r in audit),
        'maxDistanceToLowSurfaceM':max(r['distanceToLowSurfaceM'] for r in audit),
        'targetAudit':[r for r in audit if 'targetId' in r],
        'contextSampleAudit':[r for r in audit if r.get('kind')=='anatomical_context_schematic'],
        'limitsZh':['所有参考区仍待专业解剖定位审校。','连接线只表达文献关系，不代表真实纤维轨迹或力矢量。',
                    '骶结节韧带、跟骨骨膜与跟腱腱周膜没有独立网格，未假造。',
                    'Z-Anatomy与BodyParts3D的共同配准为教学参考，不能等同个体精确配准。']}
    out=ROOT/'manifests/fascia-content.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('connections','instances','targets','professionalAnatomicalReviewedTargets','maxReconstructionErrorM','maxDistanceToLowSurfaceM')},ensure_ascii=False))


if __name__=='__main__':main()
