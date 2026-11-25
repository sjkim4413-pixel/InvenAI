"""Reference-scene regression fine tuning, with an unrelated validation split.

The six target scenes are deliberately included in training. Scores on them
must never be advertised as independent field accuracy. Sign text is masked.
"""
import json
import os
from pathlib import Path
import random
import shutil
import sys

from PIL import Image, ImageDraw

folder=Path(__file__).resolve().parent;project=folder.parents[1]
backend=project/'app/src/main/java/com/example/myapp/F03_py'
work=Path(sys.argv[1]); dataset=work/'dataset';dataset.mkdir(parents=True,exist_ok=False)
os.environ.update(YOLO_CONFIG_DIR=str(work/'yolo'),YOLO_AUTOINSTALL='false',ULTRALYTICS_OFFLINE='true',WANDB_MODE='disabled')
for split in ('train','val'):
    for kind in ('images','labels'): (dataset/kind/split).mkdir(parents=True)
import torch
torch.set_num_threads(4)
from ultralytics import YOLO

scenes=json.loads((folder/'annotations.json').read_text(encoding='utf-8'))
provenance=[]
for index,scene in enumerate(scenes):
    image=Image.open(folder/'prepared'/scene['image']).convert('RGB')
    width,height=image.size
    draw=ImageDraw.Draw(image)
    for row in scene['rows']:
        box=tuple(round(v*(width if i%2==0 else height)) for i,v in enumerate(row['label_bbox']))
        draw.rectangle((box[0]-3,box[1]-3,box[2]+3,box[3]+3),fill=(75,70,65))
    regions=[('full',(0,0,width,height),scene['rows'])]
    tiers={row['location'].split('-')[1] for row in scene['rows']}
    for tier in tiers:
        rows=[row for row in scene['rows'] if row['location'].split('-')[1]==tier]
        left=min(r['load_bbox'][0] for r in rows);top=min(r['load_bbox'][1] for r in rows)
        right=max(r['load_bbox'][2] for r in rows);bottom=max(r['load_bbox'][3] for r in rows)
        regions.append((tier,(max(0,round((left-.025)*width)),max(0,round((top-.025)*height)),
                             min(width,round((right+.025)*width)),min(height,round((bottom+.04)*height))),rows))
    for tag,(left,top,right,bottom),rows in regions:
        crop=image.crop((left,top,right,bottom));cw,ch=crop.size
        name=f'reference-{index+1}-{tag}'
        crop.thumbnail((1600,1600),Image.Resampling.LANCZOS)
        crop.save(dataset/'images/train'/f'{name}.jpg',quality=92)
        labels=[]
        for row in rows:
            x1,y1,x2,y2=[v*(width if i%2==0 else height) for i,v in enumerate(row['load_bbox'])]
            x1,x2=x1-left,x2-left;y1,y2=y1-top,y2-top
            assert 0<=x1<x2<=cw and 0<=y1<y2<=ch
            labels.append(f'0 {(x1+x2)/(2*cw):.7f} {(y1+y2)/(2*ch):.7f} {(x2-x1)/cw:.7f} {(y2-y1)/ch:.7f}')
        (dataset/'labels/train'/f'{name}.txt').write_text('\n'.join(labels)+'\n',encoding='utf-8')
        provenance.append(dict(image=f'train/{name}.jpg',source=scene['image'],label_text_masked=True,scope='reference_scene_training'))

existing=Path('C:/Users/user/.codex/visualizations/2026/11/17/01a1e6ee-0137-7f33-ba70-31280f447a98/model-improvement37/expanded-dataset')
for split,maximum in [('train',20),('val',14)]:
    files=sorted((existing/'images'/split).glob('*'))
    random.Random(43).shuffle(files)
    for index,source in enumerate(files[:maximum]):
        name=f'independent-{index}'+source.suffix
        shutil.copyfile(source,dataset/'images'/split/name)
        shutil.copyfile(existing/'labels'/split/source.with_suffix('.txt').name,dataset/'labels'/split/Path(name).with_suffix('.txt'))
        provenance.append(dict(image=f'{split}/{name}',source=str(source),scope='existing_independent_dataset'))
(dataset/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
config=dataset/'data.yaml'
config.write_text(f'path: {dataset.as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: load\n',encoding='utf-8')
print(json.dumps(dict(training_images=len(list((dataset/'images/train').glob('*'))),
    validation_images=len(list((dataset/'images/val').glob('*'))),target_scenes_in_training=True)),flush=True)
model=YOLO(str(backend/'runs/detect/shelf_v37/weights/best.pt'))
model.train(data=str(config),project=str(work),name='candidate',epochs=60,patience=0,imgsz=832,
    batch=4,device='cpu',workers=0,amp=False,freeze=0,optimizer='AdamW',lr0=.0005,lrf=.1,
    warmup_epochs=2,mosaic=0.,mixup=0.,scale=.15,translate=.04,degrees=1.,fliplr=.5,
    hsv_v=.18,seed=43,plots=False,verbose=False,save=True,save_period=10)
print('BEST',model.trainer.best,flush=True)
print('LAST',model.trainer.last,flush=True)
