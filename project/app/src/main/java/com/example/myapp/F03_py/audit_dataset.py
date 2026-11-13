"""Fail before training if splits share files or source-scene groups."""
import argparse
import hashlib
import json
from pathlib import Path


def audit_dataset(root):
    root=Path(root)
    manifest=root/'source_groups.json'
    groups=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {}
    hashes,source_groups,errors,counts={},{},[],{}
    for split in ('train','val','test'):
        images=[p for p in (root/'images'/split).glob('*') if p.is_file()]
        counts[split]=len(images)
        for path in images:
            key=f'{split}/{path.name}'
            if not (root/'labels'/split/(path.stem+'.txt')).is_file(): errors.append(f'라벨 없음: {key}')
            digest=hashlib.sha256(path.read_bytes()).hexdigest()
            if digest in hashes and hashes[digest].split('/')[0]!=split:
                errors.append(f'이미지 중복: {hashes[digest]} / {key}')
            hashes[digest]=key
            group=groups.get(key)
            if not group: errors.append(f'촬영 원본/장소 그룹 미지정: {key}')
            elif group in source_groups and source_groups[group]!=split:
                errors.append(f'촬영 그룹이 분리되지 않음: {group}')
            else: source_groups[group]=split
    if not counts['train'] or not counts['val']: errors.append('별도의 학습·검증 사진이 필요합니다.')
    return {'ready_for_training':not errors,'counts':counts,'errors':errors}


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('root',type=Path)
    result=audit_dataset(parser.parse_args().root)
    print(json.dumps(result,ensure_ascii=False,indent=2))
    raise SystemExit(0 if result['ready_for_training'] else 1)
