"""Resolve bundled test photos and compare their pixel evidence with Firestore."""
import logging
import os
import json
import re
from pathlib import Path

from f03_catalog import AuditConfigError, load_locations
from f03_live_inventory import InventoryInputError, InventoryUnavailable, load_company_inventory, warehouse_view
from ivenai_erp_matcher import compare_inventory

COMPANY = 'ABC-002'
WAREHOUSE = 'AI 이미지 테스트 창고'
ADDITIONAL_WAREHOUSE = 'AI 실사 추가 테스트 창고'
BACKEND_DIR = Path(__file__).resolve().parent
DEFAULT_FIXTURE_DIR = next(
    (parent / 'test_image_ai' for parent in BACKEND_DIR.parents
     if (parent / 'test_image_ai' / 'location_map.json').is_file()),
    BACKEND_DIR / 'test_image_ai',
)
FIXTURE_DIR = Path(os.environ.get('IVENAI_TEST_IMAGE_DIR', str(DEFAULT_FIXTURE_DIR)))


def fixture_configuration(upload, company, warehouse_id):
    if company != COMPANY or warehouse_id != 1:
        return None
    map_path = FIXTURE_DIR / 'location_map.json'
    if not map_path.is_file():
        return None
    mapping = load_locations(map_path)
    for photo in mapping['images']:
        if upload.get('source_sha256') in photo['sha256'] or upload['sha256'] in photo['sha256']:
            # The source digest is from the server upload, so this works across
            # JPEG encoder versions as well as with existing normalized files.
            return FIXTURE_DIR / 'product_catalog.json', map_path, {
                **mapping, 'company_code': company, 'images': [{**photo, 'sha256': [upload['sha256']]}],
            }
    return None


def fixture_inventory(company, *, id_token=None):
    try:
        return load_company_inventory(company, warehouse=WAREHOUSE, id_token=id_token)
    except (InventoryInputError, InventoryUnavailable):
        logging.getLogger(__name__).warning('Test warehouse inventory unavailable', exc_info=True)
        return None


def fixture_rack_codes(upload, source, warehouse_id):
    """Select the test inventory that belongs to this bundled photo."""
    profile = fixture_configuration(upload, source['company_code'], warehouse_id)
    photo_reference = (profile[2]['images'][0].get('photo_reference') if profile else
                       additional_fixture_photo(upload,source['company_code'],warehouse_id))
    if not photo_reference:
        raise InventoryInputError('내장 테스트 이미지에서만 랙 라벨을 상품 코드로 사용할 수 있습니다.')
    records = [record for record in source['records']
               if photo_reference in (record.get('test_photo_reference'),record.get('alternate_test_photo_reference'))
               and (record['warehouse'].startswith(WAREHOUSE + ' ') if profile
                    else record['warehouse']==ADDITIONAL_WAREHOUSE)]
    warehouses = {record['warehouse'] for record in records}
    if len(warehouses) != 1:
        raise InventoryInputError('이 테스트 이미지에 연결된 전산 창고를 확인할 수 없습니다.')
    warehouse, = warehouses
    positions = warehouse_view({**source, 'records': records}, warehouse)
    single_item_positions = {position['position_id'] for position in positions if len(position['items']) == 1}
    test_codes = {
        record['position_id']: record['test_photo_label']
        for record in records
        if record['position_id'] in single_item_positions
        and record['test_photo_label'] and not record['test_photo_label_issue']
        and record['source_location'] == record['test_photo_label']
        and record['status'] == 'NORMAL' and record['quantity'] > 0
    }
    return warehouse, positions, test_codes


def additional_fixture_photo(upload,company,warehouse_id):
    if company!=COMPANY or warehouse_id!=1:
        return None
    path=FIXTURE_DIR/'rack_labelled_photos.json'
    if not path.is_file():
        return None
    try:
        photos=json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(photos,dict) or any(
            not re.fullmatch(r'QA[A-D](?:_closeup)?_rack_labels\.png',name)
            or not isinstance(digest,str) or not re.fullmatch(r'[a-f0-9]{64}',digest)
            for name,digest in photos.items()
        ) or len(set(photos.values()))!=len(photos):
            raise ValueError('invalid photo manifest')
    except (OSError,ValueError) as exc:
        raise AuditConfigError('추가 테스트 이미지 목록을 확인할 수 없습니다.') from exc
    return next((name for name,digest in photos.items() if digest==upload.get('source_sha256')),None)


def review_fixture(raw, inventory):
    expected, needs_review = {}, set()
    if inventory:
        for record in inventory['records']:
            key = (f"{record['zone']}-{record['rack']}", record['floor'])
            expected.setdefault(key, []).append(record['sku'])
            if record['status'] != 'NORMAL' or record['quantity'] <= 0 or record['side']:
                needs_review.add(key)
        for products in expected.values():
            products.sort()

    compared = compare_inventory(raw, expected, allow_appearance_location=True)
    observed = {(row['랙번호'], row['단수']): row for row in raw['results']}
    for row in compared['results']:
        key = (row['rack'], row['floor'])
        evidence = observed[key]
        row['visual_matches'] = evidence.get('visual_matches', [])
        row['identity_method'] = evidence.get('identity_method')
        row['appearance_location_verified'] = evidence.get('appearance_location_verified', False)
        row['quantity_verified'] = False
        confirmed = 5
        if row['location_verified']:
            if inventory is None:
                row.update(verification_passed=False, review_status='ERP조회실패_확인필요')
            elif inventory['issues'] or key in needs_review:
                row.update(verification_passed=False, review_status='ERP데이터_확인필요')
            elif row['verification_passed']:
                confirmed = 1
            elif row['observation_verified'] and row['review_status'] == '미등록_또는_오적재의심':
                confirmed = 2
        row['product_location_verified'] = confirmed == 1
        row['f04_status_code'] = confirmed
        match_status = ('TEST_APPEARANCE_LOCATION_MATCH' if row['identity_method'] == 'TEST_PACKAGING_APPEARANCE'
                        else 'PRODUCT_LOCATION_MATCH')
        row['f04_status'] = {1: match_status, 2: 'MISPLACED'}.get(
            confirmed, 'MANUAL_REVIEW_REQUIRED',
        )
    return {**compared, 'data_source': 'FIRESTORE_TEST_ERP' if inventory is not None else 'FIRESTORE_UNAVAILABLE',
            'comparison_module': 'F04_TEST_PHOTO_FIRESTORE_REVIEW', 'erp_backend': 'FIRESTORE',
            'erp_comparison_available': inventory is not None, 'erp_warehouse': WAREHOUSE,
            'inventory_issues': inventory['issues'] if inventory else [],
            'firebase_inventory_modified': False}
