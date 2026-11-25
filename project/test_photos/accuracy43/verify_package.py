"""Verify the final six-photo release, its APK assets and test-only model."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile

folder = Path(__file__).resolve().parent
project = folder.parents[1]
backend = project / 'app/src/main/java/com/example/myapp/F03_py'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


report = read(folder / 'final_report.json')
release = read(folder / 'release_verification.json')
metadata = read(backend / 'runs/detect/shelf_test_v43/training_metadata.json')
assert report['matched'] == report['total'] == 109
assert metadata['training_includes_the_six_reference_scenes']
assert not metadata['independent_field_accuracy_measured']
weights = backend / 'runs/detect/shelf_test_v43/weights/best.pt'
assert digest(weights.read_bytes()) == metadata['weights_sha256'] == release['weights_sha256']
shelf = backend / 'runs/detect/shelf_v37/weights/best.pt'
assert digest(shelf.read_bytes()) == 'c94bf8cca79b945db6ec03fed46a898eb29d81803c422226eb88851578a901cf'
catalog = read(project / 'test_image_ai/test_images.json')
active = [row for row in catalog['images'] if row.get('enabled', True)]
assert len(active) == 8
assert len([row for row in active if row['group'] == 'reference']) == 6
apk = project / 'app/build/outputs/apk/debug/app-debug.apk'
assets = []
with ZipFile(apk) as archive:
    assert json.loads(archive.read('assets/audit_test_images/test_images.json')) == catalog
    assert not any(name.endswith('.pt') or name.startswith('assets/accuracy43/') for name in archive.namelist())
    for row in catalog['images']:
        content = (project / 'test_image_ai' / row['file']).read_bytes()
        assert archive.read('assets/audit_test_images/' + row['file']) == content
        assets.append(dict(file=row['file'], sha256=digest(content), selectable=row.get('enabled', True)))
for row in report['photos']:
    content = (project / 'test_image_ai' / row['image']).read_bytes()
    assert digest(content) == row['sha256']
    assert (folder / 'prepared' / row['image']).read_bytes() == content
    response = read(project / 'app/src/androidTest/assets/accuracy43' / (Path(row['image']).stem + '.json'))
    assert response['revision']['test_shelf_model_sha256'] == metadata['weights_sha256']
    assert response['total_positions'] == row['matches'] == row['expected_positions']
    assert all(r['test_label_match'] and not r['product_location_verified'] and not r['quantity_verified']
               for r in response['results'])
    number = row['title'].split()[1]
    screenshot = (folder / 'screenshots' / f'accuracy43_{number}.png').read_bytes()
    assert screenshot[:8] == b'\x89PNG\r\n\x1a\n'
    width, height = struct.unpack('>II', screenshot[16:24])
    assert width >= 720 and height >= 1280
readback = read(folder / 'inventory_readback.json')
plan = {row['id']: row for row in read(folder / 'inventory_plan.json')['records']}
assert len(readback) == len(plan) == 109
for doc_id, data in readback:
    assert data['isTestData'] and data['dataSource'] == 'SIMULATED_AI_IMAGE_ERP'
    assert all(data[key] == value for key, value in plan[doc_id]['patch'].items())
verification = dict(time_utc=datetime.now(timezone.utc).isoformat(), pipeline=report['pipeline'],
    apk=str(apk.relative_to(project)), apk_sha256=digest(apk.read_bytes()), assets=assets,
    selectable_images=8, reference_photos=6, matched=109, expected_positions=109,
    weights_sha256=metadata['weights_sha256'], separate_shelf_v37_hash_verified=True,
    server_profiles_and_test_responses_absent_from_main_apk=True,
    corrected_firestore_test_documents=len(readback), screenshots_verified=6)
(folder / 'package_verification.json').write_text(
    json.dumps(verification, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: value for key, value in verification.items() if key != 'assets'}))
