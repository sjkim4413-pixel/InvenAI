"""Exercise real uploaded pixels; auth is local, inventory is a saved real readback."""
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

folder=Path(__file__).resolve().parent;project=folder.parents[1]
output=Path(sys.argv[1]);output.mkdir(parents=True,exist_ok=False)
snapshot=Path(sys.argv[2]) if len(sys.argv)>2 else project/'test_photos/accuracy43/inventory_readback.json'
os.environ['IVENAI_UPLOAD_DIR']=str(output/'uploads')
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
from fastapi.testclient import TestClient
import f03_api as api
from f03_auth import AuditUser,require_user
from f03_live_inventory import build_snapshot

stock=build_snapshot('ABC-002',json.loads(snapshot.read_text(encoding='utf-8')))
assert not stock['issues']
api.app.state.live_inventory_loader=lambda company:stock
api.app.dependency_overrides[require_user]=lambda:AuditUser('reference-pairs-evaluation','ABC-002')
client=TestClient(api.app)
catalog=json.loads((project/'test_image_ai/test_images.json').read_text(encoding='utf-8'))
truth={row['pair_id']:row for row in json.loads((folder/'edit_ground_truth.json').read_text(encoding='utf-8'))}
report=dict(pipeline=api.PIPELINE_VERSION,time_utc=datetime.now(timezone.utc).isoformat(),
    actual_pixel_comparison=True,auth='local TestClient override',inventory_source='saved Firestore readback',
    method='human-annotated reference appearance; no OCR/YOLO claim',photos=[])
for entry in catalog['images']:
    if not entry.get('enabled',True):continue
    path=project/'test_image_ai'/entry['file']
    uploaded=client.post('/f03/audit/upload-image',files={'image':(path.name,path.read_bytes(),'image/png')})
    assert uploaded.status_code==200,uploaded.text
    params=dict(filename=uploaded.json()['filename'],mode='test_reference_pairs',warehouse_code=entry['warehouseCode'])
    response=client.post('/f03/audit/analyze-and-compare',params=params)
    assert response.status_code==200,response.text
    result=response.json()
    (output/(path.stem+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    statuses=Counter(row['f04_status_code'] for row in result['results'])
    exceptional=[dict(status=row['f04_status_code'],**row['reference_evidence'])
                 for row in result['results'] if row['f04_status_code']!=1]
    wanted=set(truth[entry['pairId']]['swapped_locations']) if entry['variant']=='misplaced' else set()
    actual={row['location_label'] for row in result['results'] if row['f04_status_code']!=1}
    item=dict(file=entry['file'],title=entry['title'],pair=entry['pairId'],variant=entry['variant'],
              positions=len(result['results']),matched=statuses[1],misplaced=statuses[2],review=statuses[5],
              expected_changed_locations=sorted(wanted),changed_locations=sorted(actual),
              expected_changes_flagged=wanted<=actual,exceptional=exceptional)
    assert all(not row['verification_passed'] and not row['product_location_verified'] and not row['quantity_verified'] for row in result['results'])
    report['photos'].append(item)
    (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({key:value for key,value in item.items() if key!='exceptional'},ensure_ascii=False),flush=True)
print('Complete',flush=True)
