"""Chinese muscle learning metadata; exact aliases prevent substring mismatches.

Facts are independently summarized in Chinese. References identify factual
cross-checks; source textbook expression, images and tables are not reproduced.
All entries remain pending professional anatomical review.
"""
import json
import re
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCES = {
    'upper': ('OpenStax Anatomy and Physiology 2e 11.5', 'https://openstax.org/books/anatomy-and-physiology-2e/pages/11-5-muscles-of-the-pectoral-girdle-and-upper-limbs'),
    'lower': ('OpenStax Anatomy and Physiology 2e 11.6', 'https://openstax.org/books/anatomy-and-physiology-2e/pages/11-6-appendicular-muscles-of-the-pelvic-girdle-and-lower-limbs'),
    'axial': ('OpenStax Anatomy and Physiology 2e 11.3', 'https://openstax.org/books/anatomy-and-physiology-2e/pages/11-3-axial-muscles-of-the-head-neck-and-back'),
    'thorax': ('OpenStax Anatomy and Physiology 2e 11.4', 'https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax'),
    'eye': ('StatPearls Anatomy, Head and Neck, Eye Extraocular Muscles', 'https://www.ncbi.nlm.nih.gov/books/NBK519565/'),
    'larynx': ('StatPearls Anatomy, Head and Neck: Laryngeal Muscles', 'https://www.ncbi.nlm.nih.gov/books/NBK545265/'),
    'pharynx': ('StatPearls Anatomy, Head and Neck, Pharynx', 'https://www.ncbi.nlm.nih.gov/books/NBK544271/'),
    'palate': ('StatPearls Anatomy, Head and Neck, Palate', 'https://www.ncbi.nlm.nih.gov/books/NBK557817/'),
    'pelvic': ('StatPearls Anatomy, Abdomen and Pelvis: Levator Ani Muscle', 'https://www.ncbi.nlm.nih.gov/books/NBK556078/'),
    'foot': ('StatPearls Anatomy, Bony Pelvis and Lower Limb, Foot Muscles', 'https://www.ncbi.nlm.nih.gov/books/NBK539705/'),
    'back': ('StatPearls Anatomy, Back, Muscles', 'https://www.ncbi.nlm.nih.gov/books/NBK537074/'),
    'tongue': ('StatPearls Anatomy, Head and Neck, Tongue', 'https://www.ncbi.nlm.nih.gov/books/NBK507782/'),
}


def normalize(name):
    return re.sub(r'\s+', ' ', re.sub(r'\b(left|right)\b', '', name.lower())).strip()


def definitions():
    aliases = {}
    for line_number, line in enumerate((HERE/'muscle-learning.tsv').read_text().splitlines(), 1):
        if not line or line.startswith('#'):
            continue
        names, kind, origin, insertion, function, source_key = line.split('|')
        assert source_key in SOURCES, (line_number, source_key)
        detail = {'structureType': kind, 'originZh': origin, 'insertionZh': insertion,
                  'functionZh': function, 'descriptionSources': [SOURCES[source_key][1]],
                  'learningFamilyEn': names.split(';')[0]}
        for name in names.split(';'):
            assert name not in aliases, (line_number, name)
            aliases[name] = detail
    return aliases, json.loads((HERE/'muscle-descriptions.json').read_text())['muscles']


def apply_learning(structures):
    aliases, established = definitions()
    muscle = [s for s in structures if s['system'] == 'muscle']
    duplicates = Counter(normalize(s['nameEn']) for s in muscle)
    unresolved = []
    for s in muscle:
        name = normalize(s['nameEn'])
        if name in aliases:
            detail = dict(aliases[name])
            description = f"{s['nameZh']}。{detail['functionZh']}"
        else:
            match = next(((key, value) for key, value in established.items()
                          if re.search(r'\b'+re.escape(key)+r'\b', name)), None)
            if not match:
                unresolved.append({'id': s['id'], 'nameEn': s['nameEn']})
                continue
            key, original = match
            detail = {field: original[field] for field in ['originZh','insertionZh','functionZh']}
            detail.update(structureType='skeletal-muscle', learningFamilyEn=key,
                          descriptionSources=original['sources'])
            description = original['descriptionZh']
        s.update(detail)
        s['learningReviewStatus'] = 'editorial-unreviewed'
        scopes = []
        if s['structureType'] in ['tendon','fascia','retinaculum']:
            scopes.append('本项为肌肉相关结缔组织，附着关系按结构描述，不具有独立肌腹收缩。')
        elif 'head of' in name or 'part of' in name:
            scopes.append('本件为名称所示的肌头或肌部；起止点与作用同时说明所属肌的共同关系。')
        elif 'set of' in name or s['structureType'] == 'skeletal-muscle-group':
            scopes.append('本件按源图谱表示肌束或肌群，说明适用于该组结构。')
        if duplicates[name] > 2:
            scopes.append('同名结构在源模型中分为多个网格元件，不能将元件数量直接当作肌肉条数。')
        if not scopes:
            scopes.append('说明对应所选侧别的参考解剖结构；肌附着及形态可有个体变异。')
        s['learningScopeZh'] = ' '.join(scopes)
        s['descriptionZh'] = description + ' ' + s['learningScopeZh']
        if name in ['superficial perineal muscle','external anal sphincter']:
            s['sourceConceptAliases'] = ['FMA19728','FMA21930']
    if unresolved:
        raise ValueError('Missing muscle learning definitions: '+json.dumps(unresolved))
    assert all(s.get(k) for s in muscle for k in ['originZh','insertionZh','functionZh','descriptionSources'])
    return {
        'muscleSystemNodes': len(muscle),
        'completeOriginInsertionFunction': len(muscle),
        'completeSourceReferences': len(muscle),
        'coveragePercent': 100,
        'countsByStructureType': dict(Counter(s['structureType'] for s in muscle)),
        'distinctEnglishLearningFamilies': len({s['learningFamilyEn'] for s in muscle}),
        'reviewStatus': 'editorial-unreviewed',
        'references': [{'key':key,'title':title,'url':url} for key,(title,url) in SOURCES.items()],
        'limitsZh': [
            '中文文字为独立撰写的解剖事实概述；参考链接用于事实核验，不分发所引教材的图片、表格或原文。',
            '503 是肌肉系统模型节点数，包含分头、分部、肌群、肌腱和筋膜，不等于独立肌肉条数。',
            '左右侧和同肌分部可共享所属肌的完整概述，具体部件关系在说明中注明。',
            '全部说明和部件对应关系仍需医学专业人员逐项审校，不能用于诊断或操作指导。'
        ]
    }
