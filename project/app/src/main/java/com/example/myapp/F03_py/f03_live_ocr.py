"""Derive product and position evidence independently from photographed text."""
import re
from collections import defaultdict

from f03_live_inventory import InventoryInputError, clean, location_parts
from f03_verification import covered_fraction, spatially_verified

OCR_THRESHOLD = .70
SKU = r'[A-Z0-9][A-Z0-9._/-]{1,63}'
SKU_PREFIX = r'(?:SKU|ITEM\s*CODE|PRODUCT\s*CODE)'
PRINTED_SKU = re.compile(rf'{SKU_PREFIX}(?:\s*[:#=]\s*|\s+)({SKU})')
PREFIX_ONLY = re.compile(rf'{SKU_PREFIX}\s*[:#=]?')
CODE_SEPARATORS = ('', '-', '_', '/', '.')
DIGIT_LOOKALIKES = str.maketrans({'O':'0','S':'5'})
RACK_DIGIT_LOOKALIKES = str.maketrans({'O':'0','S':'5','I':'1'})
TIER_CODE = re.compile(r'[0-9OSI]+\s*[F층단](?:\s*-\s*(?:[LR]|[0-9OSI]{1,3}))?')
RACK_FIELDS = re.compile(r'(?P<zone>[A-Z0-9가-힣]+구역\s*)?(?P<rack>[A-Z][A-Z0-9]+)'
                         r'(?:\s*[-/|:]\s*|\s+)(?P<tier>[0-9OSI]+)\s*[F층단]'
                         r'(?:\s*-\s*(?P<side>[LR]|[0-9OSI]{1,3}))?')


def code_text(text):
    return re.sub(r'\s*([._/-])\s*', r'\1', clean(text).upper())


def rack_text(text):
    return re.sub(r'^(?:RACK|LOCATION)(?:\s*[:#]\s*|\s+)', '', clean(text).upper())


def visible_location(text):
    text = rack_text(text)
    text = re.sub(r'([A-Z]+\d+)(?:\s*[/|:]\s*|\s+)([1-9]\d*\s*[F층단])',r'\1-\2',text,count=1)
    try:
        return location_parts(text)
    except InventoryInputError:
        return None


def rack_number_variants(text):
    for boundary in range(1,len(text)):
        prefix, digits = text[:boundary], text[boundary:].translate(RACK_DIGIT_LOOKALIKES)
        if re.fullmatch(r'[A-Z]+',prefix) and re.fullmatch(r'[0-9]+',digits):
            yield prefix+digits


def rack_readings(text):
    """Keep possible typed readings until the warehouse can resolve them."""
    exact=visible_location(text)
    if exact:
        yield exact,None
    fields=RACK_FIELDS.fullmatch(rack_text(text))
    if not fields:
        return
    tier=fields['tier'].translate(RACK_DIGIT_LOOKALIKES)
    if tier[0]=='0':
        return
    side=fields['side'].translate(RACK_DIGIT_LOOKALIKES) if fields['side'] else None
    for rack in rack_number_variants(fields['rack']):
        if exact and (rack,int(tier),side)==(exact['rack'],exact['tier'],exact['side']):
            continue
        normalized=f"{fields['zone'] or ''}{rack}-{tier}F"
        if side:
            normalized+=f'-{side}'
        yield location_parts(normalized),normalized


def same_label(a,b):
    return covered_fraction(a['bbox'],b['bbox'])>=.65 and covered_fraction(b['bbox'],a['bbox'])>=.65


def next_on_line(left, right):
    _, b, c, d = left['bbox']
    x, y, _, w = right['bbox']
    overlap = min(d,w)-max(b,y)
    return 0 <= x-c <= min(.05, 1.5*max(d-b,w-y)) and overlap >= .6*min(d-b,w-y)


def nearby_code(prefix, token):
    a, b, c, d = prefix['bbox']
    x, y, z, w = token['bbox']
    overlap = min(c,z)-max(a,x)
    below = 0 <= y-d <= min(.04, 1.2*max(d-b,w-y)) and overlap >= .6*min(c-a,z-x)
    return below or next_on_line(prefix,token)


def label_evidence(tokens):
    return {'bbox':[min(t['bbox'][0] for t in tokens),min(t['bbox'][1] for t in tokens),
                    max(t['bbox'][2] for t in tokens),max(t['bbox'][3] for t in tokens)],
            'confidence':min(t['confidence'] for t in tokens),'ocr_token_ids':[t['id'] for t in tokens]}


def rack_label_evidence(tokens):
    strong=[t for t in tokens if t['confidence']>=OCR_THRESHOLD]
    for token in strong:
        if any(rack_readings(token['text'])):
            yield {**token,'ocr_token_ids':[token['id']]}
        elif any(rack_number_variants(rack_text(token['text']))):
            rack=rack_text(token['text'])
            for tier in strong:
                text=code_text(tier['text'])
                if TIER_CODE.fullmatch(text) and next_on_line(token,tier):
                    yield {'id':token['id'],'text':f'{rack}-{text}',**label_evidence([token,tier])}


def product_evidence(tokens, registered):
    """Interpret printed fields without using a location's expected SKU."""
    exact = defaultdict(list)
    lookalikes = defaultdict(list)
    for sku in registered:
        code = code_text(sku)
        exact[code].append(sku)
        lookalikes[code.translate(DIGIT_LOOKALIKES)].append(sku)
    fields=[(t,code_text(t['text'])) for t in tokens]
    prefixes=[t for t,text in fields if PREFIX_ONLY.fullmatch(text)]
    found = []
    continuations=set()

    def add(sku,evidence):
        result={'sku':sku,'method':'OCR_PRODUCT_CODE',**label_evidence(evidence)}
        if all(token.get('confirmed_reread') for token in evidence):
            result['confirmed_reread']=True
        found.append(result)

    # Recover punctuation lost between OCR fragments, using the company-wide
    # catalog and the observed letters and digits. Preserve competing codes.
    for left,text in fields:
        explicit=PRINTED_SKU.fullmatch(text)
        prefix=next((p for p in prefixes if p is not left and nearby_code(p,left)),None)
        stem=explicit[1] if explicit else text
        if not (explicit or prefix) or not re.fullmatch(r'[A-Z]{1,16}[-_/.]?',stem):
            continue
        for right,tail in fields:
            if not re.fullmatch(r'\d[A-Z0-9._/-]{1,19}',tail) or not next_on_line(left,right):
                continue
            continuations.add(left['id'])
            forms=[stem+tail] if stem[-1] in CODE_SEPARATORS[1:] else [stem+s+tail for s in CODE_SEPARATORS]
            for code in forms:
                for sku in exact.get(code,[]):
                    add(sku,[prefix,left,right] if not explicit else [left,right])

    for token,text in fields:
        explicit = PRINTED_SKU.fullmatch(text)
        if explicit:
            if token['id'] not in continuations or explicit[1] in exact:
                for sku in exact.get(explicit[1],[explicit[1]]):add(sku,[token])
        elif PREFIX_ONLY.fullmatch(text):
            for value,code in fields:
                if (value is not token and re.fullmatch(SKU,code) and not any(rack_readings(code))
                        and nearby_code(token,value) and (value['id'] not in continuations or code in exact)):
                    for sku in exact.get(code,[code]):add(sku,[token,value])
        elif len(text) >= 5 and text in exact and not any(rack_readings(text)):
            for sku in exact[text]:add(sku,[token])
    strong=[entry for entry in found if entry['confidence']>=OCR_THRESHOLD]
    confirmed=[entry for entry in strong if entry.get('confirmed_reread') and code_text(entry['sku']) in exact]
    reviewed=[]
    for entry in found:
        code=code_text(entry['sku'])
        signature=code.translate(DIGIT_LOOKALIKES)
        if code not in exact and any(
            same_label(other,entry) and code_text(other['sku']).translate(DIGIT_LOOKALIKES)==signature
            for other in confirmed
        ):
            continue
        if entry['confidence']<OCR_THRESHOLD and any(
            other['sku']!=entry['sku'] and other['confidence']-entry['confidence']>=.15 and same_label(other,entry)
            for other in strong
        ):
            continue
        alternatives=[sku for sku in lookalikes[signature] if sku!=entry['sku']]
        if alternatives and not entry.get('confirmed_reread'):
            entry['confusable_skus']=sorted(alternatives)
        reviewed.append(entry)
    return reviewed


def pallet_ocr_box(load_box):
    left, top, right, bottom = load_box
    margin = min(.055, .32 * (bottom - top))
    return [left, top, right, min(1., bottom + margin)]


def product_label_area(label_box, load_box, position_box, other_load_boxes):
    """Verify a SKU on the load itself or on its uniquely associated pallet."""
    all_load_boxes = [load_box, *other_load_boxes]
    if spatially_verified(label_box, load_box, position_box, all_load_boxes):
        return 'load'
    if (covered_fraction(load_box, position_box) >= .95
            and covered_fraction(label_box, pallet_ocr_box(load_box)) >= .95
            and all(covered_fraction(label_box, pallet_ocr_box(other)) < .5 for other in other_load_boxes)):
        return 'pallet'
    return None


def under_load(label, box, *, printed_sticker=False):
    a, b, c, d = box
    x, y, z, w = label
    width, height = c-a, d-b
    middle = (x+z)/2
    overlap = min(c,z)-max(a,x)
    min_overlap=.65 if printed_sticker else .80
    bottom_limit=pallet_ocr_box(box)[3]
    if printed_sticker:
        # The sticker is centred on the shelf beam. Its box can be wider
        # than the load above, and the beam can sit below a shallow load.
        bottom_limit=max(bottom_limit,d+min(.027,.55*height,1.6*(w-y)))
    return (a+.05*width <= middle <= c-.05*width and overlap >= min_overlap*(z-x)
            and d-min(.04,.22*height) <= (y+w)/2
            and y <= bottom_limit)


def rack_labels_under_load(labels, box, *, printed_sticker=False):
    """Return physically distinct stickers supported by one detection."""
    distinct=[]
    for label in labels:
        if under_load(label['bbox'],box,printed_sticker=printed_sticker) and not any(same_label(label,other) for other in distinct):
            distinct.append(label)
    return distinct


def test_rack_detection_supported(detection):
    return (.40 <= detection['confidence'] < .5
            and sum(p['confidence'] >= .35 for p in detection.get('scale_predictions', ())) >= 2)


def read_location_regions(raw, positions, manual, *, test_rack_labels=False, printed_stickers=False):
    """Map a legible rack label to one ERP position and one pictured load."""
    occupied_positions = {r['position_id'] for r in manual}
    rack_labels = list(rack_label_evidence(raw.get('ocr_tokens', [])))
    positions_by_rack = defaultdict(list)
    for position in positions:
        positions_by_rack[position['rack'],position['floor']].append(position)
    labels = defaultdict(list)
    registered_stickers=[]
    for token in rack_labels:
        matches={}
        for parts,normalized in rack_readings(token['text']):
            for position in positions_by_rack[parts['rack'],parts['tier']]:
                if ((parts['zone'] is None or parts['zone']==position['zone'])
                        and (parts['side'] is None or parts['side']==position['side'])):
                    evidence={**token,'normalized_text':normalized} if normalized else token
                    matches.setdefault(position['position_id'],(position,evidence))
        if matches:
            registered_stickers.append(token)
        if len(matches)==1:
            position_id,(position,evidence)=next(iter(matches.items()))
            labels[position_id].append((position,evidence))

    candidates = [d for d in raw['detections'] if d.get('detection_stable') is True
                  and len(rack_labels_under_load(registered_stickers,d['bbox'],printed_sticker=printed_stickers)) == 1
                  and not any(covered_fraction(d['bbox'],r['bbox']) >= .5 for r in manual)]
    strict = [d for d in candidates if d['confidence'] >= .5]
    # A test-label check may use two overlapping moderate detector reads and
    # strong OCR together. Actual product verification retains its own cutoff.
    test_support = [d for d in candidates if test_rack_detection_supported(d)] if test_rack_labels else []
    by_detection = defaultdict(list)
    for position_id, observations in labels.items():
        if position_id in occupied_positions:
            continue
        position, label = max(observations, key=lambda p:p[1]['confidence'])
        if any(covered_fraction(other['bbox'],label['bbox']) < .5
               and covered_fraction(label['bbox'],other['bbox']) < .5 for _,other in observations):
            continue  # Same printed location seen on two different stickers.
        loads = [d for d in strict if under_load(label['bbox'],d['bbox'],printed_sticker=printed_stickers)]
        if not loads and test_support and label['confidence']>=.80:
            loads = [d for d in test_support if under_load(label['bbox'],d['bbox'],printed_sticker=printed_stickers)]
        if len(loads) == 1:
            detection, = loads
            region={'position_id':position_id,'rack':position['rack'],'floor':position['floor'],
                    'bbox':detection['bbox'],'detection_id':detection['id'],
                    'confirmation_method':'ocr_rack_label','location_evidence':label}
            if detection['confidence']<.5:
                region['test_detection_consensus']=True
            by_detection[detection['id']].append(region)
    return [regions[0] for regions in by_detection.values() if len(regions) == 1]
