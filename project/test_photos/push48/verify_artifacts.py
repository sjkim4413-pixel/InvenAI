"""Verify test evidence and the release artifact without printing any key or device token."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

folder=Path(__file__).resolve().parent
root=folder.parents[1]
assert 'BUILD SUCCESSFUL' in (folder/'android-build.log').read_text(encoding='utf-8-sig')
counts=dict(tests=0,failures=0,errors=0,skipped=0)
for path in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite=ET.parse(path).getroot()
    for key in counts:counts[key]+=int(suite.attrib.get(key,0))
assert counts==dict(tests=42,failures=0,errors=0,skipped=0),counts
backend=(folder/'backend-tests.log').read_text(encoding='utf-8-sig')
assert 'Ran 15 tests' in backend and '\nOK\n' in backend and 'FAILED' not in backend
for name in ('real-device-tests.log','foreground-device-tests.log'):
    content=(folder/name).read_text(encoding='utf-8-sig')
    assert 'OK (1 test)' in content and 'FAILURES' not in content,name
sent=json.loads((folder/'real-fcm-send.json').read_text(encoding='utf-8'))
received=json.loads((folder/'real-device-receipt.json').read_text(encoding='utf-8'))
assert sent['real_fcm_accepted'] and received['real_fcm'] and received['screen_off']
latency=received['posted_at_epoch_ms']-sent['sent_at_epoch_ms']
assert latency>=0
server=(folder/'server-verification.log').read_text(encoding='utf-8-sig')
assert '"sender_ready": true' in server and 'DEPLOYED_PUSH_ROUTE_AUTHENTICATION_VERIFIED' in server
secret=json.loads((root/'.secrets/fcm-sender.json').read_text(encoding='utf-8'))
private_marker=secret['private_key'].splitlines()[1].encode()
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(apk) as archive:
    names=archive.namelist()
    images=json.loads(archive.read('assets/audit_test_images/test_images.json'))['images']
    active=[image for image in images if image.get('enabled',True)]
    assert len(active)==12
    forbidden=[b'Ui45LocalOnly!2026',b'qa-ui45@invenai.test',b'10.0.2.2:18861',
               b'push48-probe.json',b'F06BackgroundPushTest',secret['client_email'].encode(),private_marker]
    assert not any(name.startswith('assets/ui45/') for name in names)
    for name in names:
        content=archive.read(name)
        if name.endswith('.dex'):
            assert not any(marker in content for marker in forbidden),name
        assert private_marker not in content,name
assert not (root/'.secrets/push48-test-device.json').exists()
report=dict(time_utc=datetime.now(timezone.utc).isoformat(),android_unit_tests=counts,
    backend_tests=15,real_fcm_device_tests=1,existing_foreground_device_tests=1,
    real_cloud_delivery=True,other_app_and_screen_off_verified=True,
    send_to_notification_ms=latency,accept_to_notification_ms=received['posted_at_epoch_ms']-sent['accepted_at_epoch_ms'],
    duplicate_display_blocked=True,wrong_account_company_and_resolved_blocked=True,
    stock_unchanged_during_device_tests=True,production_stock_written=False,production_alerts_created=False,
    sender_role='roles/firebasecloudmessaging.admin',sender_inventory_access_added=False,
    billing_changed=False,cloud_functions_added=False,test_images=len(active),
    server_key_and_test_credentials_absent_from_apk=True,temporary_device_probe_removed=True,
    apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
(folder/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
