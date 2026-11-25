"""Reproduce the three additional rack-label fixtures without changing originals."""
import csv
import hashlib
import io
import json
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
FIXTURES = PROJECT / 'test_image_ai'


def write_same_or_new(path, content):
    if path.exists():
        if path.read_bytes() != content:
            raise ValueError(f'Existing file differs; inspect before replacing: {path}')
    else:
        path.write_bytes(content)


def main():
    specs = json.loads((HERE / 'extra_photos.json').read_text(encoding='utf-8'))
    manifest_path = FIXTURES / 'rack_labelled_photos.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    records, annotations, csv_rows, rendered = [], [], [], []
    for spec in specs['photos']:
        source = HERE.parent / spec['source']
        source_bytes = source.read_bytes()
        with Image.open(io.BytesIO(source_bytes)) as original:
            image = ImageOps.exif_transpose(original).convert('RGB')
        image.thumbnail((2200, 2200), Image.Resampling.LANCZOS)
        width, height = image.size
        ref_width, ref_height = spec['referenceSize']
        sx, sy = width / ref_width, height / ref_height
        font_path = Path('C:/Windows/Fonts/arialnb.ttf')
        if not font_path.is_file():
            font_path = Path('C:/Windows/Fonts/arialbd.ttf')
        font = ImageFont.truetype(str(font_path), round(21 * sy))
        draw, placed, rows = ImageDraw.Draw(image), [], []
        for position in spec['positions']:
            location = position['location']
            match = re.fullmatch(r'(QA[E-G])(\d{2})-([1-9])F-(\d{2})', location)
            if match is None:
                raise ValueError(f'Invalid fixture location: {location}')
            zone, bay, tier, side = match.groups()
            left, top, right, bottom = draw.textbbox((0, 0), location, font=font)
            px, py = round(6 * sx), round(4 * sy)
            bw, bh = right - left + 2 * px, bottom - top + 2 * py
            cx, cy = position['center']
            x = max(2, min(round(cx * sx - bw / 2), width - bw - 2))
            y = max(2, min(round(cy * sy - bh / 2), height - bh - 2))
            box = (x, y, x + bw, y + bh)
            for previous in placed:
                if min(box[2], previous[2]) > max(box[0], previous[0]) and min(box[3], previous[3]) > max(box[1], previous[1]):
                    raise ValueError(f'Overlapping fixture labels: {location}')
            placed.append(box)
            draw.rounded_rectangle(box, radius=max(2, round(3 * sy)), fill='white',
                                   outline='#202020', width=max(1, round(sy)))
            draw.text((x + px - left, y + py - top), location, fill='black', font=font)
            rack_bbox = [round(value / (width if index % 2 == 0 else height), 6) for index, value in enumerate(box)]
            load_bbox = [round(value / (ref_width if index % 2 == 0 else ref_height), 6)
                         for index, value in enumerate(position['loadBBox'])]
            rows.append({'rack': location, 'itemCode': 'SIM-' + location,
                         'expectedItemCode': 'SIM-' + location, 'expectedStatus': 'TEST_RACK_LABEL_MATCH',
                         'rackBBox': rack_bbox, 'loadBBox': load_bbox,
                         'description': position['name'],
                         'partialAtImageEdge': position['loadBBox'][0] == 0 or position['loadBBox'][2] == ref_width})
            data = dict(companyCode='ABC-002', warehouse='AI 실사 추가 테스트 창고', zone=zone + '구역',
                        rack=zone + bay, rackId=zone + '구역 ' + zone + bay, tier=tier + '단', side=side,
                        location=location, itemCode='SIM-' + location, itemName=position['name'],
                        quantity=1, quantityUnit='PACKAGING_GROUP', status='NORMAL', isTestData=True,
                        dataSource='SIMULATED_AI_IMAGE_ERP', photoReference=spec['output'],
                        photoLocationLabel=location, photoLabelIssue='', sourcePhotos=[spec['source']])
            records.append({'id': 'ABC-002_' + data['itemCode'], 'data': data})
            csv_rows.append({key: data[key] for key in ('companyCode', 'warehouse', 'zone', 'rack', 'rackId', 'tier', 'side', 'location')})
            csv_rows[-1].update(sourcePhoto=spec['source'], labelledPhoto=spec['output'],
                                labelCenterX=f'{(x+bw/2)/width:.6f}', labelCenterY=f'{(y+bh/2)/height:.6f}')
        stream = io.BytesIO()
        image.save(stream, format='PNG', compress_level=6)
        content = stream.getvalue()
        if len(content) > 16 * 1024 * 1024:
            raise ValueError(f'Fixture exceeds app upload limit: {spec["output"]}')
        image_hash = hashlib.sha256(content).hexdigest()
        existing_hash = manifest.get(spec['output'])
        if existing_hash is not None and existing_hash != image_hash:
            raise ValueError(f'Registered fixture differs: {spec["output"]}')
        manifest[spec['output']] = image_hash
        for record in records:
            if record['data']['photoReference'] == spec['output']:
                record['data']['photoSha256'] = image_hash
        annotations.append({'image': spec['output'], 'imageSha256': image_hash,
                            'sourcePhoto': spec['source'], 'sourceSha256': hashlib.sha256(source_bytes).hexdigest(),
                            'width': width, 'height': height, 'description': spec['description'], 'rows': rows})
        rendered.append((spec['output'], content))
    assert len(records) == len({record['id'] for record in records}) == 26
    # Validate every output before changing the manifest or writing any image.
    for name, content in rendered:
        for directory in (HERE, FIXTURES):
            path = directory / name
            if path.exists() and path.read_bytes() != content:
                raise ValueError(f'Existing fixture differs: {path}')
    for name, content in rendered:
        for directory in (HERE, FIXTURES):
            write_same_or_new(directory / name, content)
    plan = dict(project='warehouseqa-8c262', database='(default)', collection='inventory',
                companyCode='ABC-002', warehouse='AI 실사 추가 테스트 창고', records=records)
    for filename, document in (('extra_inventory_plan.json', plan), ('extra_annotations.json', annotations)):
        write_same_or_new(HERE / filename, (json.dumps(document, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    stream = io.StringIO(newline='')
    writer = csv.DictWriter(stream, fieldnames=list(csv_rows[0]))
    writer.writeheader()
    writer.writerows(csv_rows)
    write_same_or_new(HERE / 'rack_locations_extra_ABC-002.csv', stream.getvalue().encode('utf-8-sig'))
    updated = manifest_path.with_suffix('.pending.json')
    updated.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    updated.replace(manifest_path)
    print(json.dumps({'new_photos': len(rendered), 'new_positions': len(records),
                      'registered_labelled_photos': len(manifest),
                      'bytes': {name: len(content) for name, content in rendered}}))


if __name__ == '__main__':
    main()
