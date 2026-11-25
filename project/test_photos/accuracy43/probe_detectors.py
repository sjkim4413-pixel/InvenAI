"""Score real model boxes; annotations only evaluate, never generate detections."""
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
backend=project/'app/src/main/java/com/example/myapp/F03_py'
scratch=Path(sys.argv[1])
os.environ.update(YOLO_CONFIG_DIR=str(scratch/'yolo-probe'),YOLO_AUTOINSTALL='false',ULTRALYTICS_OFFLINE='true')
sys.path.insert(0,str(backend))
import torch
torch.set_num_threads(4)
from ultralytics import YOLO
from f03_multiscale_detection import predict_boxes,fuse_predictions,box_iou
models={'main':YOLO(str(backend/'runs/detect/runs/pallet_v3/warehouse_v3/weights/best.pt')),
        'shelf':YOLO(str(backend/'runs/detect/shelf_v37/weights/best.pt'))}
if len(sys.argv)>2:
    models={Path(path).stem:YOLO(path) for path in sys.argv[2:]}
report=[]
for scene in json.loads((folder/'annotations.json').read_text(encoding='utf-8')):
    stream=io.BytesIO();Image.open(folder/'prepared'/scene['image']).convert('RGB').save(stream,format='JPEG',quality=95)
    pixels=cv2.imdecode(np.frombuffer(stream.getvalue(),dtype=np.uint8),cv2.IMREAD_COLOR)
    result=dict(image=scene['image'],expected=len(scene['rows']))
    for key,model in models.items():
        start=time.perf_counter()
        predictions=[p for scale in (640,1024,1280) for p in predict_boxes(model,pixels,scale,nms_iou=.55)]
        detections=fuse_predictions(predictions)
        strong=[d for d in detections if d['confidence']>=.5 and d['detection_stable']]
        result[key]=dict(recalled=sum(any(box_iou(row['load_bbox'],d['bbox'])>=.45 for d in strong) for row in scene['rows']),
            raw_recall=sum(any(box_iou(row['load_bbox'],d['bbox'])>=.45 for d in detections) for row in scene['rows']),
            strong=len(strong),detections=detections,seconds=round(time.perf_counter()-start,2))
    report.append(result)
    print(json.dumps(dict(image=scene['image'],expected=result['expected'],
        models={k:{field:v for field,v in result[k].items() if field!='detections'} for k in models})),flush=True)
(folder/'detector_probe.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
