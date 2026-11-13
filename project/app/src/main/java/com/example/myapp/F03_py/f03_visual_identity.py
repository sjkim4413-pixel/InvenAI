"""Open-set package appearance matching for the registered ABC-002 fixtures."""
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from f03_catalog import AuditConfigError, read_config
from f03_verification import covered_fraction

MIN_INLIERS = 16
MIN_INLIER_RATIO = .65
MIN_REFERENCE_COVERAGE = .14
MAX_CHROMA_ERROR = 20.
APPEARANCE_METHOD = 'TEST_PACKAGING_APPEARANCE'


def load_visual_assets(catalog_path):
    path = Path(catalog_path).with_name('visual_references.json')
    if not path.is_file():
        return None, None
    config = read_config(path, '테스트 포장 외관')
    images = config.get('images')
    if not isinstance(images, list) or not 1 <= len(images) <= 10:
        raise AuditConfigError('포장 외관 기준 사진 목록이 올바르지 않습니다.')
    assets = []
    for entry in images:
        filename = entry.get('image') if isinstance(entry, dict) else None
        if not isinstance(filename, str) or Path(filename).name != filename or Path(filename).suffix.lower() != '.png':
            raise AuditConfigError('포장 외관 기준 사진 이름이 올바르지 않습니다.')
        try:
            pixels = (path.parent / filename).read_bytes()
        except OSError as exc:
            raise AuditConfigError('포장 외관 기준 사진을 읽을 수 없습니다.') from exc
        if hashlib.sha256(pixels).hexdigest() != entry.get('sha256'):
            raise AuditConfigError('포장 외관 기준 사진이 등록한 원본과 다릅니다.')
        assets.append((entry.get('samples'), pixels))
    # The verified image digests are part of the manifest.
    revision = hashlib.sha256(json.dumps(config, sort_keys=True).encode('utf-8')).hexdigest()
    return assets, revision


class AppearanceClassifier:
    def __init__(self, assets, allowed_skus):
        self.sift = cv2.SIFT_create(nfeatures=950, contrastThreshold=.018)
        self.matcher = cv2.BFMatcher()
        self.references = {}
        for samples, pixels in assets:
            image = cv2.imdecode(np.frombuffer(pixels, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None or not isinstance(samples, list) or not samples:
                raise AuditConfigError('포장 외관 기준 사진 또는 표본을 읽을 수 없습니다.')
            height, width = image.shape[:2]
            for sample in samples:
                if not isinstance(sample, dict):
                    raise AuditConfigError('포장 외관 표본 형식이 올바르지 않습니다.')
                sku, box = sample.get('sku'), sample.get('bbox')
                if not isinstance(sku, str) or sku not in allowed_skus or sku in self.references:
                    raise AuditConfigError('포장 외관 표본의 상품 코드가 등록 목록과 맞지 않습니다.')
                if not isinstance(box, list) or len(box) != 4 or any(type(v) is not int for v in box):
                    raise AuditConfigError('포장 외관 표본 좌표가 올바르지 않습니다.')
                x1, y1, x2, y2 = box
                if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height and min(x2-x1, y2-y1) >= 24):
                    raise AuditConfigError('포장 외관 표본이 기준 사진 영역을 벗어납니다.')
                crop = image[y1:y2, x1:x2]
                points, descriptors = self.features(crop)
                if descriptors is None or len(descriptors) < MIN_INLIERS:
                    raise AuditConfigError('포장 외관 표본에서 충분한 특징을 찾지 못했습니다.')
                chroma = cv2.cvtColor(crop, cv2.COLOR_BGR2Lab)[:, :, 1:].copy()
                self.references[sku] = points, descriptors, chroma

    def features(self, image):
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
        keypoints, descriptors = self.sift.detectAndCompute(gray, None)
        return np.float32([point.pt for point in keypoints]), descriptors

    def identify(self, crop):
        if crop.size == 0:
            return []
        points, descriptors = self.features(crop)
        if descriptors is None or len(descriptors) < 2:
            return []
        target_chroma = cv2.cvtColor(crop, cv2.COLOR_BGR2Lab)[:, :, 1:].copy() if crop.ndim == 3 else None
        results = []
        for sku, (reference_points, reference_descriptors, reference_chroma) in self.references.items():
            pairs = self.matcher.knnMatch(reference_descriptors, descriptors, k=2)
            by_target = {}
            for first, second in pairs:
                if first.distance < .76 * second.distance:
                    prior = by_target.get(first.trainIdx)
                    if prior is None or first.distance < prior.distance:
                        by_target[first.trainIdx] = first
            matches = list(by_target.values())
            if len(matches) < MIN_INLIERS:
                continue
            source = np.float32([reference_points[m.queryIdx] for m in matches])
            target = np.float32([points[m.trainIdx] for m in matches])
            matrix, mask = cv2.findHomography(source, target, cv2.RANSAC, 3.)
            if matrix is None or mask is None:
                continue
            inliers = mask.ravel().astype(bool)
            count = int(inliers.sum())
            ratio = count / len(matches)
            if count < MIN_INLIERS or ratio < MIN_INLIER_RATIO:
                continue
            height, width = reference_chroma.shape[:2]
            reference_area = width * height
            coverage = cv2.contourArea(cv2.convexHull(source[inliers])) / reference_area
            if coverage < MIN_REFERENCE_COVERAGE:
                continue
            quad = cv2.perspectiveTransform(np.float32([[[0,0],[width,0],[width,height],[0,height]]]), matrix)[0]
            if not np.isfinite(quad).all() or not cv2.isContourConvex(quad):
                continue
            projected_area = cv2.contourArea(quad, oriented=True)
            if not .25 <= projected_area / reference_area <= 4.:
                continue
            x1, y1 = quad.min(axis=0)
            x2, y2 = quad.max(axis=0)
            if x1 < -2 or y1 < -2 or x2 > crop.shape[1]+2 or y2 > crop.shape[0]+2:
                continue
            chroma_error = None
            if target_chroma is not None:
                aligned = cv2.warpPerspective(target_chroma, matrix, (width,height),
                                              flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
                delta = cv2.absdiff(aligned, reference_chroma)[3:-3,3:-3]
                chroma_error = float(np.percentile(delta.max(axis=2), 80))
                if chroma_error > MAX_CHROMA_ERROR:
                    continue
            results.append({'sku':sku, 'inliers':count, 'inlier_ratio':round(ratio, 4),
                            'reference_coverage':round(coverage, 4),
                            'chroma_error':chroma_error, 'region_coverage':round(projected_area / (crop.shape[0]*crop.shape[1]), 4),
                            'bbox':[float(x1), float(y1), float(x2), float(y2)]})
        return results


def appearance_locates_package(match, region):
    return (match['inliers'] >= 30 and match['inlier_ratio'] >= .78
            and match['reference_coverage'] >= .30 and match['region_coverage'] >= .15
            and match['chroma_error'] is not None and match['chroma_error'] <= MAX_CHROMA_ERROR
            and covered_fraction(match['bbox'], region) >= .98)


def add_visual_observations(image_path, raw, mapping, catalog, assets):
    image = cv2.imdecode(np.frombuffer(image_path.read_bytes(), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError('포장 외관을 확인할 사진을 읽을 수 없습니다.')
    classifier = AppearanceClassifier(assets, {product['sku'] for product in catalog['products']})
    height, width = image.shape[:2]
    regions = {(r['rack'], r['floor']): r['bbox'] for r in mapping['images'][0]['regions']}
    detections = {d['id']: d for d in raw['detections']}
    for row in raw['results']:
        region = regions.get((row['랙번호'], row['단수']))
        if not region or not row['location_verified']:
            continue
        x1, y1, x2, y2 = [round(v * d) for v, d in zip(region, (width, height) * 2)]
        matches = classifier.identify(image[y1:y2, x1:x2])
        if not matches:
            continue
        for match in matches:
            a, b, c, d = match['bbox']
            match['bbox'] = [max(0., (x1+a)/width), max(0., (y1+b)/height),
                             min(1., (x1+c)/width), min(1., (y1+d)/height)]
        observed = list(dict.fromkeys([*row['recognized_product_ids'], *(match['sku'] for match in matches)]))
        row.update(visual_matches=matches, recognized_product_ids=observed, 인식제품ID=','.join(observed))
        if len(observed) != 1:
            row['verification_passed'] = False
            row['review_reasons'].append('포장 외관 또는 OCR에서 서로 다른 상품 후보가 확인됐습니다.')
            continue
        if row['verification_passed']:
            continue  # Existing independent OCR evidence already verifies the product.
        detection_ids = row['detection_ids']
        detection = detections[detection_ids[0]] if len(detection_ids) == 1 else None
        yolo_supported = (detection is not None and detection['location_verified'] and detection['detection_stable']
                          and detection['confidence'] >= .50 and covered_fraction(matches[0]['bbox'], detection['bbox']) >= .95)
        localized_by_appearance = not yolo_supported and appearance_locates_package(matches[0], region)
        if not yolo_supported and not localized_by_appearance:
            row['review_reasons'].append('포장 외관 후보가 위치를 확인하기에 충분히 뚜렷하지 않습니다.')
            continue
        row.update(identity_method=APPEARANCE_METHOD, appearance_location_verified=localized_by_appearance,
                   verification_passed=True, 검토상태='비교준비',
                   review_reasons=['등록한 테스트 포장과 사진 속 적재물의 무늬가 일치합니다.'])
        if yolo_supported:
            detection.update(product_ids=observed, identity_method=row['identity_method'])
    raw['visual_status'] = 'completed'
