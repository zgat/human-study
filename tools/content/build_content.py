#!/usr/bin/env python3
"""Reproduce the offline, non-clinical acupoint catalogue from pinned public copies.
Run with a Python environment containing pypdf. No network is used by this step.
"""
from pathlib import Path
import re,json,hashlib,unicodedata,collections
ROOT=Path(__file__).resolve().parents[2]
SRC=Path(__file__).resolve().parent/'sources'
OUT=ROOT/'app/src/main/assets/content'
OUT.mkdir(parents=True,exist_ok=True)
LOCATION_CORRECTIONS=json.loads((Path(__file__).with_name('standard_location_corrections.json')).read_text())['corrections']
CHANNELS=[('LU','手太阴肺经','Shǒu Tàiyīn Fèijīng',11),('LI','手阳明大肠经','Shǒu Yángmíng Dàchángjīng',20),('ST','足阳明胃经','Zú Yángmíng Wèijīng',45),('SP','足太阴脾经','Zú Tàiyīn Píjīng',21),('HT','手少阴心经','Shǒu Shàoyīn Xīnjīng',9),('SI','手太阳小肠经','Shǒu Tàiyáng Xiǎochángjīng',19),('BL','足太阳膀胱经','Zú Tàiyáng Pángguāngjīng',67),('KI','足少阴肾经','Zú Shàoyīn Shènjīng',27),('PC','手厥阴心包经','Shǒu Juéyīn Xīnbāojīng',9),('TE','手少阳三焦经','Shǒu Shàoyáng Sānjiāojīng',23),('GB','足少阳胆经','Zú Shàoyáng Dǎnjīng',44),('LR','足厥阴肝经','Zú Juéyīn Gānjīng',14),('GV','督脉','Dūmài',29),('CV','任脉','Rènmài',24)]
ALIASES={'上迎香':['鼻通','鼻穿'],'内迎香':['迎香'],'金津玉液':['金津','玉液','廉泉'],'新设':['新识'],'颈百劳':['百劳'],'提托':['归髎'],'定喘':['喘息'],'夹脊':['华佗夹脊','佗脊'],'胃脘下俞':['胃管下俞','胃下俞'],'腰眼':['腰目'],'十七椎':['十七椎下'],'肩前':['前腋','腋缝','前腋缝','肩内陵'],'十宣':['鬼城'],'鹤顶':['膝顶'],'百虫窝':['血郄'],'内膝眼':['膝目','虎眼'],'八风':['八冲'],'独阴':['独会']}
ANATOMY_TERMS=['胸锁乳突肌','胸大肌','胸小肌','斜方肌','三角肌','冈上肌','冈下肌','肱二头肌','肱三头肌','肱桡肌','桡侧腕屈肌','尺侧腕屈肌','掌长肌','指总伸肌','拇长伸肌','拇短伸肌','拇长展肌','股直肌','股外侧肌','股内侧肌','缝匠肌','阔筋膜张肌','股二头肌','半腱肌','半膜肌','长收肌','大收肌','胫骨前肌','胫骨后肌','腓肠肌','比目鱼肌','腓骨长肌','腓骨短肌','趾长伸肌','拇长伸肌腱','跟腱','髌韧带','锁骨','胸骨','肩胛骨','尺骨','桡骨','肱骨','股骨','髌骨','胫骨','腓骨','骶骨','尾骨','舌下系带']
REGIONS={1:'头颈部',2:'胸腹部',3:'背部',4:'肩胛部',5:'上肢',6:'下肢'}
SOURCE_CONFIG=[
 {'id':'gb12346-2021','standard':'GB/T 12346—2021','title':'经穴名称与定位','file':'gb12346-university.pdf','url':'https://zynj.shutcm.edu.cn/_upload/article/files/66/b4/b34a95604d04b0bf686251b2d317/368ea8b0-187c-48a7-a402-ffdf1e11bb99.pdf','canonicalUrl':'https://www.ntcamsac.ac.cn/upload/std_info/202306192125376309.pdf','registryUrl':'https://std.samr.gov.cn/gb/search/gbDetailed?id=D1E86BE73ADD430EE05397BE0A0A206B','copyStatus':'public-university-copy','finalTextReview':'pending-publisher-copy-comparison'},
 {'id':'gb40997-2021','standard':'GB/T 40997—2021','title':'经外奇穴名称与定位','file':'gb40997-university.pdf','url':'https://zynj.shutcm.edu.cn/_upload/article/files/66/b4/b34a95604d04b0bf686251b2d317/72823dbe-8f55-4e2b-b98b-ff106160f598.pdf','canonicalUrl':'https://www.ntcamsac.ac.cn/cms/content?id=104','registryUrl':'https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=D2AEF8AD07C0150E19859079579EF99F','copyStatus':'public-university-copy-with-unresolved-reference-placeholders','finalTextReview':'pending-formal-edition-review'}]

def text_of(cfg):
 p=SRC/cfg['file']; cached=p.with_suffix('.txt')
 if True:  # Regenerate extraction from the pinned PDF on every build.
  from pypdf import PdfReader
  cached.write_text('\n'.join(f'\n[[PAGE {i+1}]]\n'+pg.extract_text() for i,pg in enumerate(PdfReader(p).pages)),encoding='utf-8')
 txt=cached.read_text(encoding='utf-8')
 # Page furniture must never leak into user-facing descriptions.
 txt=re.sub(r'\[\[PAGE \d+\]\]', '', txt)
 txt=re.sub(r'^GB/T\s*\d+[^\n]*\n', '',txt,flags=re.M)
 txt=re.sub(r'^\d+\s*$', '',txt,flags=re.M)
 return txt

def plain(s):
 return re.sub(r'\s+','',s).replace('ɡ','g').strip()
def ascii_pinyin(s):
 return ''.join(c for c in unicodedata.normalize('NFD',s) if unicodedata.category(c)!='Mn').lower().replace('ü','v').replace("'",'').replace('’','')
def slug(code):return code.lower().replace('+','-plus').replace(',','-').replace(' ','')
def source_ref(cfg,clause):return {'sourceId':cfg['id'],'clause':clause}

def instances_for(p):
 name=p['nameZh']; mid=p['meridianId'] in ['GV','CV']
 if p['kind']=='meridian': specs=[('midline',1)] if mid else [('left',1),('right',1)]
 elif name in ['聚泉','海泉','接脊','十七椎']:specs=[('midline',1)]
 elif name=='四神聪':specs=[('midline',1),('midline',2),('left',1),('right',1)]
 else:
  n={'夹脊':17,'二白':2,'腰痛点':2,'八邪':4,'四缝':4,'十宣':5,'髋骨':2,'八风':4,'气端':5}.get(name,1)
  specs=[(s,i) for s in ['left','right'] for i in range(1,n+1)]
 ret=[]
 for side,ordinal in specs:
  inst={'id':f"{p['id']}-{side}-{ordinal}",'side':side,'ordinal':ordinal,'anchor':{'modelId':'bodyparts3d-male-v4','position':None,'landmarkRefs':[],'method':'not-yet-mapped','status':'unmapped'}}
  if name=='金津玉液':inst['code']='EX-HN12' if side=='left' else 'EX-HN13'
  if name=='四神聪':inst['labelZh']='左侧点' if side=='left' else '右侧点' if side=='right' else '前方点' if ordinal==1 else '后方点'
  if name=='夹脊':inst['level']=f'T{ordinal}' if ordinal<=12 else f'L{ordinal-12}'
  if name in ['十宣','气端']:inst['digit']=ordinal
  ret.append(inst)
 return ret

def parse(cfg,kind):
 txt=text_of(cfg)
 section_title='5 十四经穴名称与定位' if kind=='meridian' else '7 名称与定位'
 start=re.search(r'^'+section_title+r'\s*$',txt,re.M).start()
 txt=txt[start:]
 txt=txt.split('附录 A')[0].split('附 录 A')[0]
 # The full section number and source codes are retained, independently of array order.
 pat=re.compile(r'^(?P<section>[57]\.\d+\.\d+)\s+(?P<head>[^\n]+)\n',re.M)
 matches=list(pat.finditer(txt)); pts=[]
 for i,m in enumerate(matches):
  head=m['head'].strip(); body=txt[m.end():matches[i+1].start() if i+1<len(matches) else len(txt)]
  head=re.sub(r'\s*（','（',head)
  hm=re.match(r'(?P<name>\S+)\s+(?P<py>[^（]+)(?:（(?P<code>[^）]+)）)?$',head)
  if not hm:raise ValueError(('header',m['section'],head))
  name=hm['name']; py=re.sub(r'\s+','',hm['py']).replace('ɡ','g')
  code_text=plain(hm['code'] or '')
  codes=code_text.split('，') if code_text else []
  # Position is an anatomy fact summary only: no needling, treatment or therapeutic claims.
  loc=body.strip().split('注')[0]
  loc=re.sub(r'（(?:图见|见)\s*GB/T.*?）','',loc,flags=re.S)
  loc=plain(loc).split('。')[0].strip('。')
  loc=loc.split('\n')[0]
  if not loc.startswith('在'):raise ValueError(('location',name,loc[:150]))
  # Rewrite as a terse landmark record, rather than reproducing the standard's explanatory notes.
  loc=loc[1:].replace('，','；').rstrip('。')+'。'
  if name=='安眠':loc=loc.replace('连7线','连线')
  if name=='会阴':loc='会阴部；阴囊根部与肛门连线的中点。'
  channel=CHANNELS[int(m['section'].split('.')[1])-1][0] if kind=='meridian' else None
  if kind=='meridian':pid=slug(codes[0])
  elif codes:pid=slug('-'.join(codes))
  else:pid='extra-'+ascii_pinyin(py)
  if pid in LOCATION_CORRECTIONS:loc=LOCATION_CORRECTIONS[pid]['locationZh']
  region=loc.split('；')[0]
  point={'id':pid,'kind':kind,'nameZh':name,'pinyin':py,'pinyinSearch':ascii_pinyin(py),'aliases':ALIASES.get(name,[]),'codes':codes,'meridianId':channel,'region':region,'bodyRegionGroup':REGIONS[int(m['section'].split('.')[1])] if kind=='extra' else region,'order':int(m['section'].split('.')[-1]),'locationZh':loc,'locationLabelZh':'体表定位摘要','sourceRefs':[source_ref(cfg,m['section'])],'contentReviewStatus':'source-transcribed-pending-medical-review','coordinateReviewStatus':'unmapped','viewPreset':'body','descriptionZh':'依据标准定位资料整理的学习条目。','instances':[]}
  if name=='金津玉液':point['pinyin']='Jīnjīn Yùyè';point['pinyinSearch']='jinjinyuye';point['aliases']=ALIASES[name];point['id']='ex-hn12-hn13'
  if name in ['聚泉','海泉','金津玉液','龈交']:
   point['viewPreset']='oral';point['surfaceType']='oral-mucosa'
  elif name=='内迎香':point['viewPreset']='nasal';point['surfaceType']='nasal-mucosa'
  elif name in ['承泣','球后']:point['viewPreset']='orbit';point['surfaceType']='orbital-margin'
  elif name in ['会阴','长强']:point['viewPreset']='perineum';point['surfaceType']='perineal-skin'
  else:point['surfaceType']='skin'
  if point['meridianId']=='GV' and name=='印堂':
   point['aliases']=['EX-HN3'];point['descriptionZh']='本目录依中国国标归入督脉，代码GV24+；其他标准可能采用不同归类和代码。'
  terms=[t for t in ANATOMY_TERMS if t in plain(body)]
  point['anatomyTermsZh']=terms
  point['anatomyZh']=('定位参照：'+'、'.join(terms)+'。') if terms else ('体表参考区：'+region+'。可切换皮肤、肌肉与骨骼观察相对位置。')
  if region=='足底' or name=='独阴':point['viewPreset']='sole'
  if name=='子宫':point['descriptionZh']='经外奇穴的体表条目名称；与内脏器官模型分别管理。'
  if name in ALIASES:point['sourceRefs'].append(source_ref(SOURCE_CONFIG[1],'附录C'))
  if name=='安眠':point['transcriptionCorrections']=[{'from':'连7线','to':'连线','reasonZh':'公开副本存在排印杂字；按上下文与标准解读核对后去除，待正式版复核。'}]
  point['instances']=instances_for(point)
  pts.append(point)
 return pts

def build():
 pts=parse(SOURCE_CONFIG[0],'meridian')+parse(SOURCE_CONFIG[1],'extra')
 assert len(pts)==413,len(pts)
 assert len({p['id'] for p in pts})==413
 assert sum(p['kind']=='meridian' for p in pts)==362
 assert sum(p['kind']=='extra' for p in pts)==51
 for code,_,_,count in CHANNELS:
  assert sum(p['meridianId']==code for p in pts)==count,(code,count)
 dataset={'schemaVersion':1,'datasetId':'cn-gb2021-413-v1','language':'zh-CN','modelSex':'male','intendedUseZh':'人体经络与解剖学习','clinicalGuidance':False,'counts':{'meridianPoints':362,'extraPointEntries':51,'totalEntries':413,'instances':sum(len(p['instances']) for p in pts)},'contentStatusZh':'国标范围目录；定位按公开文本整理，待正式版逐条复核与专业审阅。','coordinateStatusZh':'三维教学标记与标准文字分开记录；未经过专业审校的坐标明确标示待校对。','points':pts}
 (OUT/'acupoints.json').write_text(json.dumps(dataset,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 sources=[]
 for cfg in SOURCE_CONFIG:
  c=dict(cfg);c['sha256']=hashlib.sha256((SRC/cfg['file']).read_bytes()).hexdigest();c['retrievedDate']='2026-10-20';c['licenseStatus']='copyrighted-standard-public-reading-not-an-open-license';c['appUsage']='Names, identifiers and concise anatomical fact summaries; full PDF is not packaged in the app.';sources.append(c)
 (OUT/'sources.json').write_text(json.dumps({'schemaVersion':1,'sources':sources},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 (ROOT/'manifests/content-sources.json').write_text(json.dumps({'schemaVersion':1,'sources':sources},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 from update_functions import apply_to_catalogue
 apply_to_catalogue()
 print(json.dumps(dataset['counts'],ensure_ascii=False))
if __name__=='__main__':build()
