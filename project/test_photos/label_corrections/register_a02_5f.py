"""Register the edited asset after proving the other image pixels are unchanged."""
import csv
import hashlib
import io
import json
from pathlib import Path
from PIL import Image, ImageChops

folder=Path(__file__).resolve().parent
project=folder.parents[1]
name='276ec63e-e08f-4915-b585-582e9529bdaa.png'
photo=project/'test_image_ai'/name
metadata=json.loads((folder/'A02-5F-edit.json').read_text(encoding='utf-8-sig'))
with Image.open(folder/'276ec63e-before-A02-5F.png') as before, Image.open(photo) as after:
    assert before.size==after.size==(1086,1448)
    changed=ImageChops.difference(before.convert('RGB'),after.convert('RGB')).getbbox()
    x1,y1,x2,y2=metadata['changed_region']
    assert changed and x1<=changed[0]<changed[2]<=x2 and y1<=changed[1]<changed[3]<=y2,changed
digest=hashlib.sha256(photo.read_bytes()).hexdigest()
assert digest==metadata['edited_sha256']
for filename in ('location_map.json','visual_references.json'):
    path=project/'test_image_ai'/filename
    data=json.loads(path.read_text(encoding='utf-8'))
    entry=next(row for row in data['images'] if row.get('photo_reference',row.get('image'))==name)
    if filename=='location_map.json':
        if digest not in entry['sha256']: entry['sha256'].append(digest)
    else:
        entry['sha256']=digest
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
path=project/'test_image_ai/erp.csv'
rows=list(csv.DictReader(io.StringIO(path.read_text(encoding='utf-8-sig'))))
row=next(row for row in rows if row['ERP_왼쪽제품']=='SIM-AI01-P02')
row['사진_위치라벨']='A02-5F'
row['라벨_확인사항']=''
with path.open('w',encoding='utf-8',newline='') as stream:
    writer=csv.DictWriter(stream,fieldnames=list(rows[0]))
    writer.writeheader();writer.writerows(rows)
print(json.dumps(dict(edited_sha256=digest,changed_bbox=changed,other_pixels_unchanged=True)))
