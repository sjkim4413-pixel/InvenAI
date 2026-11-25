"""Recover shallow shelf groups using real detector boxes and reread labels."""
import hashlib
import os
from pathlib import Path
import threading

import cv2
import numpy as np
from f03_live_ocr import product_evidence, rack_label_evidence, rack_labels_under_load, rack_readings, same_label, two_label_load, under_load
from f03_multiscale_detection import box_iou, fuse_predictions, predict_boxes
from f03_verification import covered_fraction

SHELF_MODEL_PATH = Path(os.environ.get('IVENAI_SHELF_MODEL_PATH', str(
    Path(__file__).resolve().parent / 'runs/detect/shelf_v37/weights/best.pt')))
SHELF_SOURCE = 'independent_shelf_detector'
DESKEW_SOURCE = 'shelf_tier_deskew'
MAX_SHELF_LABELS = 4
_lock = threading.Lock()
_model = None
_model_key = None
_digest = None


def model_key():
    if not SHELF_MODEL_PATH.is_file():
        return None
    stat = SHELF_MODEL_PATH.stat()
    return str(SHELF_MODEL_PATH), stat.st_size, stat.st_mtime_ns


def shelf_model_digest():
    global _model_key, _model, _digest
    key = model_key()
    with _lock:
        if key != _model_key:
            _model_key, _model, _digest = key, None, None
        if key is not None and _digest is None:
            _digest = hashlib.sha256(SHELF_MODEL_PATH.read_bytes()).hexdigest()
        return _digest


def get_shelf_model():
    global _model_key, _model, _digest
    key = model_key()
    if key is None:
        return None
    with _lock:
        if key != _model_key:
            _model_key, _model, _digest = key, None, None
        if _model is None:
            from ultralytics import YOLO
            _model = YOLO(str(SHELF_MODEL_PATH))
        return _model


def pending_shelf_labels(detections, tokens):
    labels = []
    for label in sorted(rack_label_evidence(tokens), key=lambda item: item['confidence'], reverse=True):
        if not any(same_label(label, other) for other in labels):
            labels.append(label)
    products = [item for item in product_evidence(tokens, ())
                if item['confidence'] >= .85 and item.get('explicit_sku_label') and item.get('confirmed_reread')]
    observed_codes = {item['sku'] for item in product_evidence(tokens, ())}
    pending = []
    for label in labels:
        if label['confidence'] < .85 or any(
                d.get('detection_stable') and under_load(label['bbox'], d['bbox']) and
                (d['confidence'] >= .50 or two_label_load(d,label,tokens,observed_codes,labels,detections))
                for d in detections):
            continue
        a,b,c,d = label['bbox']
        width, height = c-a, d-b
        region = [max(0.,a-.55*width), max(0.,b-min(.30,10*height)), min(1.,c+.55*width), b]
        local = [item for item in products if covered_fraction(item['bbox'], region) >= .95]
        if len({item['sku'] for item in local}) == 1 and local:
            product = max(local, key=lambda item: item['confidence'])
            if all(same_label(product, item) for item in local):
                pending.append((label, product))
    return labels, pending[:MAX_SHELF_LABELS]


def shelf_predictions(model, image, label):
    """Project every context back to the unmodified photo; reject cut-off boxes."""
    h,w = image.shape[:2]
    a,b,c,d = label['bbox']
    center, width, height = (a+c)/2, c-a, d-b
    for context, (sx,sy) in enumerate(((1.6,10.), (2.4,14.), (3.2,18.))):
        l,t,r,bottom = (max(0,round((center-sx*width)*w)), max(0,round((b-sy*height)*h)),
                        min(w,round((center+sx*width)*w)), min(h,round((d+2*height)*h)))
        if min(r-l,bottom-t) < 16:
            continue
        crop = image[t:bottom,l:r]
        for stretch in (1.,1.5):
            tile = cv2.resize(crop,(r-l,round((bottom-t)*stretch)))
            for reflected in (False,True):
                view = tile[:,::-1].copy() if reflected else tile
                for scale in (640,960,1280):
                    for prediction in predict_boxes(model,view,scale,nms_iou=.55):
                        x,y,z,lower = prediction['bbox']
                        if reflected:
                            x,z = 1-z,1-x
                        if ((l>0 and x<.01) or (r<w and z>.99) or (t>0 and y<.01)
                                or (bottom<h and lower>.99)):
                            continue
                        yield {**prediction,'bbox':[(l+x*(r-l))/w,(t+y*(bottom-t))/h,
                                                    (l+z*(r-l))/w,(t+lower*(bottom-t))/h],
                               'view':(label['id'],context,stretch,reflected,scale)}


def recover_shelf_detections(image, detections, tokens, *, model_loader=get_shelf_model):
    labels, pending = pending_shelf_labels(detections,tokens)
    if not pending:
        return
    model = model_loader()
    if model is None:
        return
    proposals = {label['id']:[] for label, _ in pending}
    # A wider context may observe a neighbouring pending load more clearly.
    # Association follows its visible sticker, independent of the crop centre.
    for context, _ in pending:
        for prediction in shelf_predictions(model,image,context):
            if prediction['confidence'] < .25:
                continue
            box = prediction['bbox']
            linked = rack_labels_under_load(labels,box)
            if len(linked) != 1:
                continue
            for label, product in pending:
                if same_label(label,linked[0]) and covered_fraction(product['bbox'],box) >= .95:
                    proposals[label['id']].append(prediction)
    for label, product in pending:
        supported = [d for d in fuse_predictions(proposals[label['id']],view_key='view')
                     if d['confidence'] >= .27 and
                     sum(p['confidence'] >= .25 for p in d['scale_predictions']) >= 2]
        if len(supported) != 1:
            continue
        candidate, = supported
        if any(box_iou(candidate['bbox'],d['bbox']) >= .55 for d in detections):
            continue
        detections.append({**candidate,'id':f'd{len(detections)+1}','source':SHELF_SOURCE})
    recover_deskewed_shelves(model,image,detections,tokens)


def shelf_row_slope(label, labels):
    """Estimate a shelf's tilt from consistent, distinct labels on the same tier."""
    tiers = {parts['tier'] for parts,_ in rack_readings(label['text'])}
    if len(tiers) != 1:
        return None
    row = []
    for other in labels:
        if tiers == {parts['tier'] for parts,_ in rack_readings(other['text'])} and not any(
                same_label(other,seen) for seen in row):
            row.append(other)
    if len(row) < 3:
        return None
    points = [((item['bbox'][0]+item['bbox'][2])/2,(item['bbox'][1]+item['bbox'][3])/2) for item in row]
    slopes = [(v-y)/(u-x) for x,y in points for u,v in points if u-x >= .12]
    if not slopes:
        return None
    slope = float(np.median(slopes))
    offsets = [y-slope*x for x,y in points]
    tolerance = max(.006,float(np.median([item['bbox'][3]-item['bbox'][1] for item in row])))
    if not .01 <= abs(slope) <= .45 or max(offsets)-min(offsets) > tolerance:
        return None
    return slope


def deskewed_shelf_predictions(model, image, label, slope):
    """Flatten the observed beam and retain boxes supported by original pixels."""
    h,w = image.shape[:2]
    a,b,c,d = label['bbox']
    center,width,height = (a+c)/2,c-a,d-b
    l,t,r,bottom = (max(0,round((center-3.2*width)*w)),max(0,round((b-18*height)*h)),
                    min(w,round((center+3.2*width)*w)),min(h,round((d+2*height)*h)))
    if min(r-l,bottom-t) < 16:
        return
    crop = image[t:bottom,l:r]
    pixel_slope = slope*h/w
    pad = int(np.ceil(abs(pixel_slope)*(r-l)/2))
    matrix = np.float32([[1,0,0],[-pixel_slope,1,pad+pixel_slope*(r-l)/2]])
    reverse = cv2.invertAffineTransform(matrix)
    for mode in ('deskew','gray','plain'):
        if mode == 'deskew':
            tile = cv2.warpAffine(crop,matrix,(r-l,bottom-t+2*pad),borderValue=(114,114,114))
        elif mode == 'gray':
            tile = cv2.cvtColor(cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY),cv2.COLOR_GRAY2BGR)
        else:
            tile = crop
        th,tw = tile.shape[:2]
        for stretch in (1.,1.5,2.):
            scaled = cv2.resize(tile,(tw,round(th*stretch)))
            for reflected in (False,True):
                view = scaled[:,::-1].copy() if reflected else scaled
                for scale in (640,960):
                    for prediction in predict_boxes(model,view,scale,nms_iou=.55):
                        x,y,z,low = prediction['bbox']
                        if reflected:
                            x,z = 1-z,1-x
                        corners = np.float32([[[x*tw,y*th],[z*tw,y*th],[z*tw,low*th],[x*tw,low*th]]])
                        if mode == 'deskew':
                            corners = cv2.transform(corners,reverse)
                        left,top = corners[0].min(axis=0)
                        right,lower = corners[0].max(axis=0)
                        # Every corner must land inside the real crop. Padding
                        # and an object cut off by a crop never count as evidence.
                        if not (0<=left<right<=r-l and 0<=top<lower<=bottom-t):
                            continue
                        if ((l>0 and left<.01*(r-l)) or (r<w and right>.99*(r-l))
                                or (t>0 and top<.01*(bottom-t)) or (bottom<h and lower>.99*(bottom-t))):
                            continue
                        yield {**prediction,'bbox':[float((l+left)/w),float((t+top)/h),
                                                   float((l+right)/w),float((t+lower)/h)],
                               'view':('tier-deskew',label['id'],mode,stretch,reflected,scale),
                               'shelf_slope':slope}


def recover_deskewed_shelves(model, image, detections, tokens):
    labels,pending = pending_shelf_labels(detections,tokens)
    proposals = {label['id']:[] for label,_ in pending}
    for context,_ in pending:
        slope = shelf_row_slope(context,labels)
        if slope is None:
            continue
        for prediction in deskewed_shelf_predictions(model,image,context,slope):
            if prediction['confidence'] < .25:
                continue
            linked = rack_labels_under_load(labels,prediction['bbox'])
            if len(linked) != 1:
                continue
            for label,product in pending:
                if same_label(label,linked[0]) and covered_fraction(product['bbox'],prediction['bbox']) >= .95:
                    proposals[label['id']].append(prediction)
    for label,_ in pending:
        supported = []
        for candidate in fuse_predictions(proposals[label['id']],view_key='view'):
            families = {p['view'][2] for p in candidate['scale_predictions'] if p['confidence'] >= .25}
            if candidate['confidence'] >= .27 and 'deskew' in families and len(families) >= 2:
                supported.append(candidate)
        candidate = supported[0] if len(supported) == 1 else original_view_parent(supported)
        if candidate is None:
            continue
        if not any(box_iou(candidate['bbox'],d['bbox']) >= .55 for d in detections):
            detections.append({**candidate,'id':f'd{len(detections)+1}','source':DESKEW_SOURCE})


def original_view_parent(candidates):
    """Resolve nested processed-view fragments with a unique original-colour read.

    Keep the actual parent box and score. Competing original-image reads or
    another extent outside the parent remain ambiguous.
    """
    native = [candidate for candidate in candidates if any(
        p['confidence'] >= .25 and len(p.get('view',())) == 6 and
        p['view'][2] == 'plain' and p['view'][3] == 1. and p['view'][4] is False
        for p in candidate.get('scale_predictions',()))]
    if len(candidates) < 2 or len(native) != 1:
        return None
    parent, = native
    fragments = [candidate for candidate in candidates if candidate is not parent]
    if parent['confidence'] < .50 or any(part['confidence'] >= parent['confidence'] for part in fragments):
        return None
    if not all(covered_fraction(part['bbox'],parent['bbox']) >= .95 and
               covered_fraction(parent['bbox'],part['bbox']) <= .60 for part in fragments):
        return None
    return {**parent,'extent_resolution':'original_colour_parent',
            'nested_processed_candidates':[
                {key:part[key] for key in ('bbox','confidence','scale_predictions')} for part in fragments]}
