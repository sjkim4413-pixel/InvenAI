"""Diagnostic replay of saved real observations; never presented as fresh inference."""
import json
from pathlib import Path
import sys
folder=Path(__file__).resolve().parent;project=folder.parents[1]
sys.path.insert(0,str(project/'app/src/main/java/com/example/myapp/F03_py'))
from f03_live_inventory import build_snapshot,warehouse_view
from f04_live_review import review_live
work=Path(sys.argv[1]);stock=build_snapshot('ABC-002',json.loads(Path(sys.argv[2]).read_text(encoding='utf-8')))
report=[]
for scene in json.loads((folder/'annotations.json').read_text(encoding='utf-8')):
    if not (work/(Path(scene['image']).stem+'.json')).exists(): continue
    prior=json.loads((work/(Path(scene['image']).stem+'.json')).read_text(encoding='utf-8'))
    saved=list((work/'uploads').rglob(prior['filename']+'.test-rack-labels.json'))
    assert len(saved)==1
    raw=json.loads(saved[0].read_text(encoding='utf-8'))['analysis']
    records=[r for r in stock['records'] if r.get('test_photo_reference')==scene['image']]
    warehouse=records[0]['warehouse']
    positions=warehouse_view({**stock,'records':records},warehouse)
    codes={r['position_id']:r['test_photo_label'] for r in records if not r['test_photo_label_issue']}
    result=review_live(raw,[],stock,positions,[],{},test_item_codes=codes,printed_stickers=True)
    row=dict(image=scene['image'],matched=sum(bool(r.get('test_label_match')) for r in result['results']),expected=len(records),
             missing=[r['location_label'] for r in result['results'] if not r.get('test_label_match')])
    report.append(row);print(json.dumps(row),flush=True)
Path(sys.argv[3]).write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
