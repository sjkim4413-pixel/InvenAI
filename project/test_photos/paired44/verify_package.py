"""Check twelve selectable photos, retired-asset removal and actual response scope."""
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile
import xml.etree.ElementTree as ET

folder=Path(__file__).resolve().parent;project=folder.parents[1]
def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def digest(data):return hashlib.sha256(data).hexdigest()
catalog=read(project/'test_image_ai/test_images.json')
entries=[r for r in catalog['images'] if r.get('enabled',True)]
profile=read(project/'test_image_ai/reference_pairs.json')
truth={r['pair_id']:r for r in read(folder/'edit_ground_truth.json')}
report=read(folder/'final_report.json')
assert len(entries)==12 and len(profile['pairs'])==6
assert len(report['photos'])==12 and all(r['expected_changes_flagged'] for r in report['photos'])
normal=[r for r in report['photos'] if r['variant']=='normal']
edited=[r for r in report['photos'] if r['variant']=='misplaced']
assert sum(r['matched'] for r in normal)==sum(r['positions'] for r in normal)==109
assert sum(r['matched'] for r in edited)==97
assert all(len(r['changed_locations'])==2 and set(r['changed_locations'])==set(r['expected_changed_locations']) for r in edited)
apk=project/'app/build/outputs/apk/debug/app-debug.apk'
with ZipFile(apk) as archive:
    names=archive.namelist()
    assert read(project/'test_image_ai/test_images.json')==json.loads(archive.read('assets/audit_test_images/test_images.json'))
    assert not any('compare_01_normal.png' in n or 'compare_02_misplaced.png' in n for n in names)
    assert not any(n.endswith('.pt') or 'reference_pairs.json' in n or 'edit_ground_truth' in n or '/paired44/' in n for n in names)
    for row in catalog['images']:
        assert archive.read('assets/audit_test_images/'+row['file'])==(project/'test_image_ai'/row['file']).read_bytes()
for pair in profile['pairs']:
    assert pair['reference_sha256']==digest((project/'test_image_ai'/pair['reference']).read_bytes())
    baseline=next(r for r in read(project/'test_photos/accuracy43/final_report.json')['photos'] if r['image']==pair['reference'])
    assert baseline['sha256']==pair['reference_sha256']
    for file,sha in pair['photos'].items():
        assert digest((project/'test_image_ai'/file).read_bytes())==sha
        response=read(project/'app/src/androidTest/assets/paired44'/(Path(file).stem+'.json'))
        assert response['verification_scope']=='test_reference_product_locations'
        assert len(response['results'])==len(pair['regions'])
        assert all(not r['verification_passed'] and not r['product_location_verified'] and not r['quantity_verified'] for r in response['results'])
tests=[ET.parse(path).getroot() for path in (project/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert tests and all(int(row.attrib['failures'])==int(row.attrib['errors'])==0 for row in tests)
health=read(folder/'server-health.json');assert health['pipeline_version']==report['pipeline']=='2026-11-24.44'
device_log=(folder/'android-device-retry.log').read_text(encoding='utf-8-sig')
assert 'OK (2 tests)' in device_log and 'FAILURES!!!' not in device_log
for pair in profile['pairs']:
    for variant in ('normal','misplaced'):
        image=(folder/'screenshots'/f"{pair['id']}_{variant}.png").read_bytes()
        assert image[:8]==b'\x89PNG\r\n\x1a\n'
        width,height=struct.unpack('>II',image[16:24]);assert width>=720 and height>=1280
summary=dict(time_utc=datetime.now(timezone.utc).isoformat(),pipeline=health['pipeline_version'],
    selectable_photos=12,pairs=6,normal_matches=109,normal_positions=109,
    edited_unchanged_green=97,edited_changed_flagged=12,
    edited_red=sum(r['misplaced'] for r in edited),edited_orange=sum(r['review'] for r in edited),
    retired_photos_absent_from_apk=True,server_profiles_absent_from_apk=True,original_six_hashes_unchanged=True,
    android_unit_tests=sum(int(row.attrib['tests']) for row in tests),
    apk_sha256=digest(apk.read_bytes()),inventory_records=len(read(folder/'inventory_readback.json')),
    firestore_written=False,screenshots_verified=12,paired_device_tests=2,
    method='closed-set annotated reference appearance, real pixels; not field accuracy')
(folder/'package_verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary))
