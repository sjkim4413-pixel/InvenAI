"""Check final real readback, cache freshness and negative ERP controls.

Only this local TestClient overrides auth/inventory. Negative states never
write to Firestore. Original pixel inference is reused, never mocked as normal.
"""
import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
from unittest.mock import patch
from PIL import Image

folder=Path(__file__).resolve().parent;project=folder.parents[1]
scratch=Path(sys.argv[1]);work=scratch/'final-readback'
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
from fastapi.testclient import TestClient
import f03_api as api
from f03_auth import AuditUser,require_user
from f03_live_inventory import build_snapshot

documents=json.loads((scratch/'inventory-final.json').read_text(encoding='utf-8'))
source=build_snapshot('ABC-002',documents)
assert not source['issues']
report=json.loads((work/'report.json').read_text(encoding='utf-8'))
assert report['matched']==report['total']==109 and len(report['photos'])==6
assert report['inventory_source']=='Firestore readback'
weights=project/'app/src/main/java/com/example/myapp/F03_py/runs/detect/shelf_test_v43/weights/best.pt'
weight_digest=hashlib.sha256(weights.read_bytes()).hexdigest()
api.app.state.upload_dir=work/'uploads'
api.app.state.live_inventory_loader=lambda company:source
api.app.dependency_overrides[require_user]=lambda:AuditUser('six-photo-evaluation','ABC-002')
client=TestClient(api.app)
saved={}
for entry in report['photos']:
    result=json.loads((work/(Path(entry['image']).stem+'.json')).read_text(encoding='utf-8'))
    assert result['revision']['test_shelf_model_sha256']==weight_digest
    assert result['total_positions']==entry['expected_positions']==len(result['results'])
    assert all(r['test_label_match'] and not r['product_location_verified'] and not r['quantity_verified']
               and r['location_method']=='ocr_rack_label' for r in result['results'])
    detections={d['id']:d for d in result['detections']}
    for row in result['results']:
        actual=detections[row['verified_detection_id']]
        assert actual['detection_stable'] and len(actual['scale_predictions'])>=2
        assert row['ai_product_ids']==row['erp_product_ids']==[row['location_label']]
    params=dict(filename=result['filename'],mode='test_rack_labels')
    with patch.object(api.pipeline,'analyze_image',side_effect=AssertionError('Expected real OCR cache reuse')):
        review=client.post('/f04/audit/review-job',params=params)
    assert review.status_code==200 and all(r['test_label_match'] for r in review.json()['results'])
    assert client.get('/f03/audit/review-results',params={**params,'mode':'live'}).status_code==404
    target=project/'app/src/androidTest/assets/accuracy43'/f"{Path(entry['image']).stem}.json"
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    saved[entry['image']]=(result,params)

result,params=saved['276ec63e-e08f-4915-b585-582e9529bdaa.png']
control=[]
for name,changes in [('zero_quantity',dict(quantity=0)),('anomaly_status',dict(status='ANOMALY')),
                     ('wrong_registered_location',dict(location='Z99-5F',rack='Z99',rackId='AI01구역 Z99',
                                                       tier='5단',photoLocationLabel='Z99-5F'))]:
    changed=copy.deepcopy(documents)
    record=next(data for doc_id,data in changed if doc_id=='ABC-002_SIM-AI01-P02')
    record.update(changes)
    source=build_snapshot('ABC-002',changed)
    assert not source['issues'],source['issues']
    with patch.object(api.pipeline,'analyze_image',side_effect=AssertionError('ERP changes must not synthesize fresh pixels')):
        response=client.post('/f04/audit/review-job',params=params)
    assert response.status_code==200,response.text
    rows=response.json()['results'];matched=sum(bool(r['test_label_match']) for r in rows)
    assert len(rows)==20 and matched==19,(name,matched)
    control.append(dict(case=name,matched=matched,total=20,ocr_reused=True,firestore_written=False))
source=build_snapshot('ABC-002',documents)
response=client.post('/f04/audit/review-job',params=params)
assert response.status_code==200 and all(r['test_label_match'] for r in response.json()['results'])
stream=io.BytesIO();Image.new('RGB',(200,200),'white').save(stream,format='PNG')
uploaded=client.post('/f03/audit/upload-image',files={'image':('unregistered.png',stream.getvalue(),'image/png')})
assert uploaded.status_code==200
with patch.object(api.pipeline,'analyze_image',side_effect=AssertionError('Unknown image must not get fixture answers')):
    unknown=client.post('/f03/audit/analyze-and-compare',params=dict(filename=uploaded.json()['filename'],mode='test_rack_labels'))
assert unknown.status_code==422,unknown.text
api.app.dependency_overrides.clear()
assert client.get('/f03/audit/review-results',params=params).status_code==401
summary=dict(photos=6,matched=109,total=109,rate=100.0,weights_sha256=weight_digest,
             inventory_records=source['source_count'],negative_controls=control,unknown_photo_status=422,unauthenticated_status=401,
             scope='Curated reference scenes included in detector training; label/location test only; quantity unverified')
(folder/'release_verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
shutil.copy2(work/'report.json',folder/'final_report.json')
print(json.dumps(summary,ensure_ascii=False))
