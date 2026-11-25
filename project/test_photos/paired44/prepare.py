"""Register six AI-edited counterparts; preserve the six verified originals."""
import hashlib
import json
from pathlib import Path
import shutil
from PIL import Image, ImageDraw, ImageFont

folder=Path(__file__).resolve().parent
project=folder.parents[1]
assets=project/'test_image_ai'
manifest=json.loads((folder/'generation_manifest.json').read_text(encoding='utf-8'))
annotations=json.loads((project/'test_photos/accuracy43/annotations.json').read_text(encoding='utf-8'))
documents=dict(json.loads((project/'test_photos/accuracy43/inventory_readback.json').read_text(encoding='utf-8')))
names=['생활용품 A 정면','생활용품 B 정면','생활용품 C 전경','가전제품 정면','장난감 정면','포장 팔레트 사선']
swaps=[['A01-4F','A03-4F'],['A01-1F','A02-1F'],['A01-5F','A02-5F'],
       ['QAE01-1F-01','QAE02-1F-01'],['QAF01-2F-02','QAF02-2F-01'],['QAG01-2F-01','QAG01-2F-02']]
old=json.loads((assets/'test_images.json').read_text(encoding='utf-8'))
profile=dict(version=1,company_code='ABC-002',pairs=[])
catalog=[]; truth=[]
(folder/'raw').mkdir(exist_ok=True)
for index,(generation,description,swap) in enumerate(zip(manifest['pairs'],names,swaps),1):
    scene=next(row for row in annotations if row['image']==generation['file'])
    original=assets/scene['image']
    assert hashlib.sha256(original.read_bytes()).hexdigest()==scene['sha256']
    raw=folder/'raw'/f'pair_{index:02}_misplaced.png'
    if not raw.exists():shutil.copy2(generation['source'],raw)
    # Retain the product edit; restore exact rack text with the already-authorized label generator.
    image=Image.open(raw).convert('RGB');draw=ImageDraw.Draw(image)
    width,height=image.size
    for row in scene['rows']:
        x1,y1,x2,y2=[round(v*d) for v,d in zip(row['label_bbox'],(width,height,width,height))]
        pad=max(2,round(width/500))
        draw.rectangle((x1-pad,y1-pad,x2+pad,y2+pad),fill='white',outline='black',width=1)
        size=max(10,round(row['font_size']*width/scene['width']))
        while True:
            font=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',size)
            box=draw.textbbox((0,0),row['location'],font=font)
            if box[2]-box[0]<=x2-x1-2 or size<=8:break
            size-=1
        draw.text(((x1+x2-box[2]-box[0])/2,(y1+y2-box[3]-box[1])/2),row['location'],font=font,fill='black')
    edited=f'pair_{index:02}_misplaced.png';image.save(assets/edited)
    rows=[dict(location=row['location'],sku=row['itemCode'],bbox=row['load_bbox']) for row in scene['rows']]
    warehouse=documents[scene['rows'][0]['document_id']]['warehouse']
    photos={scene['image']:scene['sha256'],edited:hashlib.sha256((assets/edited).read_bytes()).hexdigest()}
    profile['pairs'].append(dict(id=f'pair_{index:02}',reference=scene['image'],reference_sha256=scene['sha256'],
        warehouse=warehouse,photos=photos,regions=rows))
    for offset,(file,variant) in enumerate([(scene['image'],'normal'),(edited,'misplaced')]):
        title=f'테스트 {2*index-1+offset:02} · {description} · '+('정상' if variant=='normal' else '오적재')
        catalog.append(dict(file=file,title=title,group='paired',mode='test_reference_pairs',warehouseCode=warehouse,
                            pairId=f'pair_{index:02}',variant=variant))
    truth.append(dict(pair_id=f'pair_{index:02}',normal=scene['image'],misplaced=edited,swapped_locations=swap,
                      expected_positions=len(rows),prompt=generation['change']))
active_files={row['file'] for row in catalog}
retired=project/'test_photos/comparison/retired';retired.mkdir(exist_ok=True)
for file in ('compare_01_normal.png','compare_02_misplaced.png'):
    source=assets/file
    if source.exists():
        destination=retired/file
        if destination.exists():assert destination.read_bytes()==source.read_bytes();source.unlink()
        else:source.replace(destination)
catalog += [dict(row,enabled=False) for row in old['images'] if row['file'] not in active_files
            and row['file'] not in ('compare_01_normal.png','compare_02_misplaced.png')]
for path,data in [(assets/'test_images.json',dict(version=1,images=catalog)),(assets/'reference_pairs.json',profile),
                  (folder/'edit_ground_truth.json',truth)]:
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(active_photos=12,pairs=6,reference_positions=sum(len(p['regions']) for p in profile['pairs']))))
