"""Explicit current-asset taxonomy and preferred official FMA mappings."""
import json
from collections import Counter
from functools import lru_cache
from pathlib import Path

HERE=Path(__file__).resolve().parent


@lru_cache(maxsize=1)
def registry(): return json.loads((HERE/'taxonomy.json').read_text())


def preferred_source(ident,candidates):
    preferred=registry()['entries'].get(ident,{}).get('sourceConceptId')
    if preferred:
        return next(item for item in candidates if item[1]==preferred)
    return candidates[0]


def system_for(ident):
    # Outside this audited, explicitly enumerated asset set, omit the source
    # element rather than interpreting an unrecognized system as an organ.
    return registry()['entries'].get(ident,{}).get('system')


def apply_taxonomy(structures):
    data=registry();entries=data['entries']
    for s in structures:
        entry=entries[s['id']]
        old_name=s['nameZh'];old_description=s.get('descriptionZh','')
        s.update(entry)
        s['translationStatus']='reviewable'
        if s['system']!='muscle':
            s['layer']=0
            for key in ['layerBasis','layerReviewStatus']:
                s.pop(key,None)
        elif s['layer']==0:
            s['layer']=1
        if s['system']=='muscle':
            s.setdefault('layerBasis','regional-anatomy-teaching-groups; not concentric shells')
            s.setdefault('layerReviewStatus','editorial-unreviewed')
        if s['system']=='connective':
            s['structureType']=s['tissueType']
        if old_name!=s['nameZh']:
            s['descriptionZh']=old_description.replace(old_name,s['nameZh'])
        if s['system'] in ['space','unknown']:
            s['descriptionZh']=s['nameZh']+'。'+s['sourceConceptNoteZh']
        elif s['system']=='connective' and 'descriptionZh' not in entry:
            s['descriptionZh']=s['nameZh']+'，属于'+s['tissueTypeZh']+'。'+s['sourceConceptNoteZh']
        if s['id']=='FJ1448':
            s['descriptionZh']='腹白线位于前腹壁正中线，由两侧腹肌腱膜纤维交织形成；属于腱膜性结缔组织，不是肌腹或内脏。'
    return {'countsBySystem':dict(Counter(s['system'] for s in structures)),
        'countsByTissueType':dict(Counter(s['tissueType'] for s in structures)),
        'unknownExamples':[{'id':s['id'],'nameZh':s['nameZh'],'tissueType':s['tissueType'],'reasonZh':s['sourceConceptNoteZh']}
                           for s in structures if s['system']=='unknown'],
        'spaceExamples':[{'id':s['id'],'nameZh':s['nameZh'],'tissueType':s['tissueType'],'reasonZh':s['sourceConceptNoteZh']}
                           for s in structures if s['system']=='space'],
        'policyZh':data['policyZh']}
