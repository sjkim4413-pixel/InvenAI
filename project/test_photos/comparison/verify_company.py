"""Check recorded real OCR against a fresh complete company inventory on stdin."""
import json
from pathlib import Path
import sys

project = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(project/'app/src/main/java/com/example/myapp/F03_py'))
from f03_live_inventory import build_snapshot, warehouse_view
from f05_comparison_demo import compare_test_regions

source = build_snapshot('ABC-002', json.load(sys.stdin))
profile = json.loads((project/'test_image_ai/product_comparison.json').read_text(encoding='utf-8'))
positions = warehouse_view(source, profile['warehouse'])
report = dict(company_records=source['source_count'],inventory_issues=len(source['issues']),photos={})
for name,expected in [('normal',[1,1,1]),('misplaced',[2,2,1])]:
    recorded=json.loads((project/f'app/src/androidTest/assets/comparison_{name}.json').read_text(encoding='utf-8'))
    raw=dict(recorded)
    raw['observations']=[dict(location=region['location'],reads=[
        [t for t in raw['ocr_tokens'] if t['id'].startswith(f'region{index}-read{view}-')]
        for view in (1,2)]) for index,region in enumerate(profile['regions'],1)]
    result=compare_test_regions(raw,source,positions,profile)
    codes=[row['f04_status_code'] for row in result['results']]
    if codes != expected:
        raise ValueError(f'Fresh company snapshot changed {name} decisions: {codes}')
    report['photos'][name]=codes
print(json.dumps(report))
