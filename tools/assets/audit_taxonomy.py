#!/usr/bin/env python3
"""Curate current real structures against official element/concept mappings.

This creates an explicit allowlist, not an 'everything else is an organ' rule.
Anatomical spaces remain visible data under unknown instead of posing as tissue.
"""
import json
import re
from pathlib import Path
from build_models import source_tables
from connective_supplement import meshes

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent

PREFERRED={
    'FJ1755':('FMA61934','大脑半球脉络丛'), 'FJ1803':('FMA61934','大脑半球脉络丛'),
    'FJ1769':('FMA62004','延髓'), 'FJ1831':('FMA62004','延髓'),
    'FJ1770':('FMA61993','中脑'), 'FJ1817':('FMA61993','中脑'),
    'FJ1775':('FMA67943','脑桥'), 'FJ1822':('FMA67943','脑桥'),
    'FJ1843':('FMA83966','小脑幕'), 'FJ2629':('FMA63120','胰腺实质'),
    'FJ2770':('FMA55130','会厌'), 'FJ2771':('FMA55227','舌骨会厌韧带'),
    'FJ2789':('FMA55237','环甲正中韧带'), 'FJ2814':('FMA59816','口唇'),
    'FJ3153':('FMA7488','剑突'), 'FJ3178':('FMA7487','胸骨体'),
    'FJ3290':('FMA7486','胸骨柄'),
    **{key:('FMA21930','肛门外括约肌（源网格片）') for key in ['FJ1450','FJ1450M','FJ2543','FJ2548']},
}
SPACE_IDS={'FJ1282','FJ1332','FJ1301','FJ1352','FJ1730','FJ1731','FJ1737',
           'FJ1738','FJ1752','FJ1763','FJ1811','FJ1767','FJ1814'}
CONNECTIVE_RE=r'\b(tendon|tendinous|aponeurosis|retinaculum|ligament|raphe|interosseous membrane|thyrohyoid membrane|conus elasticus|tarsal plate|trochlea)\b|iliotibial tract|linea alba|dura mater|tentorium cerebelli'


def connective_type(name):
    if 'iliotibial tract' in name or 'fascia' in name: return 'fascia','筋膜'
    if 'linea alba' in name: return 'aponeurotic-junction','腱膜交织区'
    if 'retinaculum' in name: return 'retinaculum','支持带'
    if 'tarsal plate' in name: return 'dense-connective-plate','致密结缔组织板'
    if 'trochlea' in name: return 'fibrocartilaginous-pulley','纤维软骨滑车'
    if 'suspensory ligament' in name and 'lens' in name: return 'zonular-fibers','晶状体悬韧带纤维'
    if 'ligament' in name: return 'ligament','韧带'
    if 'raphe' in name: return 'fibrous-raphe','纤维性肌缝'
    if 'conus elasticus' in name: return 'fibroelastic-membrane','纤维弹性膜'
    if 'tentorium' in name or 'dura mater' in name: return 'dural-fold','硬脑膜褶'
    if 'membrane' in name: return 'fibrous-membrane','纤维膜'
    if 'tendinous ring' in name: return 'tendinous-ring','腱环'
    if 'tendinous arch' in name: return 'tendinous-arch','腱弓'
    if 'tendon' in name: return 'tendon','肌腱'
    raise ValueError(name)


def organ_type(name, ancestors):
    # These known families are checked explicitly. Generic FMA 'organ region'
    # alone is not evidence of visceral tissue (it also includes muscle/bone parts).
    if re.search(r'mesentery|mesoappendix|mesocolon',name): return 'serous-membrane','浆膜系膜'
    if re.search(r'papillary|wall of (.*atrium|ventricle)|taenia',name): return 'organ-muscular-part','器官肌性部件'
    if re.search(r'valve|leaflet',name): return 'cardiac-valve','心脏瓣膜'
    if name=='epiglottis': return 'mucosa-cartilage-composite','黏膜与软骨复合结构'
    if re.search(r'brain|neuraxis|gray matter|white matter|gyrus|neural|cerebr|diencephalon|hypothalam|thalam|hindbrain|metencephalon', ' '.join(ancestors)):
        return 'neural-organ-part','中枢神经器官部件'
    if re.search(r'eye|choroid|cornea|corona ciliaris|iris|lacrimal|lens|retina|sclera|vitreous',name):
        return 'ocular-organ-part','眼及泪器部件'
    if re.search(r'liver|hepat|biliar|gallbladder|cystic duct|pancrea|spleen|esophagus|stomach|appendix|colon|rectum|duodenum|ileum|jejunu|ileocecal',name):
        return 'digestive-organ-part','消化器官或部件'
    if re.search(r'bronch|trachea',name): return 'respiratory-organ-part','呼吸器官或部件'
    if re.search(r'adrenal|thymus|thyroid|pituitary|pineal|sublingual gland|submandibular gland',name): return 'glandular-organ-part','腺体器官或部件'
    if re.search(r'kidney|ureter|urethra|bladder|testis|epididymis|deferent|seminal|prostate|penis',name):
        return 'urogenital-organ-part','泌尿生殖器官或部件'
    if re.search(r'gingiva|tongue|lip|external ear',name): return 'regional-organ-composite','局部器官复合结构'
    return None


def main():
    names,elements,inverse=source_tables()
    baseline=json.loads((ROOT/'app/src/main/assets/anatomy.json').read_text())
    added=[m for m,_,_ in meshes()]
    current=baseline['structures']+[m for m in added if m['id'] not in {s['id'] for s in baseline['structures']}]
    entries={};unresolved=[]
    for original in current:
        s=dict(original);ident=s['id'];candidates=inverse.get(ident,[])
        ancestors=[n for _,_,n in candidates]
        fields={key:s[key] for key in ['nameEn','nameZh','system']}
        fields['geometrySystem']=s.get('geometrySystem',s['system'])
        fields['geometryNameEn']=s.get('geometryNameEn',s['nameEn'])
        note='对应官方原始网格元件；应用系统分组不等同于 FMA 本体中的 organ 大类。'
        if ident in PREFERRED:
            cid,zh=PREFERRED[ident]
            assert any(c==cid for _,c,_ in candidates),(ident,cid)
            fields.update(nameEn=names[cid],nameZh=zh,sourceConceptId=cid,
                          originalSourceConceptId=s.get('originalSourceConceptId',s.get('sourceConceptId')),
                          originalNameEn=s.get('originalNameEn',s['nameEn']))
            note=f'同一元件同时映射泛化概念与 {cid} ({names[cid]})；选用更具体的官方概念，未删除或替换网格。'
        else:
            if s.get('sourceConceptId'): fields['sourceConceptId']=s['sourceConceptId']
        name=fields['nameEn'].lower()
        if ident in ['FJ2763','FJ2764']:
            fields['nameZh']=('左侧' if ident=='FJ2763' else '右侧')+'茎突舌骨韧带'
        elif ident=='FJ1343': fields['nameZh']='右侧上睑提肌腱'
        if ident.startswith('ZA_') and s['system']=='connective':
            system='connective';kind=s['tissueType'];kind_zh=s['tissueTypeZh']
        elif ident in SPACE_IDS:
            system='space';kind='anatomical-space';kind_zh='解剖腔隙·参考'
            note='此网格表示腔隙、通道或空间形状，不是实体内脏；保留真实参考网格并列入独立解剖腔隙显示系统。'
        elif re.search(CONNECTIVE_RE,name):
            system='connective';kind,kind_zh=connective_type(name)
        elif 'trapezius' in name:
            system='muscle';kind='skeletal-muscle-part';kind_zh='骨骼肌分部'
            if s['system']!='muscle': fields['layer']=1
        elif ident in ['FJ3153','FJ3178','FJ3290']:
            system='bone';kind='bone-part';kind_zh='骨性部件'
        elif s['system']=='skin': system='skin';kind='skin';kind_zh='皮肤'
        elif s['system']=='muscle':
            system='muscle'
            kind=('skeletal-muscle-group' if 'set of' in name or s.get('structureType')=='skeletal-muscle-group'
                  else 'skeletal-muscle-part' if 'head of' in name or 'part of' in name else 'skeletal-muscle')
            kind_zh={'skeletal-muscle-group':'骨骼肌群','skeletal-muscle-part':'骨骼肌分部','skeletal-muscle':'骨骼肌'}[kind]
        elif s['system']=='bone':
            system='bone';kind='cartilage' if re.search(r'cartilage|disk|meniscus',name) else 'tooth' if re.search(r'tooth|teeth',name) else 'bone'
            kind_zh={'cartilage':'骨骼系统软骨或纤维软骨','tooth':'牙','bone':'骨'}[kind]
        elif s['system']=='organ':
            found=organ_type(name,ancestors)
            if not found:
                unresolved.append((ident,name));system='unknown';kind='unresolved';kind_zh='性质待核实'
                note='现有源概念不足以确定应用系统，保留网格并独立列为待分类，未默认归入内脏。'
            else: system='organ';kind,kind_zh=found
        else:
            raise ValueError((ident,s['system']))
        fields.update(system=system,tissueType=kind,tissueTypeZh=kind_zh,
            sourceConceptNameEn=names.get(fields.get('sourceConceptId'),s.get('sourceConceptNameEn',s['nameEn'])),
            sourceConceptNoteZh=note,taxonomyPreviousSystem=s.get('taxonomyPreviousSystem',s['system']),
            taxonomyReviewStatus='source-checked-professional-review-pending')
        if system=='connective' and s.get('geometrySystem',s['system'])=='muscle':
            for key in ['descriptionZh','originZh','insertionZh','functionZh','descriptionSources']:
                if s.get(key):fields[key]=s[key]
        entries[ident]=fields
    document={'schemaVersion':1,'systemLabels':{'skin':'皮肤','muscle':'肌肉','bone':'骨骼','organ':'器官','connective':'筋膜·腱韧带','space':'解剖腔隙·参考','unknown':'待分类'},
        'sourceEvidence':['tools/assets/source/isa_parts_list_e.txt','tools/assets/source/isa_element_parts.txt',
            'tools/assets/source/partof_parts_list_e.txt','tools/assets/source/partof_element_parts.txt'],
        'terminologyReference':'https://fipat.library.dal.ca/wp-content/uploads/2020/09/FIPAT-TA2-Part-2.pdf',
        'policyZh':['逐个 ID 的明确分组清单；未列入清单的系统外源元件不默认归为 organ。',
            'connective 为显示分组，包含筋膜、腱、腱膜、韧带和其他明确纤维性支持结构，不等于每项都是筋膜。',
            '器官系统保留脑、眼、心脏肌性部件和内脏浆膜系膜；不因组织含结缔成分就全数改称筋膜。',
            '骨骼系统保留软骨、椎间盘与牙，并以 tissueType 区分；独立韧带和骨间膜归 connective。',
            '解剖腔隙不能冒充实体内脏；独立列 space 作为参考。确有性质未知者才归 unknown。'],
        'entries':entries}
    (HERE/'taxonomy.json').write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n')
    from collections import Counter
    print('counts',Counter(e['system'] for e in entries.values()))
    print('unresolved',unresolved)
    print('connective',[(k,e['nameZh']) for k,e in entries.items() if e['system']=='connective'])


if __name__=='__main__':main()
