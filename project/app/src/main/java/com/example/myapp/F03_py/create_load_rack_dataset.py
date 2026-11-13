"""Build a new dataset from independent, already labeled scene groups.

Each source contains images/, labels/, and source_groups.json mapping image
filenames to their original scene IDs. Existing datasets are never overwritten.
"""
import argparse
import json
import shutil
import tempfile
from pathlib import Path
from audit_dataset import audit_dataset


def build_dataset(train_source,val_source,output):
    output=Path(output).resolve()
    if output.exists(): raise FileExistsError('기존 데이터셋을 덮어쓸 수 없습니다.')
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.dataset-',dir=output.parent) as temporary:
        staging=Path(temporary)
        manifest={}
        for split,source in [('train',Path(train_source)),('val',Path(val_source))]:
            groups=json.loads((source/'source_groups.json').read_text(encoding='utf-8'))
            for kind in ('images','labels'): (staging/kind/split).mkdir(parents=True)
            for image in (source/'images').iterdir():
                if not image.is_file(): continue
                shutil.copy2(image,staging/'images'/split/image.name)
                shutil.copy2(source/'labels'/(image.stem+'.txt'),staging/'labels'/split/(image.stem+'.txt'))
                manifest[f'{split}/{image.name}']=groups[image.name]
        (staging/'source_groups.json').write_text(json.dumps(manifest),encoding='utf-8')
        report=audit_dataset(staging)
        if not report['ready_for_training']: raise ValueError('\n'.join(report['errors']))
        (staging/'data.yaml').write_text(f'path: {output.as_posix()}\ntrain: images/train\nval: images/val\nnc: 1\nnames:\n  0: pallet_load\n',encoding='utf-8')
        staging.replace(output)
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--train-source',required=True,type=Path)
    parser.add_argument('--val-source',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    print(build_dataset(args.train_source,args.val_source,args.output))
