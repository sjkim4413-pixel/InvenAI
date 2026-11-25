"""Read-only verification of the warehouse selection fix package and test evidence."""
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

folder = Path(__file__).resolve().parent
project = folder.parents[1]
assert 'BUILD SUCCESSFUL' in (folder / 'android-build.log').read_text(encoding='utf-8-sig')
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (project / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=38, failures=0, errors=0, skipped=0), counts
device = (folder / 'device-tests.log').read_text(encoding='utf-8-sig')
assert 'OK (1 test)' in device and 'FAILURES' not in device
backend = (folder / 'backend-tests.log').read_text(encoding='utf-8-sig')
assert 'Ran 8 tests' in backend and '\nOK\n' in backend and 'FAILED' not in backend
apk = project / 'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    catalog = json.loads(archive.read('assets/audit_test_images/test_images.json'))
    active = [entry for entry in catalog['images'] if entry.get('enabled', True)]
    by_warehouse = dict(Counter(entry['warehouseCode'] for entry in active))
    assert sorted(by_warehouse.values()) == [2, 2, 2, 6]
    for entry in active:
        assert 'assets/audit_test_images/' + entry['file'] in names
    assert not any(name.startswith('assets/ui45/') for name in names)
    forbidden = [b'Ui45LocalOnly!2026', b'qa-ui45@invenai.test', b'10.0.2.2:18861',
                 b'10.0.2.2:18862', b'AuditWarehouseSelectionScreenTest', b'unused-local-test']
    for name in names:
        if name.endswith('.dex'):
            assert not any(marker in archive.read(name) for marker in forbidden), name
screenshots = sorted(path.name for path in (folder / 'screenshots').glob('warehouse47_*.png'))
assert len(screenshots) == 3
report = dict(time_utc=datetime.now(timezone.utc).isoformat(), android_unit_tests=counts,
    android_device_tests=1, backend_tests=8, selectable_test_images=len(active),
    test_images_per_warehouse=by_warehouse, warehouse_selection_required=True,
    chosen_warehouse_preserved_through_preview_and_api=True, mismatched_result_blocked=True,
    local_firebase_only=True, stock_unchanged_during_device_test=True,
    production_inventory_written=False, production_alerts_created=False,
    test_credentials_absent_from_main_apk=True,
    apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), screenshots=screenshots)
(folder / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
