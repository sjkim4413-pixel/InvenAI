import json
from pathlib import Path
import sys
folder=Path(__file__).resolve().parent;project=folder.parents[1]
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
from f03_multiscale_detection import box_iou
from f03_live_ocr import under_load,rack_label_evidence,rack_labels_under_load
work=Path(sys.argv[1])
scenes=json.loads((folder/'annotations.json').read_text(encoding='utf-8'))
for scene in scenes:
    result=json.loads((work/(Path(scene['image']).stem+'.json')).read_text(encoding='utf-8'))
    labels=list(rack_label_evidence(result['ocr_tokens']))
    for expected in scene['rows']:
        row=next((r for r in result['results'] if r.get('location_label')==expected['location']),{})
        if row.get('test_label_match'):continue
        label=min(labels,key=lambda t:abs(sum(t['bbox'][::2])/2-sum(expected['label_bbox'][::2])/2)
                  +abs(sum(t['bbox'][1::2])/2-sum(expected['label_bbox'][1::2])/2))
        near=sorted(result['detections'],key=lambda d:box_iou(d['bbox'],expected['load_bbox']),reverse=True)[:3]
        print(json.dumps(dict(image=scene['image'],location=expected['location'],localized=row.get('location_verified'),
            yolo=row.get('yolo_candidates'),label_text=label['text'],label_box=label['bbox'],
            candidates=[dict(id=d['id'],conf=round(d['confidence'],3),stable=d['detection_stable'],bbox=d['bbox'],
                iou=round(box_iou(d['bbox'],expected['load_bbox']),3),under=under_load(label['bbox'],d['bbox'],printed_sticker=True),
                labels=len(rack_labels_under_load(labels,d['bbox'],printed_sticker=True))) for d in near])),flush=True)
