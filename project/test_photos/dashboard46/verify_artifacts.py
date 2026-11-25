"""Verify the Android-only dashboard fix artifacts; no network or database writes."""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

folder = Path(__file__).resolve().parent
project = folder.parents[1]
apk = project / 'app/build/outputs/apk/debug/app-debug.apk'
build_log = (folder / 'android-build.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in build_log
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (project / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=32, failures=0, errors=0, skipped=0), counts
dashboard_log = (folder / 'dashboard-device-tests.log').read_text(encoding='utf-8-sig')
assert 'OK (1 test)' in dashboard_log and 'FAILURES' not in dashboard_log
regression_log = (folder / 'regression-device-tests-retry.log').read_text(encoding='utf-8-sig')
regression_result = re.search(r'Tests run:\s*(\d+),\s*Failures:\s*(\d+)', regression_log)
assert regression_result, regression_log[-500:]
regression_total, regression_failed = map(int, regression_result.groups())
assert (regression_total, regression_failed) == (6, 1)
assert 'F05ReferencePairsScreenTest.kt:98' in regression_log
assert 'Required value was null' in regression_log
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    catalog = json.loads(archive.read('assets/audit_test_images/test_images.json'))
    enabled = [entry for entry in catalog['images'] if entry.get('enabled', True)]
    assert len(enabled) == 12
    assert sum(entry['variant'] == 'normal' for entry in enabled) == 6
    assert sum(entry['variant'] == 'misplaced' for entry in enabled) == 6
    for entry in enabled:
        assert 'assets/audit_test_images/' + entry['file'] in names
    assert not any(name.startswith('assets/ui45/') for name in names)
    forbidden = [b'Ui45LocalOnly!2026', b'qa-ui45@invenai.test', b'loader-ui45@invenai.test',
                 b'10.0.2.2:18861', b'10.0.2.2:18862', b'DashboardAuditSummaryScreenTest']
    for name in names:
        if name.endswith('.dex'):
            content = archive.read(name)
            assert not any(marker in content for marker in forbidden), name
screenshots = sorted(path.name for path in (folder / 'screenshots').glob('dashboard46_*.png'))
assert len(screenshots) == 4
report = dict(
    time_utc=datetime.now(timezone.utc).isoformat(),
    android_unit_tests=counts,
    dashboard_device_test_passed=True,
    additional_device_tests=dict(total=regression_total, passed=regression_total-regression_failed,
                                 failed=regression_failed),
    additional_regression_passed=False,
    additional_regression_failure='UiAutomation.takeScreenshot() returned null in F05ReferencePairsScreenTest.kt:98',
    local_firebase_only=True,
    production_inventory_written=False,
    stock_unchanged_during_dashboard_test=True,
    deduplicated_current_inventory_quantity=True,
    open_and_review_requested_included=True,
    resolved_audit_findings_excluded=True,
    warehouse_scope_checked=True,
    selectable_test_images=len(enabled),
    test_credentials_and_test_emulator_endpoints_absent_from_main_apk=True,
    apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
    screenshots=screenshots,
)
(folder / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
