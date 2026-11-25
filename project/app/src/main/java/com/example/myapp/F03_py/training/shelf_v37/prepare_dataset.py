"""Build reviewed shelf crops, retaining source groups across all image variants."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys

from PIL import Image, ImageOps

BACKEND = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(BACKEND))
from audit_dataset import audit_dataset


def prepare(base, photos, output):
    annotations = json.loads(Path(__file__).with_name('annotations.json').read_text(encoding='utf-8'))
    groups = json.loads((base/'source_groups.json').read_text(encoding='utf-8'))
    excluded = annotations['excluded_capture_batches']
    if any(batch in name+group for name,group in groups.items() for batch in excluded):
        raise ValueError('Base dataset contains an excluded evaluation capture batch.')
    output.mkdir(parents=True,exist_ok=False)
    for split in ('train','val'):
        for kind in ('images','labels'):
            target = output/kind/split
            target.mkdir(parents=True)
            for source in (base/kind/split).iterdir():
                if source.is_file():
                    shutil.copyfile(source,target/source.name)
    provenance = []
    for sample in annotations['samples']:
        if sample['split'] not in ('train','val') or any(batch in sample['source'] for batch in excluded):
            raise ValueError('Invalid split or excluded evaluation capture batch.')
        source = (photos/sample['source']).resolve(strict=True)
        if source.parent != photos.resolve():
            raise ValueError('Photo source must be inside the supplied photo directory.')
        image = ImageOps.exif_transpose(Image.open(source)).convert('RGB')
        w,h = image.size
        l,t,r,b = [round(v*size) for v,size in zip(sample['crop'],(w,h,w,h))]
        crop = image.crop((l,t,r,b))
        crop.save(output/'images'/sample['split']/(sample['name']+'.jpg'),quality=95)
        lines = []
        for box in sample['boxes']:
            a,y,c,d = [v*size-offset for v,size,offset in zip(box,(w,h,w,h),(l,t,l,t))]
            if not (0<=a<c<=r-l and 0<=y<d<=b-t):
                raise ValueError(f"Box outside reviewed crop: {sample['name']}")
            lines.append(f'0 {(a+c)/(2*(r-l)):.7f} {(y+d)/(2*(b-t)):.7f} {(c-a)/(r-l):.7f} {(d-y)/(b-t):.7f}')
        (output/'labels'/sample['split']/(sample['name']+'.txt')).write_text('\n'.join(lines),encoding='utf-8')
        groups[f"{sample['split']}/{sample['name']}.jpg"] = sample['group']
        provenance.append({**sample,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
    (output/'source_groups.json').write_text(json.dumps(groups,indent=2),encoding='utf-8')
    (output/'provenance.json').write_text(json.dumps(provenance,indent=2),encoding='utf-8')
    (output/'data.yaml').write_text(f'path: {output.resolve().as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: load\n',encoding='utf-8')
    audit = audit_dataset(output)
    (output/'audit.json').write_text(json.dumps(audit,indent=2),encoding='utf-8')
    if not audit['ready_for_training']:
        raise ValueError(audit['errors'])
    return audit


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True,help='Previously audited independent pallet dataset')
    parser.add_argument('--photos',type=Path,required=True,help='Original EXIF-bearing photo directory')
    parser.add_argument('--output',type=Path,required=True,help='New directory; existing output is never overwritten')
    args = parser.parse_args()
    print(json.dumps(prepare(args.base,args.photos,args.output),indent=2))
