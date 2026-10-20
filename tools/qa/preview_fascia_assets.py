#!/usr/bin/env python3
"""Optional offline orthographic GLB preview (Pillow/numpy; not Android proof)."""
import argparse
import hashlib
import json
import struct
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'outputs/qa/fascia-anatomy-preview.png')
    args=parser.parse_args();args.output.parent.mkdir(parents=True,exist_ok=True)
    path=ROOT/'app/src/main/assets/models/body-standard.glb';raw=path.read_bytes()
    length=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+length]);binary=raw[28+length:]
    anatomy=json.loads((ROOT/'app/src/main/assets/anatomy.json').read_text())
    metadata={row['id']:row for row in anatomy['structures']}
    def array(index):
        accessor=doc['accessors'][index];view=doc['bufferViews'][accessor['bufferView']]
        width={'VEC3':3,'SCALAR':1}[accessor['type']]
        values=np.frombuffer(binary,dtype={5126:'<f4',5125:'<u4',5123:'<u2'}[accessor['componentType']],
            offset=view.get('byteOffset',0)+accessor.get('byteOffset',0),count=accessor['count']*width)
        return values.reshape(-1,width) if width>1 else values
    geometry=[];skin=None
    colors={'fascia':(71,157,141),'aponeurosis':(202,145,62),'retinaculum':(100,125,204)}
    for node in doc['nodes']:
        ident=node['name'];meta=metadata[ident]
        if ident!='FJ2810' and not (ident.startswith('ZA_') and meta['system']=='connective'):continue
        primitive=doc['meshes'][node['mesh']]['primitives'][0]
        vertices=array(primitive['attributes']['POSITION']);indices=array(primitive['indices']).reshape(-1,3)
        triangles=vertices[indices]
        if ident=='FJ2810':skin=triangles
        else:geometry.append((ident,triangles,colors[meta['tissueType']]))
    width,height=1920,1510;image=Image.new('RGB',(width,height),(246,247,244));draw=ImageDraw.Draw(image)
    font_path='/System/Library/Fonts/STHeiti Light.ttc'
    if not Path(font_path).exists():font_path='/System/Library/Fonts/Supplemental/Arial.ttf'
    title=ImageFont.truetype(font_path,32);body=ImageFont.truetype(font_path,21);small=ImageFont.truetype(font_path,18)
    draw.text((40,24),'真实筋膜、腱膜与支持带 · GLB 离线几何预览',font=title,fill=(29,45,45))
    draw.text((40,70),'60 个 Z-Anatomy 源网格（新增 52）；共同参考平移配准，未完成逐片专业校对。',font=body,fill=(68,81,80))
    all_points=skin.reshape(-1,3);min_y=float(all_points[:,1].min());max_y=float(all_points[:,1].max())
    scale=1180/(max_y-min_y)
    views=[('正面',0,2,1),('背面',0,2,-1),('侧面',2,0,1)]
    for panel,(label,horizontal,depth,sign) in enumerate(views):
        cx=320+panel*640;top=172
        draw.text((cx-25,126),label,font=body,fill=(36,55,55))
        def project(triangles):
            x=triangles[:,:,horizontal]*scale*(-1 if panel==1 else 1)+cx
            y=top+(max_y-triangles[:,:,1])*scale
            return np.stack([x,y],axis=2)
        # Draw real skin as a pale contextual silhouette, then source sheets.
        # This deliberate ghost context is not occlusion-correct app rendering.
        for polygon in project(skin):draw.polygon([tuple(p) for p in polygon],fill=(227,231,226))
        triangles=np.concatenate([item[1] for item in geometry])
        face_colors=np.concatenate([np.tile(np.array(color),(len(tris),1)) for _,tris,color in geometry])
        projected=project(triangles);order=np.argsort(triangles[:,:,depth].mean(axis=1)*sign)
        # Simple diffuse shading is derived from the real face normal.
        normal=np.cross(triangles[:,1]-triangles[:,0],triangles[:,2]-triangles[:,0])
        normal/=np.maximum(np.linalg.norm(normal,axis=1)[:,None],1e-12)
        shade=.62+.38*np.abs(normal[:,depth]);face_colors=(face_colors*shade[:,None]).astype(np.uint8)
        for index in order:draw.polygon([tuple(p) for p in projected[index]],fill=tuple(face_colors[index]))
    for i,(kind,label) in enumerate([('fascia','筋膜'),('aponeurosis','腱膜'),('retinaculum','支持带')]):
        x=45+i*230;draw.rectangle((x,1405,x+24,1429),fill=colors[kind]);draw.text((x+35,1401),label,font=body,fill=(40,58,58))
    draw.text((45,1453),'正交投影；浅灰皮肤仅作坐标轮廓。此图用于检查真实几何覆盖，不代表传力路线、Android 画面或医学验收。',font=small,fill=(74,85,84))
    image.save(args.output)
    report={'schemaVersion':1,'status':'generated','kind':'offline-orthographic-source-geometry-preview',
        'inputGlb':str(path.relative_to(ROOT)),'inputSha256':hashlib.sha256(raw).hexdigest(),
        'meshIds':[row[0] for row in geometry],'views':[row[0] for row in views],
        'output':str(args.output),'outputSha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),
        'limitsZh':['并非 Filament 渲染，不是 Android 真机或截图验收。','皮肤为弱化参考轮廓，不参与筋膜遮挡。']}
    (ROOT/'manifests/model-fascia-preview.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
