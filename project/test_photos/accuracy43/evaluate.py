"""Measure all active reference photos with actual OCR/detectors and HTTP review."""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time

folder=Path(__file__).resolve().parent
project=folder.parents[1]
work=Path(sys.argv[1]); work.mkdir(parents=True,exist_ok=False)
snapshot_file=Path(sys.argv[2])
os.environ.update(IVENAI_UPLOAD_DIR=str(work/'uploads'),YOLO_CONFIG_DIR=str(work/'yolo'),
                  YOLO_AUTOINSTALL='false',ULTRALYTICS_OFFLINE='true')
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
import torch
torch.set_num_threads(4)
import easyocr
from fastapi.testclient import TestClient
import f03_api as api
from f03_auth import AuditUser,require_user
from f03_fixture_inventory import fixture_rack_codes
from f03_live_inventory import build_snapshot
from f03_live_ocr import rack_readings

documents=json.loads(snapshot_file.read_text(encoding='utf-8'))
stock=build_snapshot('ABC-002',documents)
assert not stock['issues'],stock['issues']
catalog=json.loads((project/'test_image_ai/test_images.json').read_text(encoding='utf-8'))
photos=[row for row in catalog['images'] if row.get('enabled',True) and (row['group']=='reference' or row.get('variant')=='normal')]
assert len(photos)==6
if '--backup' in sys.argv:
    for row in photos:
        target=folder/'originals'/row['file']
        if target.exists(): assert target.read_bytes()==(project/'test_image_ai'/row['file']).read_bytes()
        else: shutil.copy2(project/'test_image_ai'/row['file'],target)
api.app.state.live_inventory_loader=lambda company:stock
api.app.dependency_overrides[require_user]=lambda:AuditUser('six-photo-evaluation','ABC-002')
api.pipeline._reader=easyocr.Reader(['en'],gpu=False,download_enabled=False,
    model_storage_directory=str(Path(os.environ['USERPROFILE'])/'.EasyOCR/model'))
client=TestClient(api.app)
report=dict(pipeline=api.PIPELINE_VERSION,time_utc=datetime.now(timezone.utc).isoformat(),
    mode='test_rack_labels',inference_mocked=False,ocr_mocked=False,auth='local TestClient override',
    inventory_source='Proposed test inventory, not yet committed' if 'proposed' in snapshot_file.name else 'Firestore readback',
    inventory_revision=stock['revision'],photos=[])
for entry in photos:
    photo=project/'test_image_ai'/entry['file']
    start=time.perf_counter()
    uploaded=client.post('/f03/audit/upload-image',files={'image':(photo.name,photo.read_bytes(),'image/png')})
    assert uploaded.status_code==200,uploaded.text
    params=dict(filename=uploaded.json()['filename'],mode='test_rack_labels')
    response=client.post('/f03/audit/analyze-and-compare',params=params)
    assert response.status_code==200,response.text
    result=response.json()
    expected=[r for r in stock['records'] if r.get('test_photo_reference')==photo.name]
    expected_ids={r['position_id'] for r in expected}
    matched={r['position_id'] for r in result['results'] if r.get('test_label_match')}
    assert matched<=expected_ids
    assert all(not r['product_location_verified'] and not r['quantity_verified'] for r in result['results'])
    seen={r['position_id']:r for r in result['results'] if r.get('position_id')}
    for row in result['results']:
        if row.get('test_label_match'): assert row['ai_product_ids']==row['erp_product_ids'] and row['f04_status_code']==1
    (work/(photo.stem+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    defects=[]
    read_locations={f"{p['rack']}-{p['tier']}F"+(f"-{p['side']}" if p['side'] else '')
                    for t in result['ocr_tokens'] for p,_ in rack_readings(t['text']) if t['confidence']>=.7}
    for record in expected:
        if record['position_id'] in matched: continue
        row=seen.get(record['position_id'])
        defects.append(dict(document_id=record['document_id'],position_id=record['position_id'],
            label=record.get('test_photo_label'),issue=record.get('test_photo_label_issue'),
            read_label=record.get('test_photo_label') in read_locations,
            localized=row is not None,reasons=row['review_reasons'] if row else []))
    measurement=dict(image=photo.name,title=entry['title'],sha256=hashlib.sha256(photo.read_bytes()).hexdigest(),
        expected_positions=len(expected_ids),matches=len(matched),rate=round(100*len(matched)/len(expected_ids),2),
        displayed_rows=len(result['results']),unmapped_rows=sum(not r.get('position_id') for r in result['results']),
        statuses=dict(Counter(r['f04_status'] for r in result['results'])),defects=defects,seconds=round(time.perf_counter()-start,2))
    report['photos'].append(measurement)
    report.update(total=sum(p['expected_positions'] for p in report['photos']),matched=sum(p['matches'] for p in report['photos']))
    report['rate']=round(100*report['matched']/report['total'],2)
    (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in measurement.items() if k!='defects'},ensure_ascii=False),flush=True)
print('TOTAL',report['matched'],'/',report['total'],report['rate'],flush=True)
