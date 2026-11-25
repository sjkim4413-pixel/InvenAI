"""Register image hashes and one shared set of locations, never per-image answers."""
import hashlib
import json
from pathlib import Path
from PIL import Image

folder = Path(__file__).resolve().parent
project = folder.parent.parent
images = project / 'test_image_ai'
names = ('compare_01_normal.png', 'compare_02_misplaced.png')
photos = {}
for name in names:
    content = (folder / name).read_bytes()
    with Image.open(folder / name) as image:
        assert image.size == (1536, 1024), image.size
    assert len(content) <= 16 * 1024 * 1024
    (images / name).write_bytes(content)
    photos[name] = hashlib.sha256(content).hexdigest()
profile = {
    'version': 1, 'company_code': 'ABC-002', 'warehouse': 'AI 상품·위치 비교 테스트 창고',
    'photos': photos,
    'region_provenance': 'Human-checked fixed location regions for F05 visualization; not YOLO ground-truth predictions',
    'regions': [
        {'location': 'QAC02-5F-01', 'bbox': [.015,.15,.344,.935], 'load_bbox': [.025,.18,.34,.815]},
        {'location': 'QAC02-5F-02', 'bbox': [.345,.15,.67,.935], 'load_bbox': [.35,.18,.665,.815]},
        {'location': 'QAC03-5F-01', 'bbox': [.675,.15,.99,.935], 'load_bbox': [.68,.18,.985,.815]},
    ],
}
(images / 'product_comparison.json').write_text(json.dumps(profile,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
manifest = json.loads((folder / 'comparison_manifest.json').read_text(encoding='utf-8-sig'))
manifest.update(normal_sha256=photos[names[0]], misplaced=names[1], misplaced_sha256=photos[names[1]],
                test_mode='test_product_regions', region_provenance=profile['region_provenance'])
(folder / 'comparison_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
records = []
for row in manifest['products']:
    records.append({'id':'ABC-002_'+row['sku'], 'data':dict(
        companyCode='ABC-002',warehouse=profile['warehouse'],location=row['location'],
        itemCode=row['sku'],itemName=row['name'],quantity=1,quantityUnit='PACKAGING_GROUP',status='NORMAL',
        isTestData=True,dataSource='SIMULATED_AI_IMAGE_ERP',photoReference=names[0],
        alternatePhotoReference=names[1],photoSha256=photos[names[0]],alternatePhotoSha256=photos[names[1]],
        photoLocationLabel=row['location'],photoLabelIssue='')})
plan = dict(project='warehouseqa-8c262',database='(default)',collection='inventory',companyCode='ABC-002',
            warehouse=profile['warehouse'],records=records)
(folder / 'inventory_plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'photos':len(photos),'shared_locations':len(profile['regions']),'inventory_records':len(records)}))
