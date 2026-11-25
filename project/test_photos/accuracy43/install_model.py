"""Install only the checkpoint and assets that actually passed 109 positions."""
import csv
import hashlib
import json
from pathlib import Path
import shutil
import sys

folder=Path(__file__).resolve().parent;project=folder.parents[1]
scratch=Path(sys.argv[1]);evaluation=scratch/'candidate7-redacted'
checkpoint=scratch/'training2/candidate/weights/last.pt'
digest=hashlib.sha256(checkpoint.read_bytes()).hexdigest()
report=json.loads((evaluation/'report.json').read_text(encoding='utf-8'))
assert report['matched']==report['total']==109 and len(report['photos'])==6
for photo in report['photos']:
    assert photo['matches']==photo['expected_positions']
    path=project/'test_image_ai'/photo['image']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==photo['sha256']
    raw=json.loads((evaluation/(path.stem+'.json')).read_text(encoding='utf-8'))
    assert raw['revision']['test_shelf_model_sha256']==digest
target=project/'app/src/main/java/com/example/myapp/F03_py/runs/detect/shelf_test_v43'
(target/'weights').mkdir(exist_ok=True,parents=True)
staged=target/'weights/best.pending.pt'
shutil.copy2(checkpoint,staged)
assert hashlib.sha256(staged.read_bytes()).hexdigest()==digest
staged.replace(target/'weights/best.pt')
shutil.copy2(scratch/'training2/dataset/provenance.json',target/'training_provenance.json')
shutil.copy2(scratch/'training2/candidate/results.csv',target/'training_metrics.csv')
metadata=dict(pipeline='2026-11-24.43',weights_sha256=digest,source_checkpoint='training2/candidate/weights/last.pt',
    selection='109/109 reference positions, actual OCR and detector inference',
    training_includes_the_six_reference_scenes=True,independent_field_accuracy_measured=False,
    label_text_masked_during_training_and_detection=True,reference_positions=109,
    production_detector_replaced=False,used_for='test_rack_labels_only')
(target/'training_metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
shutil.copy2(scratch/'baseline/report.json',folder/'baseline_report.json')
print(json.dumps(dict(installed=str(target/'weights/best.pt'),sha256=digest,positions=109)))
