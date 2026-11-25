"""Send only the one instrumented emulator's opaque test event; never log credentials or its token."""
import json
import sys
import time
import hashlib
from pathlib import Path

folder = Path(__file__).resolve().parent
root = folder.parents[1]
sys.path.insert(0, str(root/'app/src/main/java/com/example/myapp/F03_py'))
from f06_push import FcmSender

probe = root/'.secrets/push48-test-device.json'
try:
    payload = json.loads(probe.read_text(encoding='utf-8-sig'))
    data = payload['data']
    assert data['type'] == 'invenai_anomaly' and data['kind'] == 'audit'
    assert data['company_code'] == 'ABC-002' and data['event_id'] == hashlib.sha256(data['alert_id'].encode()).hexdigest()
    assert data['alert_id'].startswith('AI_') and len(data['alert_id']) == 67
    sender = FcmSender()
    assert sender.project == 'warehouseqa-8c262' and sender.ready
    sent_at = int(time.time()*1000)
    sender.send(payload['token'], data)
    report = dict(real_fcm_accepted=True, target='instrumented emulator only',
                  notification_body_contains_stock_details=False,
                  sent_at_epoch_ms=sent_at, accepted_at_epoch_ms=int(time.time()*1000))
    (folder/'real-fcm-send.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))
finally:
    probe.unlink(missing_ok=True)
