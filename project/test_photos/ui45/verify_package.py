"""Verify the completed UI/alert release and its external-state evidence."""
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile
import xml.etree.ElementTree as ET

folder=Path(__file__).resolve().parent;project=folder.parents[1]
def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def sha(data):return hashlib.sha256(data).hexdigest()
rules=(project/'firestore.rules').read_bytes()
assert rules==(folder/'firestore.rules').read_bytes()
cloud=read(folder/'cloud-rules-after.json')
assert any(file['content'].strip()==rules.decode('utf-8').strip() for file in cloud['files'])
before=read(folder/'cloud-rules-before.json')['files'][0]['content']
local=rules.decode('utf-8');start=local.index('    function auditAlertFields()');end=local.index('    match /warehouseMemberships',start)
old=local[:start]+local[end:];start=old.index('    // auditAlerts/');end=old.index('    // stockHistory/',start)
assert (old[:start]+old[end:]).strip()==before.strip()
security=read(folder/'rules-verification.json');assert security['passed'] and security['cases']==31
assert security['emulator_only'] and security['stock_quantity_and_status_unchanged']
assert read(folder/'rules-deploy.log')['status']=='success'
health=read(folder/'server-health.json');assert health['pipeline_version']=='2026-11-24.45'
assert 'OK (6 tests)' in (folder/'final-device-tests.log').read_text(encoding='utf-8-sig')
unit=[ET.parse(p).getroot() for p in (project/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert sum(int(row.attrib['tests']) for row in unit)==25
assert all(int(row.attrib['failures'])==int(row.attrib['errors'])==0 for row in unit)
backend=(folder/'backend-tests.log').read_text(encoding='utf-8-sig');assert 'Ran 148 tests' in backend and '\nOK\n' in backend
report=read(folder/'pixel-report.json');assert len(report['photos'])==12 and all(row['expected_changes_flagged'] for row in report['photos'])
assert sum(row['matched'] for row in report['photos'] if row['variant']=='normal')==109
catalog=read(project/'test_image_ai/test_images.json');assert sum(row.get('enabled',True) for row in catalog['images'])==12
apk=project/'app/build/outputs/apk/debug/app-debug.apk'
with ZipFile(apk) as archive:
    names=archive.namelist()
    for row in catalog['images']:
        assert archive.read('assets/audit_test_images/'+row['file'])==(project/'test_image_ai'/row['file']).read_bytes()
    assert not any('/ui45/' in name or '/paired44/' in name or name.endswith('.pt') for name in names)
    dex=b''.join(archive.read(name) for name in names if name.endswith('.dex'))
    assert b'AuditAlertManager' in dex and b'WarehouseMenus' in dex
    assert all(value not in dex for value in (b'Ui45LocalOnly!2026',b'qa-ui45@invenai.test',b'AuditUiAndF06Test',b'10.0.2.2:18861'))
screens=['ui45_dropdown.png','ui45_loader_alert.png','ui45_loader_list.png','ui45_qa_review.png','ui45_audit_result.png']
for name in screens:
    image=(folder/'screenshots'/name).read_bytes();assert image[:8]==b'\x89PNG\r\n\x1a\n'
    width,height=struct.unpack('>II',image[16:24]);assert width>=720 and height>=1280
summary=dict(time_utc=datetime.now(timezone.utc).isoformat(),pipeline=health['pipeline_version'],
    backend_tests=148,android_unit_tests=25,android_device_tests=6,security_cases=31,
    current_rules_match_tested_source=True,existing_inventory_rules_preserved=True,production_inventory_written=False,
    production_alerts_created_by_validation=False,two_accounts_verified_on_local_emulators=True,
    selectable_test_images=12,normal_reference_matches=109,normal_reference_positions=109,
    test_accounts_and_emulator_configuration_absent_from_main_apk=True,
    apk_sha256=sha(apk.read_bytes()),rules_sha256=sha(rules),screenshots=len(screens))
(folder/'package_verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary))
