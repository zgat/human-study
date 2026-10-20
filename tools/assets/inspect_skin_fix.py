#!/usr/bin/env python3
"""Record and check the narrow low-skin quality exception using actual GLB bytes."""
import array
import hashlib
import json
import math
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT/'app/src/main/assets'
REPORT = ROOT/'manifests/model-low-skin-fix.json'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def geometry(path):
    raw = path.read_bytes()
    length = struct.unpack_from('<I',raw,12)[0]
    doc = json.loads(raw[20:20+length])
    binary = memoryview(raw)[28+length:]
    result = {}
    def payload(index):
        accessor = doc['accessors'][index]
        view = doc['bufferViews'][accessor['bufferView']]
        start = view.get('byteOffset',0)+accessor.get('byteOffset',0)
        width = {'VEC3':3,'SCALAR':1}[accessor['type']]
        size = {5126:4,5125:4,5123:2}[accessor['componentType']]
        return accessor, bytes(binary[start:start+accessor['count']*width*size])
    skin = None
    for node in doc['nodes']:
        primitive = doc['meshes'][node['mesh']]['primitives'][0]
        fields = {key:payload(index) for key,index in {
            'position':primitive['attributes']['POSITION'],
            'normal':primitive['attributes']['NORMAL'],'indices':primitive['indices']}.items()}
        result[node['name']] = {key:{'count':a['count'],'componentType':a['componentType'],
                                    'sha256':sha(data)} for key,(a,data) in fields.items()}
        if node['name'] == 'FJ2810':
            skin = fields
    return {'sha256':sha(raw),'bytes':len(raw),'nodes':result},skin


def winding(fields):
    def values(key):
        accessor,data=fields[key]
        result=array.array({5126:'f',5125:'I',5123:'H'}[accessor['componentType']])
        result.frombytes(data)
        if sys.byteorder!='little': result.byteswap()
        return result
    p,n,indices=(values(k) for k in ['position','normal','indices'])
    negative=zero=0; negative_area=total_area=0.
    for start in range(0,len(indices),3):
        a,b,c=(indices[start+i]*3 for i in range(3))
        u=[p[b+i]-p[a+i] for i in range(3)]; v=[p[c+i]-p[a+i] for i in range(3)]
        cross=[u[(i+1)%3]*v[(i+2)%3]-u[(i+2)%3]*v[(i+1)%3] for i in range(3)]
        area=math.sqrt(sum(x*x for x in cross))*.5
        dot=sum(cross[i]*(n[a+i]+n[b+i]+n[c+i]) for i in range(3))
        total_area+=area
        zero+=int(area==0)
        if dot < -1e-14:
            negative+=1; negative_area+=area
    return {'vertices':len(p)//3,'triangles':len(indices)//3,'negativeNormalDotFaces':negative,
            'negativeNormalDotAreaFraction':negative_area/total_area,'zeroAreaFaces':zero,
            'negativeNormalDotThreshold':-1e-14}


def snapshot():
    models={}; stats={}
    for quality in ['standard','low']:
        models[quality],skin=geometry(ASSETS/f'models/body-{quality}.glb')
        stats[quality]=winding(skin)
    anatomy=json.loads((ASSETS/'anatomy.json').read_text())
    return {'models':models,'skinWinding':stats,'structureMetadataSha256':sha(
        json.dumps(anatomy['structures'],ensure_ascii=False,sort_keys=True).encode())}


def main():
    current=snapshot()
    if sys.argv[1:] == ['before']:
        report={'schemaVersion':1,'purposeZh':'仅流畅档皮肤复用标准档几何，其余低档结构和标准档整包不变。',
                'before':current}
    elif sys.argv[1:] == ['check']:
        report=json.loads(REPORT.read_text()); before=report['before']
        assert current['models']['standard']['sha256']==before['models']['standard']['sha256']
        old,new=before['models']['low']['nodes'],current['models']['low']['nodes']
        assert list(old)==list(new)
        changed=[key for key in new if new[key]!=old[key]]
        assert changed==['FJ2810'],changed
        assert new['FJ2810']==current['models']['standard']['nodes']['FJ2810']
        assert current['structureMetadataSha256']==before['structureMetadataSha256']
        report.update(after=current,status='passed',changedLowNodes=changed,
            checks=['standard entire GLB SHA-256 unchanged','all 1230 non-skin low node POSITION/NORMAL/index hashes unchanged',
                    'low skin POSITION/NORMAL/index raw bytes and component types equal standard',
                    'structure IDs/order/coordinates/names/layers/learning metadata unchanged'])
    else:
        raise SystemExit('Usage: inspect_skin_fix.py before|check')
    temporary=REPORT.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n'); temporary.replace(REPORT)
    print(json.dumps({'phase':sys.argv[1], 'skinWinding':current['skinWinding'],
                      'modelSha256':{key:value['sha256'] for key,value in current['models'].items()}},indent=2))


if __name__=='__main__': main()
