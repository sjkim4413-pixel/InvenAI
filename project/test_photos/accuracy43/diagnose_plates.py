import io
import json
import os
from pathlib import Path
import sys
import cv2
import numpy as np
from PIL import Image
folder=Path(__file__).resolve().parent
project=folder.parents[1]
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
import torch
torch.set_num_threads(4)
import easyocr
from f03_sticker_ocr import plate_candidates,read_structured_plate
reader=easyocr.Reader(['en'],gpu=False,download_enabled=False,
    model_storage_directory=str(Path(os.environ['USERPROFILE'])/'.EasyOCR/model'))
annotations=json.loads((folder/'annotations.json').read_text(encoding='utf-8'))
probe=json.loads((folder/'ocr_probe.json').read_text(encoding='utf-8'))
for scene in annotations:
    record=next(row for row in probe if row['image']==scene['image'])
    stream=io.BytesIO();Image.open(folder/'prepared'/scene['image']).convert('RGB').save(stream,format='JPEG',quality=95)
    image=cv2.imdecode(np.frombuffer(stream.getvalue(),np.uint8),cv2.IMREAD_COLOR)
    candidates=plate_candidates(image)
    for missing in record['missing']:
        row=next(r for r in scene['rows'] if r['location']==missing)
        bbox=row['label_bbox'];cx=(bbox[0]+bbox[2])/2;cy=(bbox[1]+bbox[3])/2
        _,bounds,patch=min(candidates,key=lambda c:abs((c[1][0]+c[1][2])/2-cx)+abs((c[1][1]+c[1][3])/2-cy))
        patch=cv2.resize(patch,None,fx=44/patch.shape[0],fy=44/patch.shape[0],interpolation=cv2.INTER_CUBIC)
        trace={};result=read_structured_plate(reader,patch,trace)
        print(json.dumps(dict(image=scene['image'],expected_for_diagnostic_only=missing,result=result,trace=trace)),flush=True)
