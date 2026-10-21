#!/usr/bin/env python3
"""Attach a fresh engineering audit to point details without altering anchors/routes."""
from pathlib import Path
import argparse
import copy
import hashlib
import json

ROOT=Path(__file__).resolve().parents[2]
NOTICES={
 'gb31':'外侧大腿19寸链尚缺大转子及腘横纹标定；当前标记仍为原教学位置，未完成本次定位校准。',
 'gb32':'外侧大腿19寸链尚缺大转子及腘横纹标定；当前标记仍为原教学位置，未完成本次定位校准。',
 'lr7':'阴陵泉后1寸所需的局部量尺和后方关系尚未校准；当前示意标记未完成本次定位修正。',
 'extra-jianqian':'腋前皱襞标志与局部寸距尚待标定；现位置与纵向代理尺仍有偏差，未作为已校准点。',
}

def update(report_path):
 path=ROOT/'app/src/main/assets/content/acupoints.json'
 data=json.loads(path.read_text());before=copy.deepcopy(data)
 report=json.loads(report_path.read_text());reviews={e['id']:e for e in report['entries']}
 if len(reviews)!=226:raise ValueError('Expected all 226 cun entries; refuse a partial review export')
 for point in data['points']:
  review=reviews.get(point['id'])
  if review is not None:
   expected={i['instanceId']:i['positionMetres'] for i in review['instances']}
   actual={i['id']:i['anchor']['position'] for i in point['instances']}
   if actual!=expected:raise ValueError('Stale coordinates in review: '+point['id'])
   if review.get('locationZh',point['locationZh'])!=point['locationZh']:raise ValueError('Stale location text: '+point['id'])
   text=review['appReviewZh'].strip()
   if not text:raise ValueError('Missing review summary: '+point['id'])
   point['cunReviewZh']=text
  if point['id'] in NOTICES:point['coordinateNoticeZh']=NOTICES[point['id']]
 # Only these optional detail fields are allowed to change.
 stripped=copy.deepcopy(data)
 for old,new in zip(before['points'],stripped['points']):
  for key in ('cunReviewZh','coordinateNoticeZh'):
   if key in old:new[key]=old[key]
   else:new.pop(key,None)
 if stripped!=before:raise AssertionError('Review export changed point identity/content/geometry')
 output=json.dumps(data,ensure_ascii=False,indent=2)+'\n'
 temporary=path.with_suffix('.json.tmp');temporary.write_text(output);temporary.replace(path)
 print('Attached',len(reviews),'point-detail reviews; all coordinates and other fields preserved; sha256',hashlib.sha256(output.encode()).hexdigest())

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('report',type=Path);args=parser.parse_args();update(args.report)
