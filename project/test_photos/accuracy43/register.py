"""Promote inspected label assets and prepare a reversible test-inventory patch."""
import csv
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys

folder=Path(__file__).resolve().parent; project=folder.parents[1]
scratch=Path(sys.argv[1])
scenes=json.loads((folder/'annotations.json').read_text(encoding='utf-8'))
plan=json.loads((folder/'inventory_plan.json').read_text(encoding='utf-8'))
snapshot=json.loads((scratch/'inventory-before.json').read_text(encoding='utf-8'))
by_id={r['id']:r for r in plan['records']}
proposed=[(doc_id,{**data,**by_id[doc_id]['patch']} if doc_id in by_id else data) for doc_id,data in snapshot]
(scratch/'inventory-proposed.json').write_text(json.dumps(proposed,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def read(name): return json.loads((project/name).read_text(encoding='utf-8'))
def write(name,data): (project/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def write_asset(path,content):
    staged=path.with_name(path.name+'.pending')
    staged.write_bytes(content)
    staged.replace(path)
mapping=read('test_image_ai/location_map.json');visual=read('test_image_ai/visual_references.json')
extra=read('test_photos/rack_labelled/extra_annotations.json');manifest=read('test_image_ai/rack_labelled_photos.json')
extra_plan=read('test_photos/rack_labelled/extra_inventory_plan.json')
for scene in scenes:
    name=scene['image'];content=(folder/'prepared'/name).read_bytes()
    assert len(content)<=16*1024*1024 and hashlib.sha256(content).hexdigest()==scene['sha256']
    write_asset(project/'test_image_ai'/name,content)
    legacy=next((s for s in mapping['images'] if s['photo_reference']==name),None)
    if legacy:
        if scene['sha256'] not in legacy['sha256']: legacy['sha256'].append(scene['sha256'])
        next(s for s in visual['images'] if s['image']==name)['sha256']=scene['sha256']
    else:
        manifest[name]=scene['sha256']
        write_asset(project/'test_photos/rack_labelled'/name,content)
        old=next(s for s in extra if s['image']==name)
        old['imageSha256']=scene['sha256']
        for target in old['rows']:
            row=next(r for r in scene['rows'] if r['itemCode']==target['itemCode'])
            target['rackBBox']=row['label_bbox']
        for record in extra_plan['records']:
            if record['data']['photoReference']==name: record['data'].update(by_id[record['id']]['patch'])
write('test_image_ai/location_map.json',mapping);write('test_image_ai/visual_references.json',visual)
write('test_image_ai/rack_labelled_photos.json',manifest)
write('test_photos/rack_labelled/extra_annotations.json',extra)
write('test_photos/rack_labelled/extra_inventory_plan.json',extra_plan)
path=project/'test_image_ai/erp.csv'
rows=list(csv.DictReader(io.StringIO(path.read_text(encoding='utf-8-sig'))))
for row in rows:
    target=by_id.get('ABC-002_'+row['ERP_왼쪽제품'])
    if target:
        row['사진_위치라벨']=target['patch']['photoLocationLabel'];row['라벨_확인사항']=''
with path.open('w',encoding='utf-8',newline='') as stream:
    writer=csv.DictWriter(stream,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
print(json.dumps(dict(photos=len(scenes),proposed_inventory_updates=len(by_id),company_records=len(proposed))))
