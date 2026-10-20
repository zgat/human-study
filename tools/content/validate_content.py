#!/usr/bin/env python3
"""Completeness, geometry, provenance and honest-status checks for packaged content."""
from pathlib import Path
import json,math,collections,hashlib,zipfile
import numpy as np
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'app/src/main/assets/content'
def read(name):return json.loads((OUT/name).read_text())
a=read('acupoints.json');points=a['points'];index={p['id']:p for p in points};sources={s['id'] for s in read('sources.json')['sources']}
assert len(points)==len(index)==413
assert collections.Counter(p['kind'] for p in points)=={'meridian':362,'extra':51}
assert index['gv24-plus']['codes']==['GV24+']
assert index['ex-hn12-hn13']['codes']==['EX-HN12','EX-HN13']
assert len(index['ex-hn12-hn13']['instances'])==2
assert len(index['ex-b2']['instances'])==34
assert len(index['ex-hn1']['instances'])==4
expected={'LU':11,'LI':20,'ST':45,'SP':21,'HT':9,'SI':19,'BL':67,'KI':27,'PC':9,'TE':23,'GB':44,'LR':14,'GV':29,'CV':24}
for k,n in expected.items():assert sum(p['meridianId']==k for p in points)==n
bounds=json.loads((ROOT/'manifests/model-landmarks.json').read_text())['bodyBounds'];allids=[];positions=collections.defaultdict(list)
for p in points:
 assert p['nameZh'] and p['pinyin'] and len(p['locationZh'])>=6,p['id']
 assert not any(x in p['locationZh'] for x in ['[[PAGE','GB/T','连7线','主治','针刺','点刺','艾灸']),p['id']
 assert all(s['sourceId'] in sources for s in p['sourceRefs']),p['id']
 assert p['coordinateReviewStatus']=='provisional'
 for i in p['instances']:
  allids.append(i['id']);q=i['anchor']['position'];assert len(q)==3 and all(math.isfinite(v) for v in q),i['id']
  assert all(bounds['min'][k]-.0001<=q[k]<=bounds['max'][k]+.0001 for k in range(3)),i['id']
  assert i['anchor']['status']=='provisional' and i['anchor']['landmarkRefs'],i['id']
  if i['side']=='left':assert q[0]>=-.005,i['id']
  if i['side']=='right':assert q[0]<=.005,i['id']
  positions[tuple(q)].append(i['id'])
assert len(allids)==len(set(allids))==843
# Independently reconstruct every stored anchor from the source OBJ vertex or
# triangle barycentric coordinates. Rounded JSON positions tolerate < 0.1 micrometre.
mesh_cache={};archive=None;zmap={};max_distance=0.;normal_error=0.;pairs=[]
def source_mesh(fid):
 global archive,zmap
 if fid in mesh_cache:return mesh_cache[fid]
 path=ROOT/'tools/assets/source'/(fid+'.obj')
 if path.exists():raw=path.read_text()
 else:
  if archive is None:
   archive=zipfile.ZipFile(ROOT/'tools/assets/source/isa_BP3D_4.0_obj_99.zip');zmap={Path(n).stem:n for n in archive.namelist() if n.endswith('.obj')}
  raw=archive.read(zmap[fid]).decode()
 v=np.array([list(map(float,l.split()[1:4])) for l in raw.splitlines() if l.startswith('v ')])
 v=np.c_[v[:,0]/1000,(v[:,2]+78.1112)/1000,-v[:,1]/1000];mesh_cache[fid]=v;return v
for p in points:
 for inst in p['instances']:
  a=inst['anchor'];v=source_mesh(a['sourceSurface']);q=np.array(a['position']);n=np.array(a['normal'])
  normal_error=max(normal_error,abs(float(np.linalg.norm(n))-1));assert abs(np.linalg.norm(n)-1)<1e-5
  assert a['viewId']==p['viewPreset']
  if 'sourceTriangle' in a:
   ids=np.array(a['sourceTriangle'])-1;w=np.array(a['barycentric']);reconstructed=(v[ids]*w[:,None]).sum(0)
   assert abs(w.sum()-1)<1e-8 and min(w)>=-1e-6
  else:reconstructed=v[a['sourceVertex']-1]
  delta=float(np.linalg.norm(q-reconstructed));max_distance=max(max_distance,delta);assert delta<1e-6,inst['id']
  if inst['side']=='left':
   other=next((x for x in p['instances'] if x['side']=='right' and x['ordinal']==inst['ordinal']),None)
   if other:
    rpos=np.array(other['anchor']['position']);rpos[0]*=-1;pairs.append({'id':p['id'],'ordinal':inst['ordinal'],'mirrorErrorMetres':float(np.linalg.norm(q-rpos))})
max_mirror=max(x['mirrorErrorMetres'] for x in pairs);assert max_mirror<.025,('unexpected-left-right-asymmetry',max_mirror)
r=read('meridians.json')['meridians'];assert len(r)==20
assert len({route['color'] for route in r})==20
from update_functions import validate_notes
validate_notes(read_note := json.loads((ROOT/'tools/content/function_notes.json').read_text())['notes'], points)
assert all(p.get('traditionalIndicationsZh') and p.get('functionSourceRefs') for p in points)
assert all(sid in sources for p in points for sid in p.get('functionSourceIds',[]))
assert collections.Counter(x['routeType'] for x in r)=={'primary':12,'extraordinary':8}
for route in r:
 assert route['paths'] and all(pid in index for pid in route['pointIds']+route['associatedPointIds'])
 for path in route['paths']:
  assert type(path['internal']) is bool and len(path['points'])>=2
  assert path['reviewStatus']=='provisional'
  assert all(len(q)==3 and all(math.isfinite(v) for v in q) for q in path['points'])
for p in points:
 for i in p['instances']:
  if p['viewPreset']=='oral':assert i['anchor']['sourceSurface'] in ['FJ1252','FJ2761']
  if p['viewPreset']=='nasal':assert i['anchor']['sourceSurface'] in ['FJ3263','FJ3369']
report={'schemaVersion':1,'checksPassed':True,'entries':413,'meridianEntries':362,'extraEntries':51,'instances':843,'routes':20,'paths':sum(len(x['paths']) for x in r),'sourceIds':sorted(sources),'medicalReviewedInstances':0,'geometry':{'sourceSurfaceReconstructionMaxDistanceMetres':max_distance,'normalUnitLengthMaxError':normal_error,'pairedInstances':len(pairs),'maxMirrorDifferenceMetres':max_mirror,'mirrorDifferenceThresholdMetres':.025,'normalAndViewIdPresent':True},'coincidentCoordinateGroups':[{'position':list(k),'instanceIds':v,'reviewStatus':'pending-coincidence-review'} for k,v in positions.items() if len(v)>1],'boundariesZh':['检查证明目录、引用、实例数量与几何范围一致，不代表专业医学审校。','部分独立条目在当前分辨率和初步定位下共点，清单保留供后续精细校对。','经外奇穴公开副本仍需与正式出版社版逐条比对。']}
report['functions']={'describedEntries':413,'traditionalIndicationSummaries':408,'contextOnlyEntries':4,'referenceOnlyEntries':1,'professionalReviewedEntries':0}
report['routeColors']={'count':20,'unique':True}
(ROOT/'manifests/content-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
files=[]
for p in sorted(OUT.glob('*.json')):files.append({'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(ROOT/'manifests/content-artifacts.json').write_text(json.dumps({'schemaVersion':1,'files':files},ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['coincidentCoordinateGroups']},ensure_ascii=False))
print('coincident coordinate groups:',len(report['coincidentCoordinateGroups']))
