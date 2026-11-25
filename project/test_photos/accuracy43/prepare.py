"""Rebuild precise, unique test labels; annotations are not inference inputs."""
import copy
import csv
import hashlib
import io
import json
from pathlib import Path
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

folder=Path(__file__).resolve().parent
project=folder.parents[1]
scratch=Path(sys.argv[1])
documents=json.loads((scratch/'inventory-before.json').read_text(encoding='utf-8'))
mapping=json.loads((project/'test_image_ai/location_map.json').read_text(encoding='utf-8'))
extra=json.loads((project/'test_photos/rack_labelled/extra_annotations.json').read_text(encoding='utf-8'))
catalog=json.loads((project/'test_image_ai/test_images.json').read_text(encoding='utf-8'))
active=[r for r in catalog['images'] if r.get('enabled',True) and r['group']=='reference']
font_path='C:/Windows/Fonts/arialbd.ttf'
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
from f03_live_ocr import rack_readings

def label_rectangles(image):
    gray=cv2.cvtColor(np.array(image),cv2.COLOR_RGB2GRAY)
    count,_,stats,_=cv2.connectedComponentsWithStats(cv2.inRange(gray,215,255))
    height,width=gray.shape
    return [(int(x-2),int(y-2),int(x+w+2),int(y+h+2)) for x,y,w,h,area in stats[1:count]
            if .018*width<w<.24*width and .005*height<h<.035*height
            and 1.7<w/h<10 and area/(w*h)>.40]

plan=[]; scenes=[]
for entry in active:
    name=entry['file']; original=folder/'originals'/name
    image=Image.open(original).convert('RGB'); width,height=image.size
    records=[(doc_id,data) for doc_id,data in documents if data.get('photoReference')==name]
    legacy=next((s for s in mapping['images'] if s['photo_reference']==name),None)
    extra_scene=next((s for s in extra if s['image']==name),None)
    rows=[]
    for doc_id,data in records:
        if legacy:
            region=next(r for r in legacy['regions'] if r['rack']==data['fixtureRack'])
            rows.append(dict(document_id=doc_id,data=data,bbox=region['bbox'],floor=region['floor']))
        else:
            region=next(r for r in extra_scene['rows'] if r['itemCode']==data['itemCode'])
            rows.append(dict(document_id=doc_id,data=data,bbox=region['loadBBox'],floor=int(data['fixtureFloor']) if 'fixtureFloor' in data
                             else int(data['tier'].removesuffix('단').removesuffix('F')),
                             old_label_bbox=region['rackBBox']))
    for floor in {r['floor'] for r in rows}:
        ordered=sorted((r for r in rows if r['floor']==floor),key=lambda r:(r['bbox'][0]+r['bbox'][2])/2)
        for column,row in enumerate(ordered,1):
            row['location']=f'A{column:02}-{floor}F' if legacy else row['data']['photoLocationLabel']
    candidates=label_rectangles(image); used=set()
    baseline=json.loads((scratch/'baseline'/(Path(name).stem+'.json')).read_text(encoding='utf-8'))
    old_tokens=[]
    for token in baseline['ocr_tokens']:
        codes={f"{p['rack']}-{p['tier']}F"+(f"-{p['side']}" if p['side'] else '') for p,_ in rack_readings(token['text'])}
        if codes: old_tokens.append((token,codes))
    for row in rows:
        x1,y1,x2,y2=row['bbox']; cx=(x1+x2)/2*width; cy=(y2-.004)*height
        if legacy:
            known=[t for t,codes in old_tokens if row['data']['photoLocationLabel'] in codes
                   and abs((t['bbox'][0]+t['bbox'][2])/2*width-cx)<max((x2-x1)*width*.6,.035*width)
                   and abs((t['bbox'][1]+t['bbox'][3])/2*height-cy)<.03*height]
            nearby=[(i,box) for i,box in enumerate(candidates) if i not in used
                    and abs((box[0]+box[2])/2-cx)<max((x2-x1)*width*.60,.035*width)
                    and abs((box[1]+box[3])/2-cy)<.026*height]
            if known:
                token=min(known,key=lambda t:abs((t['bbox'][0]+t['bbox'][2])/2*width-cx)/max(1,(x2-x1)*width)
                          +abs((t['bbox'][1]+t['bbox'][3])/2*height-cy)/(.018*height))
                old=tuple(round(v*(width if i%2==0 else height))+(-4 if i<2 else 4) for i,v in enumerate(token['bbox']))
                cx,cy=(old[0]+old[2])/2,(old[1]+old[3])/2
                for index,box in nearby:
                    if box[0]<=cx<=box[2] and box[1]<=cy<=box[3]: used.add(index)
            elif nearby:
                index,old=min(nearby,key=lambda item:abs((item[1][0]+item[1][2])/2-cx)/max(1,(x2-x1)*width)
                              +abs((item[1][1]+item[1][3])/2-cy)/(.018*height))
                used.add(index); cx,cy=(old[0]+old[2])/2,(old[1]+old[3])/2
            else:
                old=(round(cx-25),round(cy-10),round(cx+25),round(cy+10))
        else:
            old=tuple(round(v*(width if i%2==0 else height)) for i,v in enumerate(row['old_label_bbox']))
            cx,cy=(old[0]+old[2])/2,(old[1]+old[3])/2
        row['old_rect']=old; row['center']=[cx,cy]
    draw=ImageDraw.Draw(image)
    for row in rows:
        cx,cy=row['center']; code=row['location']; old=row['old_rect']
        spacing=min((abs(other['center'][0]-cx) for other in rows
                     if other is not row and other['floor']==row['floor']),default=width)
        available=min(width-4,.90*spacing)
        size=round(min(width,height)/48) if legacy else 34
        tracking=0 if legacy else 3
        while True:
            font=ImageFont.truetype(font_path,size)
            bounds=draw.textbbox((0,0),code,font=font)
            text_width=bounds[2]-bounds[0] if not tracking else sum(draw.textlength(char,font=font) for char in code)+tracking*(len(code)-1)
            bw=round(text_width)+14; bh=bounds[3]-bounds[1]+12
            if bw<=available or size<=16: break
            size-=1
        bw=max(bw,old[2]-old[0]); bh=max(bh,old[3]-old[1])
        left=max(2,min(round(cx-bw/2),width-bw-2)); top=max(2,min(round(cy-bh/2),height-bh-2))
        box=(left,top,left+bw,top+bh)
        # Cover the original sticker's characters before drawing the new plate.
        draw.rectangle(old,fill='white')
        draw.rounded_rectangle(box,radius=3,fill='white',outline='#111111',width=2)
        text_x=left+(bw-text_width)/2
        text_y=top+(bh-(bounds[3]-bounds[1]))/2-bounds[1]
        if tracking:
            for character in code:
                draw.text((text_x,text_y),character,font=font,fill='black')
                text_x+=draw.textlength(character,font=font)+tracking
        else:
            draw.text((text_x-bounds[0],text_y),code,font=font,fill='black')
        row['label_bbox']=[round(v/(width if i%2==0 else height),6) for i,v in enumerate(box)]
        body=list(row['bbox'])
        # The visible pallet/support belongs to the load. Earlier annotations
        # sometimes stopped at the cartons well above the physical pallet.
        if box[1]/height>body[1]+.25*(body[3]-body[1]): body[3]=box[1]/height
        row['load_bbox']=body
        row['font_size']=size
    output=folder/'prepared'; output.mkdir(exist_ok=True)
    image.save(output/name,format='PNG')
    sha=hashlib.sha256((output/name).read_bytes()).hexdigest()
    scene=dict(image=name,width=width,height=height,sha256=sha,source_sha256=hashlib.sha256(original.read_bytes()).hexdigest(),rows=[])
    for row in rows:
        old=row['data']; location=row['location']; rack,tier,*side=location.split('-')
        patch=dict(photoLocationLabel=location,photoLabelIssue='',photoSha256=sha,location=location,
                   rack=rack,rackId=(old.get('zone','')+' '+rack).strip(),tier=tier.removesuffix('F')+'단')
        if side: patch['side']=side[0]
        plan.append(dict(id=row['document_id'],before=old,patch=patch))
        scene['rows'].append(dict(document_id=row['document_id'],itemCode=old['itemCode'],location=location,
            label_bbox=row['label_bbox'],load_bbox=row['load_bbox'],font_size=row['font_size'],original_label=old['photoLocationLabel'],
            original_issue=old['photoLabelIssue']))
    scenes.append(scene)
assert len(plan)==109 and len({r['id'] for r in plan})==109
for scene in scenes:
    assert len({r['location'] for r in scene['rows']})==len(scene['rows'])
(folder/'annotations.json').write_text(json.dumps(scenes,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(folder/'inventory_plan.json').write_text(json.dumps(dict(project='warehouseqa-8c262',company='ABC-002',records=plan),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps([dict(image=s['image'],positions=len(s['rows']),min_font=min(r['font_size'] for r in s['rows'])) for s in scenes]))
