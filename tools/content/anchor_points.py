#!/usr/bin/env python3
"""Generate provisional anatomical teaching anchors on the real BodyParts3D mesh.
No anchor is marked medically reviewed. Source text remains authoritative.
"""
from pathlib import Path
import json,re,math,hashlib,zipfile,collections,csv
import numpy as np
from chest_anchors import POINT_IDS as CHEST_POINT_IDS, locate as locate_chest
from side_chest_anchors import POINT_IDS as SIDE_CHEST_POINT_IDS, locate as locate_side_chest
from cun_units import normalized_location, torso_scales
from limb_cun import scale as limb_scale, point_target as explicit_limb_target, POINT_IDS as EXPLICIT_LIMB_IDS
from cun_surface import project_horizontal
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'app/src/main/assets/content';AS=ROOT/'tools/assets/source'
MODEL='bodyparts3d-male-v4';GROUND=-78.1112
meta=json.loads((ROOT/'app/src/main/assets/anatomy.json').read_text()); parts={s['id']:s for s in meta['structures']}
CACHE={}; RAW={}; NORMALS={}; TRIANGLES={}; PROJECTIONS={}; ZIP=None; ZMAP={}
CHEST_REFERENCES={}
SIDE_CHEST_REFERENCES={}
MEASUREMENT_EVIDENCE={}
SKIN_FACES=None
def vertices(fid):
 global ZIP,ZMAP
 if fid in CACHE:return CACHE[fid]
 p=AS/(fid+'.obj')
 if p.exists():raw=p.read_text()
 else:
  if ZIP is None:
   ZIP=zipfile.ZipFile(AS/'isa_BP3D_4.0_obj_99.zip');ZMAP={Path(n).stem:n for n in ZIP.namelist() if n.endswith('.obj')}
  raw=ZIP.read(ZMAP[fid]).decode()
 RAW[fid]=raw
 a=np.array([list(map(float,l.split()[1:4])) for l in raw.splitlines() if l.startswith('v ')])
 a=np.c_[a[:,0]*.001,(a[:,2]-GROUND)*.001,-a[:,1]*.001];CACHE[fid]=a;return a

def normal_at(fid,position):
 if fid not in NORMALS:
  vertices(fid);n=np.array([list(map(float,l.split()[1:4])) for l in RAW[fid].splitlines() if l.startswith('vn ')])
  if len(n)!=len(CACHE[fid]):raise ValueError(('normal/vertex-count',fid,len(n),len(CACHE[fid])))
  n=np.c_[n[:,0],n[:,2],-n[:,1]];n=n/np.maximum(np.linalg.norm(n,axis=1)[:,None],1e-12);NORMALS[fid]=n
 v=vertices(fid);i=int(np.argmin(np.sum((v-np.asarray(position))**2,axis=1)));return NORMALS[fid][i],i+1,float(np.linalg.norm(v[i]-position))

def find(name):return next(s['id'] for s in parts.values() if s['nameEn']==name)
def bounds(fid):return np.array(parts[fid]['bounds']['min']),np.array(parts[fid]['bounds']['max'])
def center(fid):a,b=bounds(fid);return (a+b)/2
SKIN=vertices('FJ2810')
def nearest(target,fid='FJ2810'):
 v=vertices(fid);t=np.asarray(target);i=np.argmin(np.sum((v-t)**2,axis=1));return v[i]
def project(x,y,face='front',z=None,*,require_intersection=False):
 # Intersect a Z-directed ray with real skin triangles. Source skin is a shell,
 # so vertex-normal sign alone cannot distinguish the outer front/back layers.
 fid='FJ2810'
 if fid not in TRIANGLES:
  fs=np.array([[int(t.split('/')[0])-1 for t in l.split()[1:4]] for l in RAW[fid].splitlines() if l.startswith('f ')],dtype=np.int32)
  tv=SKIN[fs];TRIANGLES[fid]=(fs,tv,tv[:,:,0].min(1),tv[:,:,0].max(1),tv[:,:,1].min(1),tv[:,:,1].max(1))
 fs,tv,xmin,xmax,ymin,ymax=TRIANGLES[fid]
 ix=np.flatnonzero((xmin<=x)&(xmax>=x)&(ymin<=y)&(ymax>=y))
 a,b,c=tv[ix,0],tv[ix,1],tv[ix,2]
 den=(b[:,1]-c[:,1])*(a[:,0]-c[:,0])+(c[:,0]-b[:,0])*(a[:,1]-c[:,1])
 good=abs(den)>1e-14;ix=ix[good];a,b,c=a[good],b[good],c[good];den=den[good]
 w1=((b[:,1]-c[:,1])*(x-c[:,0])+(c[:,0]-b[:,0])*(y-c[:,1]))/den
 w2=((c[:,1]-a[:,1])*(x-c[:,0])+(a[:,0]-c[:,0])*(y-c[:,1]))/den;w3=1-w1-w2
 inside=(w1>=-1e-7)&(w2>=-1e-7)&(w3>=-1e-7);ix=ix[inside];weights=np.c_[w1[inside],w2[inside],w3[inside]]
 if not len(ix):
  if require_intersection:raise ValueError(f'No source-skin triangle at exact X={x}, Y={y}; nearest-vertex fallback is forbidden.')
  return nearest([x,y,.25 if face=='front' else -.05])
 hit=np.sum(tv[ix]*weights[:,:,None],axis=1)
 choice=int(np.argmax(hit[:,2]) if face=='front' else np.argmin(hit[:,2]) if face=='back' else np.argmin(abs(hit[:,2]-(z or .08))))
 v=hit[choice];normal_at(fid,v);normal=np.sum(NORMALS[fid][fs[ix[choice]]]*weights[choice,:,None],axis=0)
 expected=1 if face=='front' else -1
 if normal[2]*expected<0:normal=-normal
 normal/=max(np.linalg.norm(normal),1e-12)
 PROJECTIONS[tuple(v)]={'sourceTriangle':[int(k)+1 for k in fs[ix[choice]]],'barycentric':[float(k) for k in weights[choice]],'normal':normal}
 return v

def sideproject(target,direction):
 t=np.asarray(target);d=np.asarray(direction,dtype=float);d/=np.linalg.norm(d)
 q=SKIN[(abs(SKIN[:,1]-t[1])<.008)&(abs(SKIN[:,0]-t[0])<.065)]
 if len(q)<4:return nearest(t+d*.025)
 dot=(q-t)@d;perp=np.sum(((q-t)-dot[:,None]*d)**2,axis=1)
 # Requested front/back/medial/lateral surface, with a locality penalty.
 good=(dot>0)&(dot<.1)
 if not np.any(good):return nearest(t+d*.025)
 ids=np.flatnonzero(good);cost=perp[good]+.04*(dot[good]-.025)**2
 return q[ids[np.argmin(cost)]]

def exact_sideproject(target,direction):
 global SKIN_FACES
 if SKIN_FACES is None:
  SKIN_FACES=np.array([[int(t.split('/')[0])-1 for t in line.split()[1:4]] for line in RAW['FJ2810'].splitlines() if line.startswith('f ')],dtype=np.int32)
 hit=project_horizontal(SKIN,SKIN_FACES,target,direction)
 v=np.asarray(hit['position'])
 PROJECTIONS[tuple(v)]={key:hit[key] for key in ('sourceTriangle','barycentric','normal')}
 return v

def limb_references(side,mapped):
 def reference(name,position,ids,limitation):
  return {'position':list(map(float,position)), 'source':{'anatomicalReference':name,'structureIds':ids},
          'proxyLimitations':[limitation] if limitation else []}
 result={}
 for name,key in [('patella_apex','patella-tip'),('patella_base','patella-base'),('medial_malleolus','ankle-medial'),('lateral_malleolus','ankle-lateral'),('elbow_crease','elbow'),('wrist_crease','wrist')]:
  key=side+'-'+key
  result[name]=reference(name,L[key],refs.get(key,[]),'Source-mesh bone bound is an unreviewed proxy; soft-tissue crease/tip localization is not medically calibrated.')
 result['pubic_upper_margin']=reference('pubic_upper_margin',L['pubic-top'],refs['pubic-top'],'Pubic source-mesh upper-margin proxy; not professionally reviewed.')
 ulna=find(side+' ulna');v=vertices(ulna);tip=v[np.argmax(v[:,1])]
 result['olecranon_tip']=reference('olecranon_tip',tip,[ulna],'Proximal ulna superior extremum is used as an unreviewed olecranon-tip geometry proxy.')
 for pid in ('sp9','ki3','st35'):
  if pid in mapped and side in mapped[pid]:
   result[pid]=reference(pid,mapped[pid][side],[pid],'Retained teaching point is itself unreviewed; relative agreement does not calibrate the base.')
 return result

L={};refs={}
def landmark(k,p,source):L[k]=np.array(p,float);refs[k]=source
# Bone landmarks use extrema of actual meshes, not a generic mannequin.
for s,sign in [('left',1),('right',-1)]:
 for bone in ['humerus','radius','ulna','femur','tibia','fibula','patella','clavicle','scapula','hip bone','talus']:
  fid=find(f'{s} {bone}');lo,hi=bounds(fid)
  landmark(f'{s}-{bone}',center(fid),[fid])
 for key,bone,yend in [('elbow','humerus','min'),('wrist','radius','min'),('ankle-medial','tibia','min'),('ankle-lateral','fibula','min')]:
  fid=find(f'{s} {bone}');v=vertices(fid);y=v[:,1].min() if yend=='min' else v[:,1].max();q=v[abs(v[:,1]-y)<.012]
  landmark(f'{s}-{key}',q.mean(0),[fid])
 fid=find(f'{s} patella');lo,hi=bounds(fid);landmark(f'{s}-patella-base',[center(fid)[0],hi[1],center(fid)[2]],[fid]);landmark(f'{s}-patella-tip',[center(fid)[0],lo[1],center(fid)[2]],[fid])
 fid=find(f'{s} humerus');lo,hi=bounds(fid);landmark(f'{s}-shoulder',[sign*.168,hi[1]-.012,.085],[fid,find(f'{s} clavicle')])
 landmark(f'{s}-axilla',[sign*.165,1.300,.090],[fid,'FJ2810'])
 fid=find(f'{s} hip bone');v=vertices(fid);q=v[(abs(v[:,0])<.02)&(v[:,2]>.11)];landmark(f'{s}-pubic-top',[sign*.005,q[:,1].max(),q[:,2].max()],[fid])
 landmark(f'{s}-groin',[sign*.070,L[f'{s}-pubic-top'][1],.13],[fid])
# Navel is a skin feature: vertex-density/indentation was inspected in the mesh.
navelQ=SKIN[(abs(SKIN[:,0])<.004)&(SKIN[:,1]>1.025)&(SKIN[:,1]<1.045)&(SKIN[:,2]>.17)]
landmark('navel',navelQ[np.argmin(navelQ[:,2])],['FJ2810'])
landmark('pubic-top',(L['left-pubic-top']+L['right-pubic-top'])/2,['FJ3288','FJ3152'])
lo,hi=bounds('FJ3153');landmark('xiphoid-tip',[0,lo[1],hi[2]],['FJ3153'])
lo,hi=bounds('FJ3290');landmark('sternal-notch',[0,hi[1],hi[2]],['FJ3290'])
landmark('crown',SKIN[np.argmax(SKIN[:,1])],['FJ2810'])
# Eye and face positions use actual eye/nasal/jaw structures.
for s,sign in [('left',1),('right',-1)]:
 fid=find(f'{s} cornea');landmark(s+'-eye',center(fid),[fid]);landmark(s+'-brow',center(fid)+[0,.022,0],[fid,'FJ2810'])
landmark('nose-tip',SKIN[(SKIN[:,1]>1.54)&(SKIN[:,1]<1.60)][np.argmax(SKIN[(SKIN[:,1]>1.54)&(SKIN[:,1]<1.60)][:,2])],['FJ2810','FJ2557'])
landmark('chin',project(0,1.502),['FJ3289','FJ2810']);landmark('upper-lip',project(0,1.540),['FJ1252','FJ2810'])
ordinals=['first','second','third','fourth','fifth','sixth','seventh','eighth','ninth','tenth','eleventh','twelfth']
for group,top in [('thoracic',12),('lumbar',5),('cervical',7)]:
 for i in range(1,top+1):
  try:fid=find(f'{ordinals[i-1]} {group} vertebra')
  except StopIteration:continue
  v=vertices(fid);q=v[v[:,2]<np.quantile(v[:,2],.06)]
  landmark(f'{group}-{i}-spinous',q[np.argmin(q[:,1])],[fid])
# Proportional cun values are specific to this model and body segment.
TORSO_SCALES=torso_scales(L)
ABDCUN=TORSO_SCALES['lower_abdomen_vertical'];UPPERCUN=TORSO_SCALES['upper_abdomen_vertical'];CHESTCUN=TORSO_SCALES['transverse'];CHESTVERTCUN=TORSO_SCALES['chest_vertical']

HEADFACE={'st1':(.030,1.586),'st2':(.032,1.573),'st3':(.032,1.558),'st4':(.025,1.538),'st5':(.057,1.522),'st6':(.063,1.544),'st7':(.071,1.570),'li19':(.012,1.554),'li20':(.018,1.562),'si18':(.053,1.567),'si19':(.078,1.578),'te17':(.076,1.555),'te18':(.079,1.582),'te19':(.078,1.599),'te20':(.078,1.617),'te21':(.077,1.585),'te22':(.073,1.605),'te23':(.054,1.616),'gb1':(.049,1.594),'gb2':(.078,1.571),'gb3':(.072,1.592),'gb4':(.065,1.636),'gb5':(.069,1.646),'gb6':(.073,1.633),'gb7':(.078,1.619),'gb8':(.077,1.647),'gb9':(.077,1.650),'gb10':(.078,1.631),'gb11':(.079,1.612),'gb12':(.070,1.552),'gb14':(.030,1.639),'bl1':(.016,1.594),'bl2':(.020,1.616)}
HEADSCALP={'bl3':(.016,.5),'bl4':(.035,.5),'bl5':(.035,1),'bl6':(.035,2.5),'bl7':(.035,4),'bl8':(.035,5.5),'bl9':(.030,10),'gb13':(.065,.5),'gb15':(.050,.5),'gb16':(.050,1.5),'gb17':(.050,2.5),'gb18':(.050,4),'gb19':(.050,9.5),'st8':(.075,.5)}

def scalp(x,cun,side):
 theta=1.05*(1-cun/5) if cun<=5 else -(cun-5)/7*2.0
 t=[x*(1 if side=='left' else -1),1.607+.112*math.cos(theta),.069+.112*math.sin(theta)]
 return nearest(t)

def spine(group,num):
 k=f'{group}-{num}-spinous';return L[k],refs[k]

def rib_y(n,side='left',x=.09):
 # Front rib level sampled from the actual osseous model at the requested x.
 fid=find(f'{side} {ordinals[n-1]} rib');v=vertices(fid);s=1 if side=='left' else -1
 q=v[(abs(v[:,0]-s*x)<.014)&(v[:,2]>.10)]
 if len(q)<3:q=v[v[:,2]>np.quantile(v[:,2],.8)]
 return float(np.median(q[:,1]))-.010,[fid]

def chest_reference(side):
 if side not in CHEST_REFERENCES:
  def source_triangles(name):
   fid=find(name);v=vertices(fid)
   fs=np.array([[int(t.split('/')[0])-1 for t in l.split()[1:4]] for l in RAW[fid].splitlines() if l.startswith('f ')],dtype=np.int32)
   return fid,v[fs]
  CHEST_REFERENCES[side]=locate_chest(side,source_triangles,project)
 return CHEST_REFERENCES[side]

def side_chest_reference(side):
 # Read the retained GB22 source binding, including during a narrow GB23-only
 # update. This is explicitly a spacing repair, not a new axillary localization.
 data=json.loads((OUT/'acupoints.json').read_text())
 base=next(i['anchor'] for p in data['points'] if p['id']=='gb22' for i in p['instances'] if i['side']==side)
 key=(side,tuple(base['position']),base['sourceVertex'])
 if key not in SIDE_CHEST_REFERENCES:
  def source_mesh(name):
   fid=find(name);v=vertices(fid)
   fs=np.array([[int(t.split('/')[0])-1 for t in l.split()[1:4]] for l in RAW[fid].splitlines() if l.startswith('f ')],dtype=np.int32)
   return fid,v,fs
  placement,evidence=locate_side_chest(side,base,source_mesh)
  v=placement['position'];normal_at('FJ2810',v)
  indices=np.asarray(placement['sourceTriangle'])-1;weights=np.asarray(placement['barycentric'])
  normal=np.sum(NORMALS['FJ2810'][indices]*weights[:,None],axis=0)
  if np.dot(normal,placement['normal'])<0:normal=-normal
  normal/=max(np.linalg.norm(normal),1e-12)
  PROJECTIONS[tuple(v)]={k:placement[k] for k in ['sourceTriangle','barycentric']}
  PROJECTIONS[tuple(v)]['normal']=normal
  SIDE_CHEST_REFERENCES[key]=(v,evidence)
 return SIDE_CHEST_REFERENCES[key]

def finger(s,digit,segment='distal',foot=False):
 words=['big toe','second toe','third toe','fourth toe','little toe'] if foot else ['thumb','index finger','middle finger','ring finger','little finger']
 return find(f'{segment} phalanx of {s} {words[digit-1]}')

def limb(p,s,mapped=None):
 sign=1 if s=='left' else -1;loc=normalized_location(p['locationZh']);reg=p['region'];code=p['codes'][0] if p['codes'] else '';pid=p['id'];use=[]
 mapped=mapped or {}; model_refs=limb_references(s,mapped); measure=None
 isleg=any(w in reg for w in ['股','膝','小腿','踝']);isarm=not isleg
 if isleg:
  kne=L[s+'-patella-base'];ank=L[s+'-ankle-medial'];hip=L[s+'-groin']
  segment='medial_lower_leg_15' if '内' in reg else 'lateral_lower_leg_16'
  c,measure=limb_scale(segment,model_refs,allow_popliteal_proxy=True)
  thigh,thigh_measure=limb_scale('anterior_thigh_18',model_refs)
  if pid=='lr11':
   # The standard specifies directly inferior to ipsilateral ST30. An oblique
   # medial ray from a generic thigh centre can hit the opposite thigh first.
   # Keep ST30 X and the exact 18-cun longitudinal target, then choose the
   # anterior outer source-skin intersection; never exchange bilateral anchors.
   base=np.asarray(mapped.get('st30',{}).get(s,[]),dtype=float)
   if base.shape!=(3,) or not np.isfinite(base).all() or sign*base[0]<=0:
    raise ValueError('LR11 requires an existing finite ipsilateral ST30 anchor.')
   y=float(base[1]-2*thigh)
   v=project(float(base[0]),y,'front',require_intersection=True)
   if sign*v[0]<=0 or abs(v[0]-base[0])>1e-10 or abs(v[1]-y)>1e-10:
    raise ValueError('LR11 projection failed its same-side, fixed-X/exact-Y constraints.')
   evidence=dict(thigh_measure,targetY=y,referenceY=float(base[1]),
                 referencePointId='st30',referencePosition=base.tolist(),
                 requestedCun=2,direction='inferior',expectedOffsetMetres=-2*thigh,
                 targetX=float(base[0]),sameSideRequired=s,
                 sourceSkinProjection='maximum-anterior-Z source-triangle intersection at fixed ipsilateral ST30 X and exact target Y',
                 nearestVertexFallback=False,medicallyReviewed=False)
   evidence['proxyLimitations']=[*evidence['proxyLimitations'],
     'Retained ST30 and the anterior-thigh bone endpoints are unreviewed teaching references; same-side geometry and numerical agreement are not medical localization.']
   MEASUREMENT_EVIDENCE[(pid,s)]=evidence
   return v,[*refs['pubic-top'],*refs[s+'-patella-base'],'st30','FJ2810'],'same-side-st30-inferior-anterior-thigh-cun-skin-projection'
  pop_y=L[s+'-patella-tip'][1]
  if '股' in reg:y=kne[1]+thigh*7
  elif '膝' in reg:y=kne[1]-.025
  elif '踝' in reg:y=ank[1]
  else:y=ank[1]+c*7
  m=re.search(r'髌底(?:内侧端|外侧端)?上([\d.]+)寸',loc)
  if m:y=kne[1]+float(m[1])*thigh
  for pattern,base,mult in [(r'(?:腘横纹|膝中)上([\d.]+)寸',pop_y,thigh),(r'(?:腘横纹|髌韧带外侧凹陷)下([\d.]+)寸',pop_y,-c),(r'内踝尖上([\d.]+)寸',ank[1],c),(r'外踝尖上([\d.]+)寸',L[s+'-ankle-lateral'][1],c)]:
   m=re.search(pattern,loc)
   if m:y=base+float(m[1])*mult
  if p['meridianId']=='ST' and 36<=int(code[2:])<=39:
   dist={36:3,37:6,38:8,39:9}[int(code[2:])];y=kne[1]-.025-dist*c
  if pid=='gb34':y=L[s+'-fibula'][1]+.17
  if pid=='gb31':y=kne[1]+7*thigh
  if pid=='gb32':y=kne[1]+5*thigh
  if '胫骨内侧髁' in loc:y=kne[1]-.035
  if pid=='sp9':y=kne[1]-.04
  if pid=='lr8':y=kne[1]-.015
  if pid=='ki10':y=kne[1]-.02
  if pid=='bl36':y=.79
  if pid in ['bl37','bl38']:
   base=mapped['bl36'][s];posterior_unit=(base[1]-pop_y)/14
   y=base[1]-6*posterior_unit if pid=='bl37' else pop_y+posterior_unit
   measure={'segment':'posterior_thigh_14','denominatorCun':14,'unitMetres':float(posterior_unit),'referenceY':float(base[1] if pid=='bl37' else pop_y),'targetY':float(y),'requestedCun':6 if pid=='bl37' else 1,'referencePointId':'bl36' if pid=='bl37' else 'patella-apex-plane','medicallyReviewed':False,'proxyLimitations':['Retained BL36 is an unreviewed gluteal-fold proxy; the popliteal plane uses the source patellar apex.']}
  if pid=='lr10':y=hip[1]-3*thigh
  if pid=='lr9':y=kne[1]+4*thigh
  if pid=='st31':y=hip[1]-.035
  if pid=='gb33':y=kne[1]+.010
  if pid=='bl58':y=mapped['bl60'][s][1]+7*c
  if pid=='bl59':y=mapped['bl60'][s][1]+3*c
  if pid=='bl55':y=pop_y-2*c
  if pid=='bl56':y=pop_y-5*c
  if pid=='bl57':y=pop_y-8*c
  if pid in EXPLICIT_LIMB_IDS:
   exact_y,explicit=explicit_limb_target(pid,model_refs,allow_popliteal_proxy=True)
   if exact_y is None:raise ValueError(pid+': '+explicit['reason'])
   y=exact_y;measure=explicit
  measure=dict(measure,targetY=float(y))
  MEASUREMENT_EVIDENCE[(pid,s)]=measure
  t=(y-ank[1])/(kne[1]-ank[1]);cx=ank[0]*(1-t)+kne[0]*t;cz=.08
  if y>kne[1]:cx=kne[0]+(hip[0]-kne[0])*min((y-kne[1])/(hip[1]-kne[1]),1)
  direction=[0,0,1]
  if '后' in reg:direction=[0,0,-1]
  if '内' in reg:direction[0]=-sign*.8
  if '外' in reg:direction[0]=sign*.8
  if p['meridianId']=='GB':direction=[sign,0,0]
  if p['meridianId']=='SP':direction=[-sign,.0,.4]
  if p['meridianId']=='KI':direction=[-sign,0,-.45]
  if p['meridianId']=='BL':direction=[0,0,-1]
  if p['meridianId']=='LR':direction=[-sign,0,.7]
  if p['nameZh']=='百虫窝':direction=[-sign*.7,0,1]
  if pid=='st35':cx+=sign*.016
  if pid=='st40':cx+=sign*.016
  if pid=='bl39':cx+=sign*.020
  if pid=='ki8':direction=[-sign,0,.25]
  if pid=='gb35':direction=[sign,0,-.6]
  if pid=='gb36':direction=[sign,0,.6]
  if pid=='sp10':cx-=sign*.020
  use=[find(f'{s} tibia'),find(f'{s} femur'),find(f'{s} patella')]
  return (exact_sideproject([cx,y,cz],direction) if '寸' in loc else sideproject([cx,y,cz],direction)),use,'segment-bone-proportional-cun'
 # Arm centerline is derived from humerus/radius/ulna landmarks.
 el=L[s+'-elbow'];wr=L[s+'-wrist'];ax=L[s+'-axilla'];armcun=(ax[1]-el[1])/9;fc=(el[1]-wr[1])/12
 if '前臂' in reg:y=(wr[1]+el[1])/2
 elif '腕' in reg:y=wr[1]
 elif '肘' in reg:y=el[1]
 elif '臂' in reg:y=(ax[1]+el[1])/2
 else:y=ax[1]
 for pattern,base,mult in [(r'腕[^；]*?上([\d.]+)寸',wr[1],fc),(r'肘[^；]*?上([\d.]+)寸',el[1],armcun),(r'肘[^；]*?下([\d.]+)寸',el[1],-fc),(r'腋前纹头下([\d.]+)寸',ax[1],-armcun)]:
  m=re.search(pattern,loc)
  if m:y=base+float(m[1])*mult
 if pid=='si6':y=wr[1]+fc
 if '尺骨头' in loc and '前臂' in reg:y=wr[1]+fc
 if pid=='li12':y=el[1]+.014
 if pid=='te10':y=el[1]+armcun
 if pid=='te11':y=el[1]+2*armcun
 if pid=='te12':y=el[1]+5*armcun
 if pid=='li14':y=el[1]+7*armcun
 if pid=='ht2':y=el[1]+3*armcun
 if pid=='pc2':y=ax[1]-2*armcun
 if pid=='te13':y=el[1]+7*armcun
 if pid in EXPLICIT_LIMB_IDS:
  exact_y,measure=explicit_limb_target(pid,model_refs,allow_popliteal_proxy=True)
  if exact_y is None:raise ValueError(pid+': '+measure['reason'])
  y=exact_y
 if measure is not None:MEASUREMENT_EVIDENCE[(pid,s)]=measure
 if y>=el[1]:t=(y-el[1])/(ax[1]-el[1]);ct=el*(1-t)+ax*t
 else:t=(y-wr[1])/(el[1]-wr[1]);ct=wr*(1-t)+el*t
 ct[1]=y
 if pid=='te7':ct[0]-=sign*.013
 direction=[0,0,1]
 if '后' in reg:direction=[0,0,-1]
 if '内' in reg:direction[0]=-sign*.8
 if '外' in reg:direction[0]=sign*.8
 if p['meridianId']=='LU':direction=[sign*.65,0,1]
 if p['meridianId']=='LI':direction=[sign*.8,0,-.6]
 if p['meridianId']=='HT':direction=[-sign*.8,0,1]
 if p['meridianId']=='SI':direction=[-sign*.8,0,-1]
 if p['meridianId']=='TE':direction=[0,0,-1]
 return (exact_sideproject(ct,direction) if '寸' in loc else sideproject(ct,direction)),[find(f'{s} humerus'),find(f'{s} radius'),find(f'{s} ulna')],'segment-bone-proportional-cun'

BACK_LEVELS={11:('thoracic',1),12:('thoracic',2),13:('thoracic',3),14:('thoracic',4),15:('thoracic',5),16:('thoracic',6),17:('thoracic',7),18:('thoracic',9),19:('thoracic',10),20:('thoracic',11),21:('thoracic',12),22:('lumbar',1),23:('lumbar',2),24:('lumbar',3),25:('lumbar',4),26:('lumbar',5),41:('thoracic',2),42:('thoracic',3),43:('thoracic',4),44:('thoracic',5),45:('thoracic',6),46:('thoracic',7),47:('thoracic',9),48:('thoracic',10),49:('thoracic',11),50:('thoracic',12),51:('lumbar',1),52:('lumbar',2)}
GV_LEVELS={3:('lumbar',4),4:('lumbar',2),5:('lumbar',1),6:('thoracic',11),7:('thoracic',10),8:('thoracic',9),9:('thoracic',7),10:('thoracic',6),11:('thoracic',5),12:('thoracic',3),13:('thoracic',1),14:('cervical',7)}

def map_point(p,inst,mapped):
 s=inst['side'];sgn=-1 if s=='right' else 1;loc=normalized_location(p['locationZh']);reg=p['region'];pid=p['id'];name=p['nameZh'];ch=p['meridianId'];n=int(re.search(r'\d+',pid).group()) if re.search(r'\d+',pid) else 0
 def surface(x,y,f='front'):return project(sgn*x,y,f)
 def pick(q):return mapped[q][s] if s in mapped[q] else next(iter(mapped[q].values()))
 def result(v,rs,method):return np.asarray(v),rs,method
 # ST12 belongs to the neck in the standard text, but shares the upper-chest
 # geometry references with ST13/14. Handle it before region-based fallbacks.
 if pid in CHEST_POINT_IDS:
  positions,evidence=chest_reference(s)
  return result(positions[pid],evidence['sourceStructureIds']+['FJ2810'],
                'upper-chest-local-geometry-reference-provisional')
 if pid in SIDE_CHEST_POINT_IDS:
  position,evidence=side_chest_reference(s)
  return result(position,evidence['sourceStructureIds'],
                'side-chest-relative-surface-spacing-only-provisional')
 if pid in HEADFACE:
  x,y=HEADFACE[pid]
  if pid in ['te17','te18','te19','te20','gb10','gb11','gb12']:
   zz={'te17':.076,'te18':.050,'te19':.040,'te20':.070,'gb10':.026,'gb11':.016,'gb12':.031}[pid]
   return result(nearest([sgn*x,y,zz]),['FJ2810'],'auricular-posterior-regional-projection')
  zz={'st5':.12,'st6':.13,'st7':.10,'si18':.14,'si19':.075,'te21':.080,'te22':.105,'te23':.15,'gb1':.15,'gb2':.075,'gb3':.105,'gb4':.115,'gb5':.09,'gb6':.08,'gb7':.07,'gb8':.065,'gb9':.035,'li19':.20,'li20':.195}.get(pid,.165)
  return result(nearest([sgn*x,y,zz]),['FJ2810', 'FJ1289' if sgn==1 else 'FJ1340'],'regional-face-landmark-projection')
 if pid in HEADSCALP:
  x,c=HEADSCALP[pid];return result(scalp(x,c,s),['FJ2810','crown','left-eye'],'scalp-proportional-cun')
 if p['viewPreset']=='oral':
  if name=='龈交':
   fid='FJ1252';a,b=bounds(fid);return result(nearest([0,(a[1]+b[1])/2,b[2]],fid),[fid],'upper-gingiva-local-surface')
  fid='FJ2761';a,b=bounds(fid);ct=(a+b)/2
  if name=='聚泉':t=[0,b[1],ct[2]+.014]
  elif name=='海泉':t=[0,a[1]+.006,ct[2]+.018]
  else:t=[sgn*.010,a[1]+.011,ct[2]+.021]
  return result(nearest(t,fid),[fid],'oral-tongue-surface-frenulum-region-provisional')
 if p['viewPreset']=='nasal':
  fid='FJ3263' if sgn==1 else 'FJ3369';a,b=bounds(fid);return result(nearest([sgn*.012,(a[1]+b[1])/2,b[2]],fid),[fid,'FJ2557'],'intranasal-concha-surface-provisional')
 if name in ['会阴','长强']:
  if name=='会阴':t=[0,.831,.064];rs=['FJ1450','FJ2543','FJ2571','FJ3133']
  else:t=[0,.850,.008];rs=['FJ3393','FJ2571']
  return result(nearest(t),rs+['FJ2810'],'perineal-local-anatomical-interval-provisional')
 if ch=='CV':
  x=0;rs=['navel','pubic-top','xiphoid-tip','sternal-notch']
  if n==2:y=L['pubic-top'][1]
  elif 3<=n<=7:y=L['navel'][1]-{3:4,4:3,5:2,6:1.5,7:1}[n]*ABDCUN
  elif n==8:y=L['navel'][1]
  elif 9<=n<=15:y=L['navel'][1]+{9:1,10:2,11:3,12:4,13:5,14:6,15:7}[n]*UPPERCUN
  elif n==16:y=L['xiphoid-tip'][1]
  elif 17<=n<=20:y=rib_y(21-n)[0]
  elif n==21:y=L['sternal-notch'][1]-CHESTVERTCUN
  elif n==22:y=L['sternal-notch'][1]
  elif n==23:y=center('FJ2772')[1]+.014;rs=['FJ2772']
  else:y=1.518;rs=['FJ3289','FJ2810']
  return result(surface(0,y),rs,'midline-landmark-proportional-cun')
 if ch=='GV':
  if n in GV_LEVELS:
   t,rs=spine(*GV_LEVELS[n]);return result(project(0,t[1]-.003,'back'),rs,'vertebral-spinous-inferior-skin-projection')
  if n==2:return result(project(0,.894,'back'),['FJ3393'],'sacral-hiatus-region-provisional')
  if n==24 and '+' in p['codes'][0]:return result(surface(0,L['left-brow'][1]),['left-brow','right-brow'],'brow-midpoint-surface')
  if n>=25:
   yy={25:L['nose-tip'][1],26:1.551,27:1.540,28:1.549}[n];return result(surface(0,yy),['nose-tip','upper-lip'],'facial-midline-projection')
  if n in [15,16]:return result(project(0,1.525 if n==15 else 1.548,'back'),['FJ3176','FJ3177','FJ2810'],'occipital-nuchal-surface-provisional')
  c={17:10.5,18:8,19:6.5,20:5,21:3.5,22:2,23:1,24:.5}[n];return result(scalp(0,c,'left'),['crown','FJ2810'],'midline-scalp-proportional-cun')
 if ch=='BL' and n in BACK_LEVELS:
  t,rs=spine(*BACK_LEVELS[n]);x=(1.5 if n<41 else 3)*CHESTCUN;return result(surface(x,t[1]-.003,'back'),rs,'vertebral-level-lateral-cun-skin-projection')
 if ch=='BL' and 27<=n<=35:
  y={27:.977,28:.950,29:.923,30:.900,31:.973,32:.949,33:.927,34:.907,35:.850}[n];x=(1.5*CHESTCUN if n<=30 else .022 if n<=34 else .5*CHESTCUN);return result(surface(x,y,'back'),['FJ3393'],'sacral-region-landmark-projection-provisional')
 if ch=='BL' and n in [53,54]:return result(surface(3*CHESTCUN,.952 if n==53 else .915,'back'),['FJ3393',find(s+' hip bone')],'sacral-lateral-cun-projection')
 # Chest and abdominal coordinates follow local bone levels and region-specific cun.
 if reg in ['前胸部','上腹部','下腹部','腹股沟','侧胸部','侧腹部']:
  rs=['navel','pubic-top','sternal-notch','xiphoid-tip'];x=.11 if reg=='腹股沟' else 0;y=L['pubic-top'][1]+.010 if reg=='腹股沟' else L['navel'][1];face='front'
  m=re.search(r'(?:前正中线|脐中)旁开([\d.]+)寸',loc)
  if m:x=float(m[1])*CHESTCUN
  m=re.search(r'第([\d]+)肋间隙',loc)
  if m:y,rr=rib_y(int(m[1]),s if s!='midline' else 'left',max(x,.03));rs+=rr
  m=re.search(r'脐中([上下])([\d.]+)寸',loc)
  if m:y=L['navel'][1]+(1 if m[1]=='上' else -1)*float(m[2])*(UPPERCUN if m[1]=='上' else ABDCUN)
  if '耻骨联合上缘' in loc:y=L['pubic-top'][1]
  if '脐中旁开' in loc:y=L['navel'][1]
  if '锁骨下缘' in loc:y=1.388
  if '锁骨下窝' in loc:y=1.370 if pid=='lu2' else 1.344
  if name=='乳中':x=4*CHESTCUN;y=rib_y(4,s)[0]
  if pid=='ki27':y=1.385;x=2*CHESTCUN
  if ch=='KI' and 11<=n<=21:x=.5*CHESTCUN
  if ch=='KI' and 22<=n<=26:x=2*CHESTCUN
  if pid=='pc1':x=5*CHESTCUN;y=rib_y(4,s)[0]
  # Retained, unreviewed GB22/SP21 bases; GB23 spacing is handled above without
  # reinterpreting a medial X offset as the standard's anterior direction.
  if pid in ['gb22','sp21']:x=.16;y=rib_y(4 if pid!='sp21' else 6,s,.14)[0];face='side'
  if pid in ['gb25','lr13']:x=.137;y=1.160 if pid=='gb25' else 1.185;face='side'
  if pid=='gb26':x=.15;y=L['navel'][1];face='side'
  if pid=='gb27':x=.125;y=L['navel'][1]-3*ABDCUN
  if pid=='gb28':x=.132;y=L['pubic-top'][1]+.025
  if face=='side':v=nearest([sgn*x,y,.10])
  else:v=surface(x,y)
  return result(v,rs,'regional-cun-and-rib-landmark-surface-projection')
 if reg in ['颈前部','颈部','颈后部','项部']:
  x=.035;y=1.460;face='front';rs=['FJ2772','FJ3176','FJ3172','FJ2810']
  neck={'li17':(.043,1.448),'li18':(.040,1.480),'st9':(.027,1.480),'st10':(.027,1.452),'st11':(.025,1.409),'si16':(.045,1.475),'si17':(.047,1.503),'bl10':(.032,1.517),'te16':(.046,1.512),'gb20':(.037,1.540)}
  if pid in neck:x,y=neck[pid]
  if reg in ['项部','颈后部'] or pid in ['bl10','gb20']:face='back'
  if name=='安眠':x=.058;y=1.548;face='back'
  if name=='翳明':x=.069;y=1.550;face='back'
  if name=='新设':x=.043;y=1.490;face='back'
  if name=='颈百劳':x=CHESTCUN;y=1.473;face='back'
  if name=='血压点':x=2*CHESTCUN;y=1.445;face='back'
  return result(surface(x,y,face),rs,'cervical-surface-landmark-provisional')
 if reg in ['肩带部','肩部','背部','腰部','腰区','脊柱区','臀部']:
  rs=[find(s+' scapula') if s!='midline' else 'FJ3393','FJ2810'];x=.08;y=1.33;face='back'
  shoulder={'li15':(.172,1.385),'li16':(.143,1.424),'si9':(.168,1.295),'si10':(.152,1.370),'si11':(.109,1.326),'si12':(.104,1.392),'si13':(.070,1.389),'si14':(.075,1.388),'si15':(.052,1.422),'te14':(.171,1.374),'te15':(.100,1.410),'gb21':(.100,1.425)}
  if pid in shoulder:x,y=shoulder[pid];face='front' if pid in ['li15','li16'] else 'back'
  elif name=='肩前':x=.170;y=1.340;face='front'
  elif name=='夹脊':
   level=inst['level'];t,rs=spine('thoracic' if level[0]=='T' else 'lumbar',int(level[1:]));x=.5*CHESTCUN;y=t[1]-.003
  elif name in ['定喘','胃脘下俞','接脊','痞根','腰宜','腰眼','十七椎']:
   g,i,x={'定喘':('cervical',7,.5),'胃脘下俞':('thoracic',8,1.5),'接脊':('thoracic',12,0),'痞根':('lumbar',1,3.5),'腰宜':('lumbar',4,3),'腰眼':('lumbar',4,3.5),'十七椎':('lumbar',5,0)}[name];t,rs=spine(g,i);y=t[1]-.003;x*=CHESTCUN
  elif pid=='gb29':x=.135;y=.900;face='side'
  elif pid=='gb30':x=.120;y=.860;face='back'
  elif reg=='臀部':x=.070;y=.795
  elif reg in ['腰区','腰部']:y=1.055;x=.065
  if face=='side':v=sideproject([sgn*x,y,.07],[sgn,0,-.2])
  else:v=surface(x,y,face)
  return result(v,rs,'bone-or-vertebra-regional-surface-projection')
 if reg in ['手指','手背','手掌']:
  digit={'lu11':1,'li1':2,'ht9':5,'si1':5,'pc9':3,'te1':4}.get(pid)
  if name=='十宣':digit=inst['ordinal']
  if digit:
   fid=finger(s,digit);v=vertices(fid);q=v[v[:,1]<v[:,1].min()+.004];t=q.mean(0)
   if pid!='pc9' and name!='十宣':t[0]+=sgn*(.003 if pid in ['lu11','li1','ht9'] else -.003)
   return result(nearest(t),[fid,'FJ2810'],'digit-phalanx-tip-surface-projection')
  if name in ['中魁','大骨空','小骨空','四缝']:
   digit={'中魁':3,'大骨空':1,'小骨空':5}.get(name,inst['ordinal']+1);fid=finger(s,digit,'proximal');a,b=bounds(fid);t=center(fid);t[1]=a[1] if name!='大骨空' else b[1];t[2]+=.012 if name=='四缝' else -.012
   return result(nearest(t),[fid,'FJ2810'],'phalangeal-joint-surface-projection')
  if name=='八邪':
   d=inst['ordinal'];f1=finger(s,d,'proximal');f2=finger(s,d+1,'proximal');t=(center(f1)+center(f2))/2;t[1]=max(bounds(f1)[1][1],bounds(f2)[1][1])-.010;t[2]-=.014
   return result(nearest(t),[f1,f2,'FJ2810'],'interdigital-web-surface-projection')
  pair=(2,3)
  if pid=='lu10':pair=(1,1)
  if pid in ['si2','si3']:pair=(5,5)
  if pid in ['te2','te3','ht8']:pair=(4,5)
  if name=='腰痛点':pair=(2,3) if inst['ordinal']==1 else (4,5)
  if pid in ['li2','li3']:pair=(2,2)
  if pid=='li4':pair=(1,2)
  fids=[find(f'{s} {ordinals[d-1]} metacarpal bone') for d in pair];t=(center(fids[0])+center(fids[1]))/2
  if pid in ['li2','li3','te2','te3','si2','si3']:t[1]=min(bounds(fids[0])[0][1],bounds(fids[1])[0][1])+(-.005 if pid in ['li2','te2','si2'] else .009)
  if pid in ['si2','si3']:t[0]-=sgn*.006
  if name=='腰痛点':t[1]=(t[1]+L[s+'-wrist'][1])/2
  t[2]+=.020 if reg=='手掌' else -.020
  return result(nearest(t),fids+['FJ2810'],'metacarpal-surface-projection')
 if reg in ['足趾','足背','足内侧','足外侧','足底']:
  digit={'st45':2,'sp1':1,'lr1':1,'gb44':4,'bl67':5,'ex-le11':2}.get(pid)
  if name=='气端':digit=inst['ordinal']
  if digit:
   fid=finger(s,digit,foot=True);v=vertices(fid);t=v[np.argmax(v[:,2])].copy();t[1]+=.004
   if pid in ['st45','lr1','gb44','bl67','sp1']:t[0]+=sgn*(-.004 if pid=='sp1' else .004);t[2]-=.003
   if name=='独阴':t=center(fid);t[1]=bounds(fid)[0][1]-.004
   return result(nearest(t),[fid,'FJ2810'],'toe-phalanx-surface-projection')
  if name=='八风':
   d=inst['ordinal'];f1=finger(s,d,'proximal',True);f2=finger(s,d+1,'proximal',True);t=(center(f1)+center(f2))/2;t[1]+=.012
   return result(nearest(t),[f1,f2,'FJ2810'],'interdigital-toe-web-projection')
  metatarsal={'st42':2,'st43':2,'st44':2,'sp2':1,'sp3':1,'sp4':1,'gb41':4,'gb42':4,'gb43':4,'lr2':1,'lr3':1,'bl64':5,'bl65':5,'bl66':5,'ki1':2}.get(pid,2)
  fid=find(f'{s} {ordinals[metatarsal-1]} metatarsal bone');t=center(fid);a,b=bounds(fid)
  if pid in ['st44','lr2','gb43','bl66']:t[2]=b[2]-.002
  if pid in ['sp2','sp3','bl65']:t[2]=b[2]+(.003 if pid=='sp2' else -.009)
  if pid=='st42':t[2]=a[2]+.012
  if pid=='st43':t[2]=b[2]-.012
  if pid=='gb41':t[2]=a[2]+.008
  if pid=='gb42':t[2]=b[2]-.010
  if pid in ['sp4','bl64']:t[2]=a[2]+.006
  if pid in ['ki2','ki3','ki4','ki5','ki6']:
   t=L[s+'-ankle-medial'].copy();t[0]-=sgn*.010;t[1]+={'ki2':-.025,'ki3':0,'ki4':-.015,'ki5':-.025,'ki6':-.025}[pid];t[2]+={'ki2':.025,'ki3':-.014,'ki4':-.025,'ki5':-.020,'ki6':.001}[pid]
  elif pid in ['bl60','bl61','bl62','bl63']:
   t=L[s+'-ankle-lateral'].copy();t[0]+=sgn*.008;t[1]+={'bl60':0,'bl61':-.025,'bl62':-.018,'bl63':-.015}[pid];t[2]+={'bl60':-.012,'bl61':-.020,'bl62':.003,'bl63':.030}[pid]
  elif pid in ['sp5','gb40','lr4','st41']:
   t=L[s+'-ankle-medial'].copy();t[2]+=.020;t[0]+=sgn*({'sp5':-.018,'gb40':.025,'lr4':-.005,'st41':.010}[pid])
  elif name=='里内庭':t[2]=b[2];t[1]=a[1]-.012
  else:t[1]+=.012 if reg!='足底' else -.018
  if reg=='足底':t[1]=.006 if name!='独阴' else t[1]
  return result(nearest(t),[fid,'FJ3280' if s=='left' else 'FJ3385','FJ2810'],'tarsal-metatarsal-surface-projection')
 if name=='四神聪':
  t=L['crown'].copy();d=CHESTCUN
  offset=[d,0,0] if s=='left' else [-d,0,0] if s=='right' else [0,0,d] if inst['ordinal']==1 else [0,0,-d]
  t+=np.array(offset)
  return result(nearest(t),['crown','FJ2810'],'scalp-four-point-projection')
 if reg in ['头部','耳区','面颊部','面部']:
  extra={'当阳':(.030,1),'鱼腰':(.030,0),'太阳':(.064,-1),'耳尖':(.080,-2),'球后':(.046,-3),'上迎香':(.016,-4),'牵正':(.065,-5)}
  if name in extra:
   x,c=extra[name]
   if name=='当阳':v=scalp(x,c,s)
   else:y={'鱼腰':1.616,'太阳':1.613,'耳尖':1.619,'球后':1.585,'上迎香':1.573,'牵正':1.549}[name];v=surface(x,y)
   return result(v,['FJ2810','left-eye' if s=='left' else 'right-eye'],'craniofacial-landmark-projection-provisional')
  if pid=='gb20':return result(surface(.037,1.54,'back'),['FJ3176','FJ2810'],'occipital-surface-projection')
  return result(surface(.05,1.62),['FJ2810'],'cranial-regional-projection-needs-refinement')
 if name in ['内踝尖','外踝尖']:
  k=s+('-ankle-medial' if name=='内踝尖' else '-ankle-lateral');return result(nearest(L[k]+[sgn*(-.012 if name=='内踝尖' else .012),0,0]),refs[k],'malleolar-bone-extremum-projection')
 if name=='髋骨':
  ref=pick('st34').copy();ref[0]+=sgn*(-1 if inst['ordinal']==1 else 1)*CHESTCUN*1.5;return result(nearest(ref),['st34','FJ2810'],'paired-parapatellar-proportional-cun')
 if name=='鹤顶':return result(nearest(L[s+'-patella-base']+[0,.008,.015]),[find(s+' patella')],'patella-superior-midpoint-projection')
 if name=='百虫窝':return result(limb(p,s,mapped)[0],[find(s+' patella'),find(s+' femur')],'patella-medial-superior-proportional-cun')
 if name=='内膝眼':t=L[s+'-patella-tip']+[-sgn*.016,-.01,.012];return result(nearest(t),[find(s+' patella')],'medial-patellar-ligament-region-projection')
 if name=='胆囊':t=L[s+'-fibula'].copy();t[1]=bounds(find(s+' fibula'))[1][1]-.05;return result(sideproject(t,[sgn,0,.3]),[find(s+' fibula')],'fibular-head-distal-proportional-cun')
 if name=='阑尾':t=pick('st36').copy();t[1]-=.052;return result(nearest(t),['st36',find(s+' tibia')],'tibial-anterior-distal-proportional-cun')
 if name=='肘尖':t=L[s+'-elbow'].copy();return result(sideproject(t,[0,0,-1]),[find(s+' ulna')],'olecranon-region-projection')
 if name=='二白':
  t=L[s+'-wrist']*(2/3)+L[s+'-elbow']*(1/3);t[0]+=sgn*(.007 if inst['ordinal']==1 else -.007);return result(exact_sideproject(t,[0,0,1]),[find(s+' radius'),find(s+' ulna')],'paired-forearm-proportional-cun')
 if name=='中泉':t=L[s+'-wrist'].copy();return result(sideproject(t,[0,0,-1]),refs[s+'-wrist'],'dorsal-wrist-surface-projection')
 return result(*limb(p,s,mapped))

def anatomy_structure_map():
 terms={'胸锁乳突肌':'sternocleidomastoid','胸大肌':'pectoralis major','胸小肌':'pectoralis minor','斜方肌':'trapezius','三角肌':'deltoid','冈上肌':'supraspinatus','冈下肌':'infraspinatus','肱二头肌':'biceps brachii','肱三头肌':'triceps brachii','肱桡肌':'brachioradialis','桡侧腕屈肌':'flexor carpi radialis','尺侧腕屈肌':'flexor carpi ulnaris','掌长肌':'palmaris longus','指总伸肌':'extensor digitorum','拇长伸肌':'extensor pollicis longus','拇短伸肌':'extensor pollicis brevis','拇长展肌':'abductor pollicis longus','股直肌':'rectus femoris','股外侧肌':'vastus lateralis','股内侧肌':'vastus medialis','缝匠肌':'sartorius','阔筋膜张肌':'tensor fasciae latae','股二头肌':'biceps femoris','半腱肌':'semitendinosus','半膜肌':'semimembranosus','长收肌':'adductor longus','大收肌':'adductor magnus','胫骨前肌':'tibialis anterior','胫骨后肌':'tibialis posterior','腓肠肌':'gastrocnemius','比目鱼肌':'soleus','腓骨长肌':'fibularis longus','腓骨短肌':'fibularis brevis','趾长伸肌':'extensor digitorum longus','跟腱':'calcaneal tendon','髌韧带':'patellar ligament','锁骨':'clavicle','胸骨':'sternum','肩胛骨':'scapula','尺骨':'ulna','桡骨':'radius','肱骨':'humerus','股骨':'femur','髌骨':'patella','胫骨':'tibia','腓骨':'fibula','骶骨':'sacrum','尾骨':'coccyx'}
 names=list(csv.DictReader(open(AS/'partof_parts_list_e.txt'),delimiter='\t'));elements=collections.defaultdict(list)
 for row in csv.DictReader(open(AS/'partof_element_parts.txt'),delimiter='\t'):elements[row['concept id']].append(row['element file id'])
 result={}
 for zh,en in terms.items():
  ids=set()
  for row in names:
   if row['en'] in [en,'left '+en,'right '+en]:ids.update(i for i in elements[row['concept id']] if i in parts)
  result[zh]=sorted(ids)
 return result

def main(point_ids=None):
 anatomy_map=anatomy_structure_map()
 data=json.loads((OUT/'acupoints.json').read_text());mapped={p['id']:{i['side']:np.asarray(i['anchor']['position']) for i in p['instances'] if i['anchor'].get('position') is not None} for p in data['points']};audit=[]
 # Shared references must be mapped before regional extra points use them.
 for p in data['points']:
  if point_ids is not None and p['id'] not in point_ids:continue
  mapped[p['id']]={};methods=[]
  p['anatomyStructureIds']=sorted({i for term in p.get('anatomyTermsZh',[]) for i in anatomy_map.get(term,[])})
  for inst in p['instances']:
   v,rs,method=map_point(p,inst,mapped)
   if not np.isfinite(v).all():raise ValueError(p['id'])
   inst['anchor']={'modelId':MODEL,'position':[round(float(c),7) for c in v],'landmarkRefs':rs,'method':method,'status':'provisional','sourceSurface': 'FJ2761' if p['viewPreset']=='oral' and p['nameZh']!='龈交' else 'FJ1252' if p['nameZh']=='龈交' else ('FJ3263' if inst['side']=='left' else 'FJ3369') if p['viewPreset']=='nasal' else 'FJ2810','reviewNotesZh':'真实模型几何与标准定位摘要形成的教学位置，尚未专业医学审校。'}
   if (p['id'],inst['side']) in MEASUREMENT_EVIDENCE:
    inst['anchor']['measurementEvidence']=MEASUREMENT_EVIDENCE[(p['id'],inst['side'])]
   if p['id'] in CHEST_POINT_IDS:
    inst['anchor']['placementEvidence']=chest_reference(inst['side'])[1]
   if p['id'] in SIDE_CHEST_POINT_IDS:
    inst['anchor']['placementEvidence']=side_chest_reference(inst['side'])[1]
    inst['anchor']['reviewNotesZh']=inst['anchor']['placementEvidence']['limitationZh']
   fid=inst['anchor']['sourceSurface'];normal,vi,distance=normal_at(fid,v)
   projection=PROJECTIONS.get(tuple(v))
   if projection:
    normal=projection['normal'];distance=0.;inst['anchor'].update({k:projection[k] for k in ['sourceTriangle','barycentric']})
   else:
    if fid=='FJ2810':
     if p['viewPreset']=='perineum':radial=np.array([0,-1,0])
     elif p['viewPreset']=='sole':radial=np.array([0,-1,0])
     elif v[1]>1.49:radial=v-np.array([0,1.61,.07])
     elif any(term in p['region'] for term in ['臂','肘','腕','手']):
      side=inst['side'];wr=L[side+'-wrist'];el=L[side+'-elbow'];ax=L[side+'-axilla']
      bone_refs=[r for r in rs if r in parts and parts[r]['system']=='bone']
      if p['region'] in ['手指','手背','手掌'] and bone_refs:ct=np.mean([center(r) for r in bone_refs],axis=0)
      elif v[1]>=el[1]:
       t=np.clip((v[1]-el[1])/(ax[1]-el[1]),0,1);ct=el*(1-t)+ax*t
      else:
       t=np.clip((v[1]-wr[1])/(el[1]-wr[1]),0,1);ct=wr*(1-t)+el*t
      radial=v-ct
     else:radial=np.array([v[0]-(.075 if v[0]>0 else -.075) if v[1]<.85 else v[0],0,v[2]-.085])
     if np.dot(normal,radial)<0:normal=-normal
    elif fid=='FJ2761':
     expected=1 if p['nameZh']=='聚泉' else -1
     if normal[1]*expected<0:normal=-normal
    elif fid=='FJ1252' and normal[2]<0:normal=-normal
   inst['anchor'].update({'normal':[round(float(c),7) for c in normal],'viewId':p['viewPreset'],'normalStatus':'geometry-derived-provisional','normalOrientationMethod':'OBJ normals transformed; outer shell orientation resolved by ray direction or regional anatomy','sourceVertex':vi,'sourceMeshDistanceMetres':round(distance,10)})
   mapped[p['id']][inst['side']]=v;methods.append(method)
  p['coordinateReviewStatus']='provisional'
 # Derive the audit from the final data so a local repair does not drop the
 # unmodified entries or regenerate their coordinates.
 for p in data['points']:
  audit.append({'id':p['id'],'nameZh':p['nameZh'],'instances':len(p['instances']),
                'methods':sorted({i['anchor']['method'] for i in p['instances']}),
                'reviewStatus':p.get('coordinateReviewStatus','provisional')})
 data['counts']['mappedInstances']=sum(len(p['instances']) for p in data['points']);data['counts']['medicallyReviewedInstances']=0
 data['coordinateStatusZh']='全部实例已有真实模型上的初步教学锚点；定位待专业医学审校，不能作为操作指导。'
 (OUT/'acupoints.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
 landmarks={'schemaVersion':1,'modelId':MODEL,'coordinateSystem':meta['coordinateSystem'],'landmarks':[{ 'id':k,'position':list(map(float,v)),'sourceStructureIds':refs[k],'reviewStatus':'geometry-derived-provisional'} for k,v in L.items()]}
 if point_ids is None:
  (OUT/'landmarks.json').write_text(json.dumps(landmarks,ensure_ascii=False,indent=2)+'\n')
 (ROOT/'manifests/content-anchor-audit.json').write_text(json.dumps({'modelId':MODEL,'entryCount':413,'instanceCount':sum(len(p['instances']) for p in data['points']),'medicalReviewCompleted':False,'medicalReviewedInstances':0,'mappedInstances':843,'entries':audit},ensure_ascii=False,indent=2)+'\n')
 print('mapped',data['counts']['mappedInstances'],'instances; medical-reviewed 0; methods',dict(collections.Counter(a['method'] for p in data['points'] for a in [i['anchor'] for i in p['instances']])))
if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--point-ids',nargs='+',
                     help='Update only known selected entries; preserve every other anchor and landmarks.json.')
 args=parser.parse_args()
 if args.point_ids:
  known={p['id'] for p in json.loads((OUT/'acupoints.json').read_text())['points']}
  unknown=set(args.point_ids)-known
  if unknown:parser.error('Unknown point IDs: '+', '.join(sorted(unknown)))
 main(set(args.point_ids) if args.point_ids else None)
