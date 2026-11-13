"""Actual pixel evidence for whole-aisle photos, without inferred rack numbers."""
from pathlib import Path
import re
import cv2
import numpy as np
from f03_catalog import load_catalog, load_locations, match_catalog, resolve_regions
from f03_live_ocr import (
    OCR_THRESHOLD, pallet_ocr_box, product_evidence, rack_label_evidence, rack_labels_under_load,
    rack_readings, same_label, test_rack_detection_supported, under_load,
)
from f03_verification import covered_fraction, spatially_verified
from f03_multiscale_detection import (
    DETECTION_SCALES, FUSION_IOU, box_iou, crop_predictions, detect_objects, fuse_predictions, retry_empty_regions,
)

OCR_MAX_REGIONS = 50
OCR_MAX_RECOVERY_LOADS = 8
OCR_MAX_LABEL_REREADS = 6
OCR_MAX_ANCHORED_REREADS = 4
RACK_RETRY_TILE_RANGES = ((0., .66), (.34, 1.))
REFLECTED_FULL_SCALES = (*DETECTION_SCALES, 1536, 1792)
REFLECTED_ADJUSTED_SCALES = (*DETECTION_SCALES, 1536)
MAX_RACK_LOCAL_VIEWS = 24
RACK_RECOVERY_SOURCES = ('rack_label_tile_retry','rack_label_reflected_retry','rack_label_local_retry')
CONFUSABLE_SKU_RUN = re.compile(r'[0-9OS]{2,}')
RACK_LABEL = re.compile(r'[A-Z][0-9]{2}-[1-9][0-9]*F(?:-[0-9]{2})?')


def read_image(path):
    # Unlike imread, this supports Korean Windows paths and applies JPEG EXIF.
    image = cv2.imdecode(np.frombuffer(Path(path).read_bytes(), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None: raise ValueError('이미지를 읽을 수 없습니다.')
    return image


def read_region(reader, image, box, tokens, *, overview=False, enhance=False, binarize=False, zoom_limit=None):
    h, w = image.shape[:2]
    a, b = max(0, int(box[0]*w)), max(0, int(box[1]*h))
    c, d = min(w, int(np.ceil(box[2]*w))), min(h, int(np.ceil(box[3]*h)))
    crop = image[b:d, a:c]
    if crop.size == 0: return
    sx, sy = 1., 1.
    options = {}
    if enhance:
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        limit = zoom_limit if zoom_limit is not None else (3. if binarize else 2.)
        zoom = max(1., min(limit, 1600/max(gray.shape)))
        enlarged = cv2.resize(gray, None, fx=zoom, fy=zoom, interpolation=cv2.INTER_CUBIC)
        sx, sy = gray.shape[1]/enlarged.shape[1], gray.shape[0]/enlarged.shape[0]
        if binarize:
            _, crop = cv2.threshold(enlarged, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
        else:
            crop = cv2.createCLAHE(clipLimit=2., tileGridSize=(4,4)).apply(enlarged)
        options['text_threshold'] = .55
    for polygon, text, confidence in reader.readtext(crop, detail=1, paragraph=False,
                                                    canvas_size=1280 if overview else 1600,
                                                    mag_ratio=1.0 if overview or enhance else 1.5, workers=0,
                                                    **options):
        confidence = float(confidence)
        if confidence < .45 or not str(text).strip(): continue
        bounds = [(min(p[0] for p in polygon)*sx+a)/w, (min(p[1] for p in polygon)*sy+b)/h,
                  (max(p[0] for p in polygon)*sx+a)/w, (max(p[1] for p in polygon)*sy+b)/h]
        bounds = [max(0., min(1., float(v))) for v in bounds]
        if bounds[2] <= bounds[0] or bounds[3] <= bounds[1]: continue
        duplicate=next((t for t in tokens if t['text'] == str(text) and
                        covered_fraction(bounds,t['bbox'])>.85 and covered_fraction(t['bbox'],bounds)>.85),None)
        if duplicate:
            # Keep the strongest real reading. Earlier overview OCR must not
            # suppress a clearer later object crop of the very same label.
            if confidence > duplicate['confidence']:
                duplicate.update(confidence=confidence,bbox=bounds)
            continue
        tokens.append({'id': f't{len(tokens)+1}', 'text': str(text), 'confidence': confidence, 'bbox': bounds})


def read_printed_rack_stickers(reader,image,tokens):
    """Re-read high-contrast rectangular stickers directly from the photo."""
    gray=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY)
    height,width=gray.shape
    bright=cv2.inRange(gray,230,255)
    count,_,components,_=cv2.connectedComponentsWithStats(bright)
    known=list(rack_label_evidence(tokens))
    candidates=[]
    for x,y,w,h,area in components[1:count]:
        if not (.025*width<=w<=.20*width and .005*height<=h<=.033*height
                and 3.1<=w/h<=11 and area>=.49*w*h):
            continue
        patch=gray[y:y+h,x:x+w]
        dark=np.mean(patch<100)
        if not .06<=dark<=.42:
            continue
        bounds=[x/width,y/height,(x+w)/width,(y+h)/height]
        if any(covered_fraction(label['bbox'],bounds)>=.65 and
               covered_fraction(bounds,label['bbox'])>=.45 for label in known):
            continue
        candidates.append((area/(w*h),bounds,patch))
    for _,bounds,patch in sorted(candidates,key=lambda entry:entry[0],reverse=True)[:64]:
        h,w=patch.shape
        scale=max(1.,32/h)
        prepared=cv2.resize(patch,None,fx=scale,fy=scale,interpolation=cv2.INTER_CUBIC)
        for _,text,confidence in reader.recognize(prepared,detail=1,paragraph=False):
            if confidence>=.80 and any(parts['side'] for parts,_ in rack_readings(text)):
                tokens.append({'id':f't{len(tokens)+1}','text':text,
                               'confidence':float(confidence),'bbox':bounds})


def recover_live_labels(reader, image, detections, tokens, *, rack_only=False):
    labels=list(rack_label_evidence(tokens))
    missing_racks=[]
    weak_products=[]
    for detection in detections:
        if detection.get('detection_stable') is not True or detection['confidence']<.50:
            continue
        box=detection['bbox']
        area=pallet_ocr_box(box)
        local=[t for t in tokens if covered_fraction(t['bbox'],area)>=.75]
        if not rack_only:
            product=product_evidence(local,())
            if not any(p['confidence']>=OCR_THRESHOLD for p in product):
                weak_products.append((bool(product),detection['confidence'],area))
        if not any(under_load(t['bbox'],box) for t in labels):
            missing_racks.append((detection['confidence'],box))
    for _,box in sorted(missing_racks,key=lambda entry:entry[0],reverse=True)[:OCR_MAX_RECOVERY_LOADS]:
        x1,y1,x2,y2=box
        height=y2-y1
        area=pallet_ocr_box(box)
        rack_strip=[x1,y2-min(.04,.19*height),x2,area[3]]
        read_region(reader,image,rack_strip,tokens,enhance=True)
    for _,_,area in sorted(weak_products,key=lambda entry:entry[:2],reverse=True)[:OCR_MAX_RECOVERY_LOADS]:
        read_region(reader,image,area,tokens,enhance=True)


def label_crop(image, box, *, padding_ratio=.25):
    height, width = image.shape[:2]
    x1,y1,x2,y2=box
    pad_y=max(3/height,padding_ratio*(y2-y1))
    pad_x=pad_y*height/width
    return [max(0.,x1-pad_x),max(0.,y1-pad_y),min(1.,x2+pad_x),min(1.,y2+pad_y)]


def recognize_rack_label_box(reader, image, label, original_readings):
    """Re-read the same OCR box without running text detection again."""
    height,width=image.shape[:2]
    left,top,right,bottom=label_crop(image,label['bbox'])
    crop=image[int(top*height):int(bottom*height)+1,int(left*width):int(right*width)+1]
    x1,y1,x2,y2=label['bbox']
    bounds=[int((x1-left)*width),int((x2-left)*width),int((y1-top)*height),int((y2-top)*height)]
    if bounds[1]<=bounds[0] or bounds[3]<=bounds[2]:
        return None
    gray=cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY)
    tight=image[int(y1*height):int(y2*height),int(x1*width):int(x2*width)]
    views=rack_label_recognition_views(gray,bounds,tight)
    for source,window in views:
        reads=reader.recognize(source,detail=1,paragraph=False,horizontal_list=[window],free_list=[])
        strong=[(text,float(confidence),[parts for parts,_ in rack_readings(text)])
                for _,text,confidence in reads if confidence>=OCR_THRESHOLD]
        strong=[read for read in strong if read[2]]
        if not strong:
            continue
        if any(not any(parts in original_readings for parts in readings) for _,_,readings in strong):
            return None
        text,confidence,_=max(strong,key=lambda read:read[1])
        return {**label,'text':text,'confidence':confidence}
    return None


def rack_label_recognition_views(gray,bounds,tight):
    yield gray,bounds
    if min(tight.shape[:2])<4:
        return
    tight=cv2.cvtColor(tight,cv2.COLOR_BGR2GRAY)
    h,w=tight.shape
    yield cv2.resize(tight,(2*w,2*h),interpolation=cv2.INTER_CUBIC),[0,2*w,0,2*h]
    for angle in (-3.,3.):
        transform=cv2.getRotationMatrix2D((w/2,h/2),angle,1.)
        rotated=cv2.warpAffine(tight,transform,(w,h),borderMode=cv2.BORDER_REPLICATE)
        yield rotated,[0,w,0,h]
    contrast=cv2.createCLAHE(clipLimit=2.,tileGridSize=(4,4)).apply(tight)
    sharp=cv2.addWeighted(tight,1.8,cv2.GaussianBlur(tight,(3,3),0),-.8,0)
    for source in (contrast,sharp):
        yield cv2.resize(source,(2*w,2*h),interpolation=cv2.INTER_CUBIC),[0,2*w,0,2*h]


def reread_weak_rack_labels(reader,image,tokens,detections=()):
    known=list(rack_label_evidence(tokens))
    weak_labels=sorted(
        (t for t in tokens if t['confidence']<OCR_THRESHOLD and any(rack_readings(t['text']))),
        key=lambda t:t['confidence'],reverse=True,
    )
    visited=[]
    for label in weak_labels:
        if any(same_label(label,other) for other in known+visited):
            continue
        if len(visited)>=OCR_MAX_LABEL_REREADS:
            break
        visited.append(label)
        original=[parts for parts,_ in rack_readings(label['text'])]
        for binarize, zoom_limit, padding_ratio in ((True,3.,.25),(False,2.,.25),(False,3.,.50)):
            reread=[]
            crop=label_crop(image,label['bbox'],padding_ratio=padding_ratio)
            read_region(reader,image,crop,reread,enhance=True,binarize=binarize,zoom_limit=zoom_limit)
            recovered=[candidate for candidate in reread
                       if candidate['confidence']>=OCR_THRESHOLD and same_label(candidate,label) and any(
                           parts in original for parts,_ in rack_readings(candidate['text'])
                       )]
            if recovered:
                best=max(recovered,key=lambda candidate:candidate['confidence'])
                tokens.append({**best,'id':f't{len(tokens)+1}'})
                break
        else:
            candidate=recognize_rack_label_box(reader,image,label,original)
            if candidate:
                tokens.append({**candidate,'id':f't{len(tokens)+1}'})

    # Reserve inexpensive recognition-only reads for stickers with real load
    # evidence that did not fit in the general OCR retry budget.
    known=list(rack_label_evidence(tokens))
    anchors=[d['bbox'] for d in detections if d.get('detection_stable') is True
             and (d['confidence']>=.5 or test_rack_detection_supported(d))]
    extra=[]
    for label in weak_labels:
        if len(extra)>=OCR_MAX_ANCHORED_REREADS:
            break
        if any(same_label(label,other) for other in known+visited+extra):
            continue
        if sum(under_load(label['bbox'],box) for box in anchors)!=1:
            continue
        extra.append(label)
        original=[parts for parts,_ in rack_readings(label['text'])]
        candidate=recognize_rack_label_box(reader,image,label,original)
        if candidate:
            tokens.append({**candidate,'id':f't{len(tokens)+1}'})


def local_rack_view(label,labels):
    """Bound a load crop by the spacing of nearby photographed labels."""
    left,top,right,bottom=label['bbox']
    width,height=right-left,bottom-top
    x,y=(left+right)/2,(top+bottom)/2
    centers=[((a+c)/2,(b+d)/2) for a,b,c,d in (other['bbox'] for other in labels)]
    column=min((abs(nx-x) for nx,ny in centers if abs(ny-y)<=1.5*height and abs(nx-x)>width),
               default=2.75*width)
    column=max(.07,min(.45,column))
    row=min((y-ny for nx,ny in centers if abs(nx-x)<=.65*column and y-ny>2.5*height),
            default=max(.10,10*height))
    row=max(.075,min(.35,row))
    return [max(0.,x-1.15*column),max(0.,y-1.28*row),
            min(1.,x+1.15*column),min(1.,y+.23*row)]


def retry_rack_detections(model,image,detections,tokens,*,test_rack_labels=False,printed_stickers=False):
    """Retry unpaired rack labels with wider and reflected views of the same scene."""
    labels=[]
    for label in sorted(rack_label_evidence(tokens),key=lambda t:t['confidence'],reverse=True):
        if not any(same_label(label,previous) for previous in labels):
            labels.append(label)

    def has_anchor(label):
        return any(detection['detection_stable'] and detection['confidence']>=.5
                   and under_load(label['bbox'],detection['bbox'])
                   and len(rack_labels_under_load(labels,detection['bbox']))==1 for detection in detections)

    for view_name in ('tile','reflected','reflected_lift','reflected_stretch','local','reflected_wide','reflected_overlap'):
        pending=[label for label in labels if not has_anchor(label)]
        if not pending:
            break
        retry_image=image
        full_view=[0.,0.,1.,1.]
        if view_name=='reflected_lift':
            lightening=(255*(np.arange(256)/255)**.75).astype(np.uint8)
            retry_image=cv2.LUT(image,lightening)
            views=[(full_view,REFLECTED_ADJUSTED_SCALES)]
        elif view_name=='reflected_stretch':
            height,width=image.shape[:2]
            retry_image=cv2.resize(image,(width,round(height*1.4)),interpolation=cv2.INTER_CUBIC)
            views=[(full_view,REFLECTED_ADJUSTED_SCALES)]
        elif view_name=='local':
            views=[(local_rack_view(label,labels),DETECTION_SCALES[:2])
                   for label in pending[:MAX_RACK_LOCAL_VIEWS]]
        elif view_name=='reflected_wide':
            height,width=image.shape[:2]
            retry_image=cv2.resize(image,(round(width*1.3),height),interpolation=cv2.INTER_LINEAR)
            views=[(full_view,(1280,1536))]
        elif view_name=='reflected_overlap':
            views=[([left,top,right,bottom],(1280,))
                   for top,bottom in RACK_RETRY_TILE_RANGES for left,right in RACK_RETRY_TILE_RANGES]
        else:
            views=[(full_view,REFLECTED_FULL_SCALES)] if view_name=='reflected' else []
            tile_scales=DETECTION_SCALES if view_name=='reflected' else DETECTION_SCALES[:2]
            views.extend(([left,top,right,bottom],tile_scales)
                         for top,bottom in RACK_RETRY_TILE_RANGES for left,right in RACK_RETRY_TILE_RANGES)
        proposals={label['id']:[] for label in pending}
        for view,scales in views:
            if not any(covered_fraction(label['bbox'],view)>=.95 for label in pending):
                continue
            for label,prediction in rack_view_predictions(model,retry_image,view,scales,labels,view_name=view_name):
                if label['id'] in proposals:
                    proposals[label['id']].append(prediction)
        merge_rack_proposals(detections,labels,pending,proposals,has_anchor,
                             view_name=view_name,test_rack_labels=test_rack_labels)
    if printed_stickers:
        retry_printed_shelf_bands(model,image,detections,labels)


def retry_printed_shelf_bands(model,image,detections,labels):
    def has_anchor(label):
        return any(d['detection_stable'] and d['confidence']>=.5 and
                   rack_labels_under_load(labels,d['bbox'],printed_sticker=True)==[label]
                   for d in detections)

    pending=[label for label in labels if not has_anchor(label)]
    proposals={label['id']:[] for label in pending}
    band=[0.,.24,1.,.72]
    if not any(covered_fraction(label['bbox'],band)>=.95 for label in pending):
        return
    for size in (960,1280):
        predictions=crop_predictions(model,image,band,0.,scale=size,reflected=True,nms_iou=.60)
        for prediction in predictions:
            a,b,c,d=prediction['bbox']
            if b-band[1]<.005 or band[3]-d<.003:
                continue
            matches=rack_labels_under_load(labels,prediction['bbox'],printed_sticker=True)
            if len(matches)!=1 or matches[0]['id'] not in proposals:
                continue
            label,=matches
            x,y,z,w=label['bbox']
            if c-a<.8*(z-x) or d-b<3*(w-y):
                continue
            proposals[label['id']].append({**prediction,'view':('shelf_band',*band,True,size)})
    merge_rack_proposals(detections,labels,pending,proposals,has_anchor,
                         view_name='shelf_band',test_rack_labels=False,printed_stickers=True)


def merge_rack_proposals(detections,labels,pending,proposals,has_anchor,*,view_name,test_rack_labels,printed_stickers=False):
    reflected=view_name.startswith('reflected')
    kind='rack_label_reflected_retry' if reflected else (
        'rack_label_local_retry' if view_name=='local' else 'rack_label_tile_retry')
    test_support=test_rack_labels and view_name in ('reflected','reflected_lift','reflected_stretch')
    whole_predictions=[prediction for detection in detections
                       for prediction in detection.get('scale_predictions',())]
    for label in pending:
        tile_predictions=proposals[label['id']]
        if not tile_predictions or has_anchor(label):
            continue
        whole_support=[{**prediction,'view':tuple(prediction.get('view',('whole',prediction['scale'])))}
                       for prediction in whole_predictions
                       if under_load(label['bbox'],prediction['bbox'],printed_sticker=printed_stickers)
                       and len(rack_labels_under_load(labels,prediction['bbox'],printed_sticker=printed_stickers))==1]
        options=[candidate for candidate in fuse_predictions(tile_predictions+whole_support,view_key='view')
                 if candidate['detection_stable']
                 and (candidate['confidence']>=.5 or
                      (test_support and label['confidence']>=.80
                       and test_rack_detection_supported(candidate)))
                 and any(p['view'][0]==view_name for p in candidate['scale_predictions'])]
        if not options:
            continue
        candidate=max(options,key=lambda option:option['confidence'])
        if any(box_iou(option['bbox'],candidate['bbox'])<FUSION_IOU for option in options):
            continue
        overlapping=[i for i,d in enumerate(detections) if box_iou(d['bbox'],candidate['bbox'])>=FUSION_IOU]
        candidate['source']=kind
        if len(overlapping)==1:
            index,=overlapping
            original=detections[index]
            if not original['detection_stable'] or original['confidence']<.5:
                detections[index]={**candidate,'id':original['id']}
        elif not overlapping:
            candidate['id']=f'd{len(detections)+1}'
            detections.append(candidate)


def rack_view_predictions(model,image,tile,scales,labels,*,view_name):
    """Keep real predictions wholly visible at the crop's internal edges."""
    left,top,right,bottom=tile
    options={'reflected':True} if view_name.startswith('reflected') else {}
    if view_name=='reflected_overlap':
        options['nms_iou']=.60
    margin=.006 if view_name=='local' else .008
    predictions=[prediction for scale in scales
                 for prediction in crop_predictions(model,image,tile,0.,scale=scale,**options)]
    for candidate in predictions:
        a,b,c,d=candidate['bbox']
        if ((left>0 and a-left<margin) or (top>0 and b-top<margin)
                or (right<1 and right-c<margin) or (bottom<1 and bottom-d<margin)):
            continue
        linked=rack_labels_under_load(labels,candidate['bbox'])
        if len(linked)==1:
            yield linked[0],{**candidate,'view':(view_name,*tile,candidate['scale'])}


def reread_confusable_skus(reader, image, detections, tokens):
    """Require two OCR reads of the original label crop to agree."""
    suspects = []
    for detection in detections:
        if detection.get('detection_stable') is not True or detection['confidence'] < .50:
            continue
        area = pallet_ocr_box(detection['bbox'])
        local = [token for token in tokens if covered_fraction(token['bbox'],area)>=.95]
        for evidence in product_evidence(local,()):
            digit_runs = CONFUSABLE_SKU_RUN.findall(evidence['sku'])
            if any('O' in run or 'S' in run for run in digit_runs):
                suspects.append(evidence)
    seen = []
    for suspect in sorted(suspects,key=lambda entry:entry['confidence'],reverse=True):
        if any(same_label(suspect,previous) for previous in seen):
            continue
        if len(seen)>=OCR_MAX_LABEL_REREADS:
            break
        seen.append(suspect)
        crop=label_crop(image,suspect['bbox'])
        reads=[]
        for binarize in (False,True):
            reread=[]
            read_region(reader,image,crop,reread,enhance=True,binarize=binarize)
            choices=[entry for entry in product_evidence(reread,()) if entry['confidence']>=OCR_THRESHOLD
                     and same_label(entry,suspect)]
            if len({entry['sku'] for entry in choices})!=1:
                break
            reads.append((reread,max(choices,key=lambda entry:entry['confidence'])))
        if len(reads)!=2 or reads[0][1]['sku']!=reads[1][1]['sku']:
            continue
        selected, evidence = reads[0]
        confidence=min(evidence['confidence'],reads[1][1]['confidence'])
        for token in selected:
            if token['id'] in evidence['ocr_token_ids']:
                tokens.append({**token,'id':f't{len(tokens)+1}','confidence':min(token['confidence'],confidence),
                               'confirmed_reread':True})


def observation_row(rack, floor, detections, tokens, catalog, location_verified, region, all_boxes, reasons):
    matches = match_catalog(tokens, catalog)
    ids = [m['sku'] for m in matches]
    object_matches=[]
    if len(detections)==1:
        object_tokens=[t for t in tokens if covered_fraction(t['bbox'],detections[0]['bbox'])>=.95]
        object_matches=match_catalog(object_tokens,catalog)
    same_product=len(matches)==1 and len(object_matches)==1 and matches[0]['sku']==object_matches[0]['sku']
    spatial = same_product and spatially_verified(object_matches[0]['bbox'],detections[0]['bbox'],region,all_boxes)
    confidence = object_matches[0]['confidence'] if same_product else (matches[0]['confidence'] if len(matches)==1 else 0.)
    stable = len(detections)==1 and detections[0].get('detection_stable') is True
    verified = bool(location_verified and spatial and stable and confidence >= .85 and detections[0]['confidence'] >= .50)
    reasons = list(reasons)
    if not catalog['products']: reasons.append('서버에 상품 목록을 등록해야 합니다.')
    elif not tokens: reasons.append('이 영역에서 OCR이 읽은 글자가 없습니다.')
    elif not matches: reasons.append('OCR 글자에서 등록 상품을 식별하지 못했습니다.')
    if len(matches) > 1: reasons.append('서로 다른 상품 후보가 있어 자동 확정을 보류합니다.')
    if not detections: reasons.append('이 영역에서 적재물을 탐지하지 못했습니다.')
    elif len(detections) > 1: reasons.append('적재물 후보가 여러 개 있어 확인이 필요합니다.')
    if matches and confidence < .85: reasons.append('상품 식별 신뢰도가 자동 확인 기준에 못 미칩니다.')
    if len(detections) == 1 and detections[0]['confidence'] < .50:
        reasons.append('적재물 탐지 신뢰도가 자동 확인 기준에 못 미칩니다.')
    if len(detections)==1 and not stable:
        reasons.append('여러 입력 크기에서 같은 적재물이 반복 확인되지 않았습니다.')
    if not spatial: reasons.append('상품 글자·적재물·위치의 공간 일치를 확인해야 합니다.')
    return {'랙번호': rack, '단수': floor, 'YOLO탐지후보수': len(detections),
            '인식제품ID': ','.join(ids), 'recognized_product_ids': ids,
            'verification_passed': verified, 'location_verified': bool(location_verified),
            'ocr_max_confidence': max((m['confidence'] for m in matches), default=0.),
            'ocr_yolo_spatial_match': bool(spatial), 'product_matches': matches,
            'verified_product_matches':object_matches if same_product else [],
            'detection_stable':bool(stable),
            'ocr_token_ids': [t['id'] for t in tokens],
            'detection_ids': [d['id'] for d in detections], 'review_reasons': reasons,
            '검토상태': '비교준비' if verified else '확인필요'}


def group_overlapping_marks(detections):
    """Keep the raw evidence for comparison and collapse near-duplicates only on the photo."""
    def area(box):
        return (box[2]-box[0]) * (box[3]-box[1])

    visible = []
    for detection in sorted(detections, key=lambda d: (d['location_verified'], area(d['bbox'])), reverse=True):
        parent = next((d for d in visible if box_iou(d['bbox'], detection['bbox']) >= .65
                       or covered_fraction(detection['bbox'], d['bbox']) >= .93), None)
        if parent:
            detection['overlay_parent_id'] = parent['id']
        else:
            visible.append(detection)


def observe_photo(image_path, image, rack_number, models, model_path, *, catalog=None, location_map=None,
                  catalog_path=None, location_map_path=None, company_code=None, warehouse_id=None):
    h, w = image.shape[:2]
    catalog = load_catalog(catalog_path) if catalog is None else catalog
    mapping = load_locations(location_map_path) if location_map is None else location_map
    regions, location_reason = resolve_regions(mapping, image_path, w, h, company_code, warehouse_id)
    model, reader = models
    detections = detect_objects(model,image)
    retry_empty_regions(model, image, detections, regions)
    tokens, processed = [], []
    printed_stickers=catalog.get('printed_rack_stickers',False)
    # The detector may find some loads while missing a different labelled
    # product entirely. Always read the overview, including those missed areas.
    ocr_image=image
    if printed_stickers and max(h,w)>1600:
        scale=1600/max(h,w)
        ocr_image=cv2.resize(image,(round(w*scale),round(h*scale)),interpolation=cv2.INTER_AREA)
    read_region(reader,ocr_image,[0.,0.,1.,1.],tokens,overview=True)
    live_ocr = catalog.get('ocr_rack_labels')
    for region in regions:
        box = region['bbox']
        read_region(reader, ocr_image, pallet_ocr_box(box) if live_ocr else box, tokens)
        processed.append(region['bbox'])
    remaining = [d for d in detections if not any(covered_fraction(d['bbox'], b) >= .95 for b in processed)]
    for detection in remaining[:max(0, OCR_MAX_REGIONS-len(processed))]:
        b = detection['bbox']
        bottom = pallet_ocr_box(b)[3] if live_ocr else min(1., b[3]+.008)
        padded = [max(0., b[0]-.008), max(0., b[1]-.008), min(1., b[2]+.008), bottom]
        read_region(reader, ocr_image, padded, tokens)
        if ocr_image is not image and detection['detection_stable'] and detection['confidence']>=.5 and (
                b[0]<.015 or b[2]>.985):
            read_region(reader,image,padded,tokens)
    truncated = len(remaining) > max(0, OCR_MAX_REGIONS-len(processed))
    if live_ocr:
        if printed_stickers:
            read_printed_rack_stickers(reader,image,tokens)
        recover_live_labels(reader,ocr_image,detections,tokens,rack_only=printed_stickers)
        reread_weak_rack_labels(reader,image,tokens,detections)
        retry_rack_detections(model,image,detections,tokens,test_rack_labels=catalog.get('test_rack_labels',False),
                              printed_stickers=printed_stickers)
        if not printed_stickers:
            for detection in detections:
                if detection.get('source') in RACK_RECOVERY_SOURCES:
                    read_region(reader,image,pallet_ocr_box(detection['bbox']),tokens,enhance=True)
            reread_confusable_skus(reader,image,detections,tokens)
    all_boxes = [d['bbox'] for d in detections]
    for detection in detections:
        local = [t for t in tokens if covered_fraction(t['bbox'], detection['bbox']) >= .95]
        detection['product_ids'] = [m['sku'] for m in match_catalog(local, catalog)]
        detection['ocr_token_ids'] = [t['id'] for t in local]
    by_region = [[] for _ in regions]
    unmapped = []
    for detection in detections:
        eligible = [i for i, region in enumerate(regions) if covered_fraction(detection['bbox'], region['bbox']) >= .5]
        if len(eligible) == 1:
            by_region[eligible[0]].append(detection)
        else:
            unmapped.append(detection)
    rows = []
    for region, local_detections in zip(regions, by_region):
        local_tokens = [t for t in tokens if covered_fraction(t['bbox'], region['bbox']) >= .95]
        row = observation_row(region['rack'], region['floor'], local_detections, local_tokens, catalog,
                              True, region['bbox'], all_boxes, [])
        rows.append(row)
        for detection in local_detections:
            detection.update(rack=region['rack'], floor=region['floor'],
                             verification_passed=row['verification_passed'])
            detection['location_verified']=covered_fraction(detection['bbox'],region['bbox'])>=.95
        group_overlapping_marks(local_detections)
    unmapped_tokens = [t for t in tokens if not any(covered_fraction(t['bbox'], r['bbox']) >= .95 for r in regions)]
    if unmapped or not regions or match_catalog(unmapped_tokens, catalog):
        reasons = [location_reason or '지정한 위치 영역 밖의 후보는 위치 확인이 필요합니다.']
        if truncated: reasons.append('OCR 처리 영역 한도를 초과했습니다. 사진을 나누어 분석해주세요.')
        rows.append(observation_row(rack_number or 'UNMAPPED', None, unmapped, unmapped_tokens,
                                    catalog, False, [0., 0., 1., 1.], all_boxes, reasons))
    product_tokens = {token_id for row in rows for match in row['product_matches']
                      for token_id in match['ocr_token_ids']}
    for token in tokens:
        token['kind'] = ('product' if token['id'] in product_tokens else
                         'location_label' if RACK_LABEL.fullmatch(token['text'].upper().replace(' ', '')) else 'other')
    return {'status': 'analyzed', 'rack': rack_number or ('MAPPED' if regions else 'UNMAPPED'),
            'total_positions': len(rows), 'total_candidates': len(detections),
            'image_width': w, 'image_height': h, 'shelf_boundaries': [],
            'detections': detections, 'results': rows, 'ocr_tokens': tokens,
            'ocr_status': 'partial_limit' if truncated else 'completed', 'ocr_languages': ['en'],
            'catalog_id': catalog.get('catalog_id', 'UNCONFIGURED'),
            'verification_scope': mapping.get('verification_scope', 'unmapped_photo') if regions else 'unmapped_photo',
            'model': {'name': model_path.parent.parent.name, 'file': model_path.name,
                      'imgsz':1280,'scales':list(DETECTION_SCALES),'fusion':'actual_anchor_iou_0.55',
                      'conf': .20, 'iou': .30}}
