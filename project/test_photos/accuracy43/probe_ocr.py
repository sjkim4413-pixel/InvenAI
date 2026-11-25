"""OCR-only diagnostic; expected annotations are used for scoring, never crops."""
import io
import json
import os
from pathlib import Path
import sys
import time
import cv2
import numpy as np
from PIL import Image

folder=Path(__file__).resolve().parent
project=folder.parents[1]
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
import torch
torch.set_num_threads(4)
import easyocr
from f03_photo_observer import read_region,read_printed_rack_stickers
from f03_live_ocr import rack_readings
from f03_sticker_ocr import read_stickers,plate_candidates
reader=easyocr.Reader(['en'],gpu=False,download_enabled=False,
    model_storage_directory=str(Path(os.environ['USERPROFILE'])/'.EasyOCR/model'))
report=[]
for scene in json.loads((folder/'annotations.json').read_text(encoding='utf-8')):
    image=Image.open(folder/'prepared'/scene['image']).convert('RGB')
    stream=io.BytesIO();image.save(stream,format='JPEG',quality=95)
    pixels=cv2.imdecode(np.frombuffer(stream.getvalue(),dtype=np.uint8),cv2.IMREAD_COLOR)
    tokens=[];start=time.perf_counter()
    read_region(reader,pixels,[0,0,1,1],tokens,overview=True)
    read_printed_rack_stickers(reader,pixels,tokens)
    candidates=len(plate_candidates(pixels))
    read_stickers(reader,pixels,tokens)
    expected={row['location'] for row in scene['rows']}
    recognized=set()
    for token in tokens:
        if token['confidence']<.7: continue
        options={f"{p['rack']}-{p['tier']}F"+(f"-{p['side']}" if p['side'] else '') for p,_ in rack_readings(token['text'])}
        valid=options&expected
        if len(valid)==1: recognized.update(valid)
    result=dict(image=scene['image'],expected=len(expected),recognized=len(recognized),candidates=candidates,missing=sorted(expected-recognized),
                seconds=round(time.perf_counter()-start,2),tokens=tokens)
    report.append(result)
    print(json.dumps({k:v for k,v in result.items() if k!='tokens'}),flush=True)
(folder/'ocr_probe.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
