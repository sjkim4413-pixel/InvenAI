"""Derive product and position evidence independently from photographed text."""
import re
from collections import defaultdict

from f03_live_inventory import InventoryInputError, clean, location_parts
from f03_multiscale_detection import (
    PERSPECTIVE_EXTENSION_SOURCE, PERSPECTIVE_MIN_CONFIDENCE, PERSPECTIVE_RETRY_SOURCE, PERSPECTIVE_SOURCES,
    perspective_consensus, perspective_extension_consensus, supports_load_extension,
)
from f03_verification import covered_fraction, spatially_verified

OCR_THRESHOLD = .70
SKU = r'[A-Z0-9][A-Z0-9._/-]{1,63}'
SKU_PREFIX = r'(?:SKU|ITEM\s*CODE|PRODUCT\s*CODE)'
PRINTED_SKU = re.compile(rf'{SKU_PREFIX}(?:\s*[:#=]\s*|\s+)({SKU})')
PREFIX_ONLY = re.compile(rf'{SKU_PREFIX}\s*[:#=]?')
JOINED_SKU = re.compile(rf'SKU({SKU})')
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
    text = re.sub(r'^(?:RACK|LOCATION)(?:\s*[:#]\s*|\s+)', '', clean(text).upper())
    # OCR can pick up the frame of a sticker or read its dash as an underscore.
    label = text.strip('[](){}| \t').replace('_','-')
    return label if RACK_FIELDS.fullmatch(label) else text


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
    label=rack_text(text)
    if exact:
        yield exact,label if label!=clean(text).upper() else None
    fields=RACK_FIELDS.fullmatch(label)
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


def sku_without_one_separator(code):
    return {code[:index]+code[index+1:] for index,char in enumerate(code)
            if char in CODE_SEPARATORS[1:] and 0<index<len(code)-1
            and code[index-1].isalnum() and code[index+1].isalnum()}


def product_evidence(tokens, registered):
    """Interpret printed fields without using a location's expected SKU."""
    exact = defaultdict(list)
    lookalikes = defaultdict(list)
    missing_separator = defaultdict(set)
    for sku in registered:
        code = code_text(sku)
        exact[code].append(sku)
        lookalikes[code.translate(DIGIT_LOOKALIKES)].append(sku)
        for compact in sku_without_one_separator(code):
            missing_separator[compact].add(sku)
    fields=[(t,code_text(t['text'])) for t in tokens]
    prefixes=[t for t,text in fields if PREFIX_ONLY.fullmatch(text)]
    found = []
    continuations=set()

    def add(sku,evidence,*,explicit=False,joined=False,separator_inferred=False):
        result={'sku':sku,'method':'OCR_PRODUCT_CODE',**label_evidence(evidence)}
        if explicit:
            result['explicit_sku_label']=True
        if joined:
            result['joined_sku_prefix']=True
        if separator_inferred:
            result['separator_inferred']=True
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
                    add(sku,[prefix,left,right] if not explicit else [left,right],
                        explicit=True,separator_inferred=code!=stem+tail)

    for token,text in fields:
        explicit = PRINTED_SKU.fullmatch(text)
        if explicit:
            if token['id'] not in continuations or explicit[1] in exact:
                for sku in exact.get(explicit[1],[explicit[1]]):add(sku,[token],explicit=True)
        elif PREFIX_ONLY.fullmatch(text):
            for value,code in fields:
                if (value is not token and re.fullmatch(SKU,code) and not any(rack_readings(code))
                        and nearby_code(token,value) and (value['id'] not in continuations or code in exact)):
                    for sku in exact.get(code,[code]):add(sku,[token,value],explicit=True)
        elif len(text) >= 5 and text in exact and not any(rack_readings(text)):
            for sku in exact[text]:add(sku,[token])
        elif (joined:=JOINED_SKU.fullmatch(text)) and (not exact or joined[1] in exact or joined[1] in missing_separator):
            add(joined[1],[token],joined=True)
    strong=[entry for entry in found if entry['confidence']>=OCR_THRESHOLD]
    confirmed=[entry for entry in strong if entry.get('confirmed_reread')
               and not entry.get('joined_sku_prefix') and code_text(entry['sku']) in exact]
    reviewed=[]
    for entry in found:
        code=code_text(entry['sku'])
        joined=entry.get('joined_sku_prefix',False)
        separator_candidates=set(exact.get(code,())) if joined else set()
        if code not in exact and (joined or entry.get('explicit_sku_label')):
            separator_candidates=missing_separator.get(code,set())
        if separator_candidates and len(separator_candidates)==1:
            candidate,=separator_candidates
            if any(other['sku']==candidate and other.get('explicit_sku_label')
                   and not other.get('separator_inferred') and same_label(other,entry)
                   and set(other['ocr_token_ids']).isdisjoint(entry['ocr_token_ids']) for other in strong):
                continue  # An independent read sees the full registered SKU on this sticker.
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
        if separator_candidates or joined:
            entry['requires_separator_reread']=True
            entry['separator_candidates']=sorted(separator_candidates)
        if not entry.get('confirmed_reread') or separator_candidates:
            ambiguous=set(alternatives).union(separator_candidates)
            if ambiguous:
                entry['confusable_skus']=sorted(ambiguous)
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


def is_load_fragment(detection, anchor, rack_labels):
    if detection['id'] == anchor['id'] or detection.get('detection_stable') is not False:
        return False
    part, whole = detection['bbox'], anchor['bbox']
    if any(under_load(label['bbox'], part) and not under_load(label['bbox'], whole) for label in rack_labels):
        return False
    inside = covered_fraction(part, whole)
    share = covered_fraction(whole, part)
    if detection['confidence'] < .30 and inside >= .75 and share <= .45:
        return True
    spans = ((part[2]-part[0])/(whole[2]-whole[0]), (part[3]-part[1])/(whole[3]-whole[1]))
    return ((inside >= .95 and share <= .60 and max(spans) >= .85)
            or (detection['confidence'] < .40 and inside >= .88 and share <= .55 and max(spans) >= .90))


def two_label_load(detection, rack_label, tokens, registered, rack_labels, all_detections):
    """Accept weak repeated detections only with an independently reread SKU and rack."""
    if rack_label['confidence'] < .85 or detection['confidence'] < .27:
        return False
    box = detection['bbox']
    local = [token for token in tokens if covered_fraction(token['bbox'],box) >= .95]
    identities = product_evidence(local,registered)
    if len({item['sku'] for item in identities}) != 1:
        return False
    perspective = detection.get('source') in PERSPECTIVE_SOURCES
    extended = detection.get('source') == PERSPECTIVE_EXTENSION_SOURCE
    native = detection.get('native_support', {})
    if extended and not (.25 <= native.get('confidence', 0) < .50 and supports_load_extension(native, box)
                         and not any(rack_labels_under_load(rack_labels, p['bbox'])
                                     for p in native.get('scale_predictions') or (native,))):
        return False
    min_label_confidence = OCR_THRESHOLD if detection.get('source') == PERSPECTIVE_RETRY_SOURCE else .85
    min_view_confidence = PERSPECTIVE_MIN_CONFIDENCE if perspective else .25
    for item in identities:
        if (item['sku'] not in registered or item['confidence'] < min_label_confidence or not item.get('confirmed_reread')
                or item.get('confusable_skus') or item.get('requires_separator_reread')):
            continue
        if perspective and (not item.get('explicit_sku_label') or any(
                other['confidence'] >= OCR_THRESHOLD and not same_label(other, item) for other in identities)):
            continue
        if any(other['id']!=detection['id'] and covered_fraction(item['bbox'],other['bbox']) >= .50
               and not (extended and is_load_fragment(other, detection, rack_labels))
               for other in all_detections):
            continue
        witnesses = [p for p in detection.get('scale_predictions',())
                     if p['confidence'] >= min_view_confidence and covered_fraction(item['bbox'],p['bbox']) >= .95
                     and under_load(rack_label['bbox'],p['bbox'])
                     and (not extended or supports_load_extension(native, p['bbox']))
                     and len(rack_labels_under_load(rack_labels,p['bbox'])) == 1]
        if extended:
            corroborated = perspective_extension_consensus(witnesses)
        else:
            views = {tuple(p.get('view', ('whole', p.get('scale')))) for p in witnesses}
            corroborated = perspective_consensus(views) if perspective else len(views) >= 2
        if corroborated:
            return True
    return False


def read_location_regions(raw, positions, manual, *, test_rack_labels=False, printed_stickers=False,
                          registered=None):
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
    strict = [d for d in candidates if d['confidence'] >= .5 and d.get('source') not in PERSPECTIVE_SOURCES]
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
        two_label = False
        if not loads and not test_rack_labels and registered:
            loads = [d for d in candidates if under_load(label['bbox'],d['bbox'])
                     and two_label_load(d,label,raw.get('ocr_tokens',()),registered,rack_labels,raw['detections'])]
            two_label = bool(loads)
        if len(loads) == 1:
            detection, = loads
            region={'position_id':position_id,'rack':position['rack'],'floor':position['floor'],
                    'bbox':detection['bbox'],'detection_id':detection['id'],
                    'confirmation_method':'ocr_rack_label','location_evidence':label}
            if two_label:
                region['two_label_detection']=True
            elif detection['confidence']<.5:
                region['test_detection_consensus']=True
            by_detection[detection['id']].append(region)
    return [regions[0] for regions in by_detection.values() if len(regions) == 1]
