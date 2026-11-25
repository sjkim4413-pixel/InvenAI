"""Prepare a focused comparison fixture with explicit synthetic product labels."""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

project = Path(__file__).resolve().parents[2]
folder = project / 'test_photos/comparison'
folder.mkdir(exist_ok=True)
source = project / 'test_image_ai/QAC_rack_labels.png'
with Image.open(source) as original:
    original = original.convert('RGB')
    sx, sy = original.width / 1400, original.height / 1050
    crop_box = tuple(round(v * (sx if i % 2 == 0 else sy)) for i, v in enumerate((235, 100, 730, 298)))
    image = original.crop(crop_box)
image = image.resize((1980, 792), Image.Resampling.LANCZOS)
draw = ImageDraw.Draw(image)
font = ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf', 45)
products = [
    ('HPX-111', 'QAC02-5F-01', (320, 218), '흰색 가전 포장 묶음'),
    ('HPX-222', 'QAC02-5F-02', (477, 220), '갈색 가전 포장 묶음'),
    ('HPX-333', 'QAC03-5F-01', (652, 215), '연녹색 가전 포장 묶음'),
]
for sku, location, (cx, cy), name in products:
    text = 'SKU ' + sku
    l, t, r, b = draw.textbbox((0, 0), text, font=font)
    width, height = r-l+32, b-t+24
    x, y = round((cx-235)*4-width/2), round((cy-100)*4-height/2)
    draw.rectangle((x,y,x+width,y+height), fill='white', outline='#202020', width=2)
    draw.text((x+16-l,y+12-t),text,font=font,fill='black')
output = folder / 'source_prepared_normal.png'
image.save(output)
metadata = {'source': source.name, 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'crop_reference': [235,100,730,298], 'crop_reference_size': [1400,1050],
            'normal': output.name, 'normal_sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
            'warehouse': 'AI 상품·위치 비교 테스트 창고',
            'products': [{'sku':sku,'location':location,'name':name} for sku,location,_,name in products],
            'scope': 'Synthetic product codes for controlled photo comparison; quantity unverified'}
(folder / 'source_preparation.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'normal':str(output),'positions':len(products),'size':image.size,'bytes':output.stat().st_size}))
