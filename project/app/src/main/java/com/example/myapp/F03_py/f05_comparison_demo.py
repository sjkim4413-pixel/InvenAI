"""Hash-scoped F05 UI fixtures with real OCR and explicit human-annotated regions.

These fixtures never claim YOLO object verification or stock quantity verification.
Both photos use the same regions and the same current authoritative inventory.
"""
import json
import re

from fastapi import HTTPException
from f03_fixture_inventory import FIXTURE_DIR
from f03_live_inventory import InventoryInputError, digest, warehouse_view
from f03_live_ocr import OCR_THRESHOLD, product_evidence, rack_readings
from f03_photo_observer import read_region
from f03_storage import atomic_json, job_lock, utc_now
from f03_verification import covered_fraction

TEST_PRODUCT_SUFFIX = '.test-product-regions.json'
MODE = 'test_product_regions'
SCOPE = 'test_annotated_product_locations'


def require(value):
    if not value:
        raise ValueError('Invalid comparison profile')


def comparison_profile(upload, company, warehouse_id):
    try:
        profile = json.loads((FIXTURE_DIR / 'product_comparison.json').read_text(encoding='utf-8-sig'))
        require(profile['version'] == 1)
        require(isinstance(profile['company_code'], str) and isinstance(profile['warehouse'], str))
        photos, regions = profile['photos'], profile['regions']
        require(isinstance(photos, dict) and 2 <= len(photos) <= 8)
        require(all(re.fullmatch(r'compare_[0-9]{2}_[a-z]+\.png', name)
                    and isinstance(value, str) and re.fullmatch(r'[a-f0-9]{64}', value) for name, value in photos.items()))
        require(len(set(photos.values())) == len(photos))
        require(isinstance(regions, list) and 1 <= len(regions) <= 12)
        require(len({row['location'] for row in regions}) == len(regions))
        for row in regions:
            require(isinstance(row['location'], str) and any(rack_readings(row['location'])))
            for field in ('bbox', 'load_bbox'):
                box = row[field]
                require(len(box) == 4 and all(type(v) in (int, float) and 0 <= v <= 1 for v in box))
                require(box[0] < box[2] and box[1] < box[3])
            require(covered_fraction(row['load_bbox'], row['bbox']) >= .999)
        for index, row in enumerate(regions):
            require(all(covered_fraction(row['bbox'], other['bbox']) == 0 for other in regions[index+1:]))
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise InventoryInputError('상품 비교 테스트의 등록 정보를 확인할 수 없습니다.') from exc
    if company != profile['company_code'] or warehouse_id != 1:
        raise InventoryInputError('이 회사·창고에서 사용할 수 있는 비교 테스트가 아닙니다.')
    if upload.get('source_sha256') not in photos.values():
        raise InventoryInputError('등록된 정상·오적재 비교 사진을 선택해 주세요.')
    return profile


def read_test_regions(image, reader, regions):
    observations, tokens = [], []
    for index, region in enumerate(regions, 1):
        reads = []
        for view in range(2):
            observed = []
            read_region(reader, image, region['bbox'], observed, enhance=bool(view))
            for token in observed:
                token['id'] = f'region{index}-read{view+1}-{token["id"]}'
            reads.append(observed)
            tokens.extend(observed)
        observations.append({'location': region['location'], 'reads': reads})
    return {'observations': observations, 'ocr_tokens': tokens,
            'image_width': image.shape[1], 'image_height': image.shape[0], 'ocr_status': 'completed'}


def observed_identity(reads, registered):
    codes, confidence = [], []
    for tokens in reads:
        evidence = [item for item in product_evidence(tokens, registered)
                 if item['confidence'] >= OCR_THRESHOLD and item.get('explicit_sku_label')
                 and not item.get('requires_separator_reread') and not item.get('confusable_skus')
                 and item['sku'][-1:] not in ('-', '_', '/', '.')]
        found = {item['sku'] for item in evidence}
        if len(found) != 1:
            return None
        codes.append(next(iter(found)))
        confidence.append(max(item['confidence'] for item in evidence))
    # Require two agreeing reads at the regular OCR threshold and at least one
    # strong reading. Neither the image name nor the expected location SKU is used.
    return codes[0] if len(codes) == 2 and codes[0] == codes[1] and max(confidence) >= .85 else None


def photographed_location_matches(reads, location):
    return len(reads) == 2 and all(any(
        token['confidence'] >= .80 and any(
            f"{parts['rack']}-{parts['tier']}F-{parts['side']}" == location
            for parts, _ in rack_readings(token['text']) if parts['side'])
        for token in tokens) for tokens in reads)


def compare_test_regions(raw, source, positions, profile):
    registered = {product['sku'] for product in source['products']}
    by_location = {position['source_location']: position for position in positions}
    rows, detections = [], []
    for index, (region, observation) in enumerate(zip(profile['regions'], raw['observations']), 1):
        position = by_location[region['location']]
        expected = [item['sku'] for item in position['items']]
        sku = observed_identity(observation['reads'], registered)
        location_read = photographed_location_matches(observation['reads'], region['location'])
        stock_ready = (not source['issues'] and len(position['items']) == 1
                       and position['items'][0]['quantity'] > 0 and position['items'][0]['status'] == 'NORMAL')
        # Fixed locations are human annotations for the two hash-allowlisted
        # photos. OCR is used for product identity, not claimed as rack detection.
        ready = bool(sku and stock_ready)
        match = ready and expected == [sku]
        code = 1 if match else (2 if sku in registered else 4) if ready else 5
        decision = {1: 'TEST_PRODUCT_LOCATION_MATCH', 2: 'TEST_PRODUCT_MISPLACED',
                    4: 'TEST_PRODUCT_UNREGISTERED', 5: 'MANUAL_REVIEW_REQUIRED'}[code]
        label = {1: '비교 테스트 상품·위치 일치', 2: '비교 테스트 오적재',
                 4: '비교 테스트 미등록 상품', 5: '비교 테스트 확인 필요'}[code]
        detection_id = f'test-region-{index}'
        reasons = ['F05 검증용으로 사람이 표시한 위치 영역입니다. YOLO 탐지 성능을 측정하지 않습니다.',
                   '위치는 사전 표시 영역을 사용하고, 상품 코드는 원본·대비 보정 영상에서 각각 읽습니다.',
                   '합성 비교 사진이며 수량은 확인하지 않습니다.']
        if not sku: reasons.append('두 판독에서 동일한 상품 코드를 확인하지 못했습니다.')
        if not location_read: reasons.append('위치 라벨 OCR은 불충분하여 사람이 표시한 테스트 위치를 사용했습니다.')
        if not stock_ready: reasons.append('현재 전산 위치의 정상 재고 정보가 불완전합니다.')
        row = {'rack': position['rack'], 'floor': position['floor'], 'side': position['side'],
               'zone': position['zone'], 'position_id': position['position_id'],
               'location_label': region['location'], 'location_bbox': region['bbox'],
               'erp_product_ids': expected, 'ai_product_ids': [sku] if sku else [], 'ai_product_id': sku or '',
               'product_names': {p['sku']: p.get('name', '') for p in source['products']},
               'ocr_max_confidence': max((p['confidence'] for view in observation['reads']
                   for p in product_evidence(view, registered) if p['sku'] == sku), default=0),
               'yolo_candidates': 0, 'review_status': label, 'review_reasons': reasons,
               'location_verified': True, 'location_method': 'human_annotated_test_region',
               'location_label_ocr_confirmed': location_read,
               'verification_passed': False, 'product_location_verified': False, 'product_identity_verified': False,
               'test_product_identity_verified': bool(sku), 'test_product_location_match': bool(match),
               'quantity_verified': False, 'observed_quantity': None,
               'expected_quantity': position['items'][0]['quantity'] if len(position['items']) == 1 else None,
               'identity_method': 'TEST_REGION_OCR_PRODUCT_CODE' if sku else None,
               'detection_ids': [detection_id], 'verified_detection_id': detection_id if ready else None,
               'fragment_detection_ids': [], 'product_matches': [],
               'ocr_token_ids': [token['id'] for view in observation['reads'] for token in view],
               'f04_status_code': code, 'f04_status': decision}
        rows.append(row)
        detections.append({'id': detection_id, 'bbox': region['load_bbox'], 'confidence': None,
                           'source': 'manually_annotated_f05_test_region', 'detection_stable': False,
                           'location_verified': True, 'location_label_ocr_confirmed': location_read,
                           'verification_passed': False,
                           'product_location_verified': False, 'test_product_location_match': bool(match),
                           'product_ids': [sku] if sku else [], 'review_status': label})
    return {'status': 'compared', 'rack': 'TEST_REGIONS', 'total_positions': len(rows), 'results': rows,
            'detections': detections, 'data_source': 'FIRESTORE_TEST_ERP', 'erp_backend': 'FIRESTORE',
            'erp_comparison_available': True, 'erp_warehouse': profile['warehouse'],
            'verification_scope': SCOPE, 'comparison_module': 'F05_ANNOTATED_PRODUCT_COMPARISON',
            'detector_status': 'not_evaluated_in_f05_region_test', 'quantity_verified': False,
            'firebase_inventory_modified': False, 'inventory_issues': source['issues'],
            'product_names': {product['sku']: product.get('name', '') for product in source['products']},
            'ocr_tokens': raw['ocr_tokens'], 'ocr_status': raw['ocr_status'],
            'image_width': raw['image_width'], 'image_height': raw['image_height'],
            'ocr_languages': ['en'], 'total_candidates': len(rows), 'codes': []}


def run_comparison_demo(request, filename, warehouse_id, user, warehouse_code, directory, image, upload, source, *, raw_only=False):
    try:
        profile = comparison_profile(upload, source['company_code'], warehouse_id)
        if warehouse_code is not None and warehouse_code != profile['warehouse']:
            raise InventoryInputError('비교 테스트에 등록된 창고를 선택해 주세요.')
        positions = warehouse_view(source, profile['warehouse'])
        if {position['source_location'] for position in positions} != {row['location'] for row in profile['regions']}:
            raise InventoryInputError('비교 테스트의 전산 위치 정보를 확인해 주세요.')
    except InventoryInputError as exc:
        raise HTTPException(422, detail={'code': 'COMPARISON_TEST_UNAVAILABLE', 'message': str(exc)}) from exc
    revision = {'pipeline': request.app.state.pipeline_version, 'image_sha256': upload['sha256'],
                'fixture_sha256': digest(profile), 'inventory_revision': source['revision'], 'mode': MODE}
    signature = digest(revision)
    ai_signature = digest({key: value for key, value in revision.items() if key != 'inventory_revision'})
    target = directory / (filename + TEST_PRODUCT_SUFFIX)
    with job_lock(directory, filename):
        cached = json.loads(target.read_text(encoding='utf-8')) if target.is_file() else {}
        if cached.get('signature') == signature:
            return cached['analysis' if raw_only else 'comparison']
        raw = cached.get('analysis') if cached.get('ai_signature') == ai_signature else None
        if raw is None:
            acquired = request.app.state.analysis_slot.acquire(blocking=False)
            if not acquired:
                raise HTTPException(429, detail={'code': 'ANALYSIS_BUSY', 'message': '다른 사진 분석이 끝난 뒤 다시 시도해주세요.'})
            try:
                import ivenai_integrated_ai as pipeline
                with pipeline._inference_lock:
                    _, reader = pipeline.get_ai_models()
                    raw = read_test_regions(pipeline.read_image(image), reader, profile['regions'])
            finally:
                request.app.state.analysis_slot.release()
        result = compare_test_regions(raw, source, positions, profile)
        result.update(module='F03', filename=filename, job_id=filename[:-4], company_code=user.company,
                      warehouse_id=warehouse_id, warehouse_code=profile['warehouse'], revision=revision,
                      model={'name': 'OCR_WITH_ANNOTATED_TEST_REGIONS', 'yolo_evaluated': False}, analyzed_at=utc_now())
        atomic_json(target, {'signature': signature, 'ai_signature': ai_signature, 'analysis': raw, 'comparison': result})
        return raw if raw_only else result
