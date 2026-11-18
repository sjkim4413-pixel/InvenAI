"""Fuse repeated object observations without inventing boxes or confidence.

An anchor is always an actual highest-confidence prediction. Two objects from
the same inference can never collapse into one stock observation.
"""
import math
from f03_verification import covered_fraction

DETECTION_SCALES = (640, 1024, 1280)
FUSION_IOU = .55
MAX_REGION_RETRIES = 12
MIRROR_RETRY_SCALE = 1536
MIRROR_BANDS = ((0., .66), (.33, 1.))
PERSPECTIVE_RETRY_SOURCE = 'rack_label_perspective_retry'
PERSPECTIVE_EXTENSION_SOURCE = 'rack_label_perspective_extension'
PERSPECTIVE_SOURCES = (PERSPECTIVE_RETRY_SOURCE, PERSPECTIVE_EXTENSION_SOURCE)
PERSPECTIVE_MIN_CONFIDENCE = .30


def box_iou(a, b):
    intersection = max(0., min(a[2], b[2])-max(a[0], b[0])) * max(0., min(a[3], b[3])-max(a[1], b[1]))
    union = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - intersection
    return intersection/union if union > 0 else 0.


def fuse_predictions(predictions, *, view_key='scale'):
    clusters = []
    for prediction in sorted(predictions, key=lambda p: p['confidence'], reverse=True):
        candidates = [cluster for cluster in clusters if
                      all(p[view_key] != prediction[view_key] for p in cluster)
                      and box_iou(cluster[0]['bbox'], prediction['bbox']) >= FUSION_IOU]
        # Ambiguous associations stay separate; averaging/merging would hide
        # the possibility of two neighbouring loads.
        if len(candidates) == 1:
            candidates[0].append(prediction)
        else:
            clusters.append([prediction])
    detections = []
    for cluster in clusters:
        anchor = cluster[0]
        scales = sorted({p['scale'] for p in cluster})
        detections.append({'id': f'd{len(detections)+1}', 'bbox': list(anchor['bbox']),
                           'confidence': anchor['confidence'], 'support_scales': scales,
                           'detection_stable': len(cluster) >= 2,
                           'scale_predictions': cluster,
                           'floor': None, 'rack': None, 'product_ids': [],
                           'verification_passed': False, 'location_verified': False})
    return detections


def predict_boxes(model, image, scale, *, nms_iou=.30):
    h, w = image.shape[:2]
    predictions = []
    result = model.predict(source=image, imgsz=scale, conf=.20, iou=nms_iou, verbose=False, save=False)[0]
    for box in result.boxes:
        coordinates = list(map(float, box.xyxy[0].tolist()))
        confidence = float(box.conf[0])
        if not all(math.isfinite(v) for v in coordinates+[confidence]) or not 0 <= confidence <= 1:
            raise ValueError('탐지 모델이 올바르지 않은 좌표/신뢰도를 반환했습니다.')
        bounds = [max(0., coordinates[0]/w), max(0., coordinates[1]/h),
                  min(1., coordinates[2]/w), min(1., coordinates[3]/h)]
        if bounds[2] <= bounds[0] or bounds[3] <= bounds[1]: continue
        predictions.append({'bbox': bounds, 'confidence': confidence, 'scale': scale})
    return predictions


def perspective_consensus(views):
    families = {view[0] for view in views}
    return 'contrast' in families and len(families) >= 3


def perspective_extension_consensus(predictions):
    strong_views = {p['view'][0] for p in predictions if p['confidence'] >= .65}
    return len(strong_views) >= 2 or len({p['view'][0] for p in predictions}) >= 3


def supports_load_extension(part, full):
    """Require the original box to cover the same sides and most of the upper load."""
    for prediction in part.get('scale_predictions') or (part,):
        box = prediction['bbox']
        horizontal = min(box[2], full[2]) - max(box[0], full[0])
        if (horizontal >= .80*max(box[2]-box[0], full[2]-full[0]) and box[3] < full[3]
                and covered_fraction(box, full) >= .85 and box_iou(box, full) >= .45):
            return True
    return False


def perspective_rack_predictions(model, image, *, extended=False):
    """Keep original and rectified detector evidence in the same coordinates."""
    import cv2
    import numpy as np

    height, width = image.shape[:2]
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    lab[:, :, 0] = cv2.createCLAHE(clipLimit=2., tileGridSize=(12, 12)).apply(lab[:, :, 0])
    contrast = cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)
    scales = (960, 1024, 1152, 1280) if extended else (960, 1408)
    if not extended:
        for scale in scales:
            for prediction in predict_boxes(model, contrast, scale, nms_iou=.55):
                yield {**prediction, 'view': ('contrast', scale)}

    frame = np.float32([[0, 0], [width, 0], [width, height], [0, height]])
    for direction in ('left', 'right'):
        for fraction in ((.37, .42) if extended else (.17, .30)):
            inset = height*fraction
            target = ([[0, inset], [width, 0], [width, height], [0, height-inset]] if direction == 'left'
                      else [[0, 0], [width, inset], [width, height-inset], [0, height]])
            matrix = cv2.getPerspectiveTransform(frame, np.float32(target))
            inverse = np.linalg.inv(matrix)
            rectified = cv2.warpPerspective(contrast, matrix, (width, height), borderMode=cv2.BORDER_REPLICATE)
            for reflected in ((False, True) if extended else (False,)):
                view = rectified[:, ::-1].copy() if reflected else rectified
                for scale in scales:
                    for prediction in predict_boxes(model, view, scale, nms_iou=.55):
                        a, b, c, d = prediction['bbox']
                        if reflected:
                            a, c = 1-c, 1-a
                        polygon = np.float32([[[a*width, b*height], [c*width, b*height],
                                               [c*width, d*height], [a*width, d*height]]])
                        corners = cv2.perspectiveTransform(polygon, inverse)[0]
                        left, top = corners.min(axis=0)
                        right, bottom = corners.max(axis=0)
                        bounds = [max(0., float(left/width)), max(0., float(top/height)),
                                  min(1., float(right/width)), min(1., float(bottom/height))]
                        if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                            yield {**prediction, 'bbox': bounds,
                                   'view': (f'{direction}-{fraction}', scale, reflected)}


def detect_objects(model, image):
    return fuse_predictions([
        prediction for scale in DETECTION_SCALES
        for prediction in predict_boxes(model, image, scale)
    ])


def retry_empty_regions(model, image, detections, regions):
    """Retry mapped gaps. Supplemental predictions never establish stability."""
    empty_regions = [
        region['bbox'] for region in regions
        if not any(covered_fraction(d['bbox'], region['bbox']) >= .5 for d in detections)
    ]
    gaps = []
    for region in empty_regions[:MAX_REGION_RETRIES]:
        predictions = crop_predictions(model, image, region, padding=0.)
        if not append_region_predictions(detections, predictions, 'mapped_region_retry'):
            gaps.append(region)
    remaining = []
    for region in gaps:
        context = [
            prediction for padding in (.15, .35)
            for prediction in crop_predictions(model, image, region, padding)
        ]
        if not append_region_predictions(detections, local_predictions(context, region), 'mapped_context_retry'):
            remaining.append(region)
    if not remaining:
        return

    tiles = [(top, bottom, mirror_band_predictions(model, image, top, bottom))
             for top, bottom in MIRROR_BANDS
             if any(top <= region[1] and region[3] <= bottom for region in remaining)]
    for region in remaining:
        predictions = [p for top, bottom, boxes in tiles
                       if top <= region[1] and region[3] <= bottom for p in boxes]
        append_region_predictions(detections, local_predictions(predictions, region), 'mapped_mirror_band_retry')


def local_predictions(predictions, region):
    return [p for p in predictions if covered_fraction(p['bbox'], region) >= .80
            and covered_fraction(region, p['bbox']) >= .18]


def mirror_band_predictions(model, image, top, bottom):
    height = image.shape[0]
    y1, y2 = round(top*height), round(bottom*height)
    predictions = predict_boxes(model, image[y1:y2, ::-1].copy(), MIRROR_RETRY_SCALE)
    for prediction in predictions:
        a, b, c, d = prediction['bbox']
        prediction['bbox'] = [1.-c, (y1+b*(y2-y1))/height, 1.-a, (y1+d*(y2-y1))/height]
    return predictions


def crop_predictions(model, image, region, padding, *, scale=DETECTION_SCALES[0], reflected=False, nms_iou=.30):
    height, width = image.shape[:2]
    x1, y1, x2, y2 = [round(value * size) for value, size in zip(region, (width, height) * 2)]
    pad_x, pad_y = round((x2-x1)*padding), round((y2-y1)*padding)
    x1, y1, x2, y2 = max(0, x1-pad_x), max(0, y1-pad_y), min(width, x2+pad_x), min(height, y2+pad_y)
    crop = image[y1:y2, x1:x2]
    if min(crop.shape[:2]) < 16:
        return []
    predictions = predict_boxes(model, crop[:, ::-1].copy() if reflected else crop, scale, nms_iou=nms_iou)
    for prediction in predictions:
        a, b, c, d = prediction['bbox']
        if reflected:
            a, c = 1.-c, 1.-a
        prediction['bbox'] = [(x1+a*(x2-x1))/width, (y1+b*(y2-y1))/height,
                              (x1+c*(x2-x1))/width, (y1+d*(y2-y1))/height]
    return predictions


def append_region_predictions(detections, predictions, source):
    added = False
    for prediction in sorted(predictions, key=lambda p: p['confidence'], reverse=True):
        if any(box_iou(prediction['bbox'], d['bbox']) >= FUSION_IOU for d in detections):
            continue
        candidate, = fuse_predictions([prediction])
        candidate.update(id=f'd{len(detections)+1}', source=source)
        detections.append(candidate)
        added = True
    return added
