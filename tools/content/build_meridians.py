#!/usr/bin/env python3
from pathlib import Path
import json,csv,math
import numpy as np
from build_content import CHANNELS
from meridian_definitions import PRIMARY,EXTRA,ASSOCIATIONS,COLORS
ROOT=Path(__file__).resolve().parents[2]; OUT=ROOT/'app/src/main/assets/content'
pts=json.loads((OUT/'acupoints.json').read_text())['points'];index={p['id']:p for p in pts}
metadata=[]
for code,name,py,count in CHANNELS[:12]:
 desc,segments=PRIMARY[code]
 metadata.append({'id':code,'nameZh':name,'pinyin':py,'routeType':'primary','color':COLORS[code],'descriptionZh':desc,'pointIds':[p['id'] for p in pts if p['meridianId']==code],'associatedPointIds':[],'topologySegments':segments})
for code,(name,py,desc,segments) in EXTRA.items():
 metadata.append({'id':code,'nameZh':name,'pinyin':py,'routeType':'extraordinary','color':COLORS[code],'descriptionZh':desc,'pointIds':[p['id'] for p in pts if p['meridianId']==code],'associatedPointIds':ASSOCIATIONS.get(code,[]),'topologySegments':segments})
for route in metadata:
 route.update({'sourceRefs':[{'sourceId':'bucm-meridian-overview','section':'经络系统组成与奇经八脉'}],'routeReviewStatus':'editorial-schematic-pending-medical-review','routeScopeZh':'用于结构学习的体表循行示意；深部走行与分支单独标记，未经完整医学审校。','paths':[]})
 assert all(pid in index for s in route['topologySegments'] for pid in s),route['id']
for r in metadata:
 # Every segment has an explicit topology and role; source labels do not certify coordinates.
 sides=['midline'] if r['id'] in ['GV','CV'] else ['left','right']
 for side in sides:
  for seg_no,segment in enumerate(r['topologySegments']):
   samples=[]
   for pid in segment:
    p=index[pid];inst=next((i for i in p['instances'] if i['side']==side),None) or next((i for i in p['instances'] if i['side']=='midline'),None)
    if inst and inst['anchor']['position'] is not None:samples.append(inst['anchor']['position'])
   if len(samples)>=2:r['paths'].append({'id':f"{r['id']}-{side}-{seg_no+1}",'side':side,'internal':False,'points':samples,'reviewStatus':'provisional','role':'surface-course-schematic','pointIds':segment})
# Belt meridian: a genuine circumference schematic, rather than a chord through three front points.
skin=np.load(ROOT/'tools/content/sources/skin_vertices.npy')
navel=next(l['position'] for l in json.loads((OUT/'landmarks.json').read_text())['landmarks'] if l['id']=='navel')
ring=[]
for angle in np.linspace(0,2*math.pi,49):
 y=navel[1]-.045*max(math.cos(angle),0)
 q=skin[(abs(skin[:,1]-y)<.012)&(abs(skin[:,0])<.19)]
 target=np.array([.15*math.sin(angle),y,.095+.11*math.cos(angle)])
 ring.append([round(float(v),7) for v in q[np.argmin(np.sum((q-target)**2,axis=1))]])
next(r for r in metadata if r['id']=='DAI')['paths']=[{'id':'DAI-circumference','side':'midline','internal':False,'points':ring,'reviewStatus':'provisional','role':'circumferential-course-schematic','pointIds':['gb26','gb27','gb28']}]
# Deep connections are explicitly an organ relation diagram, not a discovered anatomical tube.
anatomy=json.loads((ROOT/'app/src/main/assets/anatomy.json').read_text());structures={x['id']:x for x in anatomy['structures']}
organ_members={}
for row in csv.DictReader(open(ROOT/'tools/assets/source/partof_element_parts.txt'),delimiter='\t'):
 organ_members.setdefault(row['concept id'],[]).append(row['element file id'])
def organ_center(concept=None,fid=None):
 ids=[fid] if fid else [v for v in organ_members.get(concept,[]) if v in structures]
 if not ids:return None
 lo=np.min([structures[v]['bounds']['min'] for v in ids],axis=0);hi=np.max([structures[v]['bounds']['max'] for v in ids],axis=0)
 return {'position':list(map(float,(lo+hi)/2)),'structureIds':ids,'method':'aggregate-real-structure-bounds'}
organs={'heart':organ_center('FMA7088'),'left-lung':organ_center('FMA7310'),'right-lung':organ_center('FMA7309'),'liver':organ_center('FMA7197'),'small-intestine':organ_center('FMA7200'),'large-intestine':organ_center('FMA7201'),'gallbladder':organ_center('FMA7202'),'stomach':organ_center(fid='FJ2564'),'spleen':organ_center(fid='FJ2561'),'left-kidney':organ_center(fid='FJ3145'),'right-kidney':organ_center(fid='FJ3147'),'bladder':organ_center(fid='FJ3149'),'brain':organ_center('FMA50801')}
relations={'LU':['stomach','large-intestine','stomach','lung'],'LI':['lung','large-intestine'],'ST':['stomach','spleen'],'SP':['stomach','spleen','heart'],'HT':['heart','small-intestine'],'SI':['heart','stomach','small-intestine'],'BL':['brain','kidney','bladder'],'KI':['kidney','bladder','liver','lung','heart'],'PC':['heart'],'TE':['heart','bladder'],'GB':['gallbladder','liver'],'LR':['liver','stomach','lung']}
for route in metadata:
 if route['id'] not in relations:continue
 for side in ['left','right']:
  nodes=[];ids=[]
  for name in relations[route['id']]:
   name=(side+'-'+name) if name in ['lung','kidney'] else name
   if organs.get(name):nodes.append(organs[name]['position']);ids+=organs[name]['structureIds']
  if len(nodes)>=2:route['paths'].append({'id':route['id']+'-'+side+'-deep-relations','side':side,'internal':True,'points':nodes,'reviewStatus':'provisional','role':'organ-relation-schematic','sourceStructureIds':list(dict.fromkeys(ids)),'descriptionZh':'传统经络属络关系的空间示意；折线连接真实器官区域中心，不代表实体管道或完整深部循行。'})
(OUT/'organ-landmarks.json').write_text(json.dumps({'schemaVersion':1,'modelId':'bodyparts3d-male-v4','organs':organs,'reviewStatus':'geometry-derived-provisional'},ensure_ascii=False,indent=2)+'\n')
assert len(metadata)==20
(OUT/'meridians.json').write_text(json.dumps({'schemaVersion':1,'modelId':'bodyparts3d-male-v4','counts':{'primary':12,'extraordinary':8,'total':20},'meridians':metadata},ensure_ascii=False,indent=2)+'\n')
sources_path=OUT/'sources.json'; sources=json.loads(sources_path.read_text());sources['sources']=[x for x in sources['sources'] if x['id']!='bucm-meridian-overview'];sources['sources'].append({'id':'bucm-meridian-overview','title':'经络总论：经络系统组成','publisher':'北京中医药大学','url':'https://jxjyxb.bucm.edu.cn/BZYAttachs/courseware/zhenjiuxue/a1/a1_4.htm','usageZh':'经络分类、十四经与奇经八脉关系；路线几何为本项目教学示意，非该资料提供的三维坐标。','retrievedDate':'2026-10-20','licenseStatus':'no-open-license-confirmed'})
sources_path.write_text(json.dumps(sources,ensure_ascii=False,indent=2)+'\n')
(ROOT/'manifests/content-sources.json').write_text(json.dumps(sources,ensure_ascii=False,indent=2)+'\n')
print('20 routes; paths',sum(len(r['paths']) for r in metadata))
