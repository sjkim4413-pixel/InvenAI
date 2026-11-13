import csv
from collections import Counter
from pathlib import Path
from f03_verification import product_ids

BASE_DIR = Path(__file__).resolve().parent
ERP_FILE = BASE_DIR / 'IvenAI_ERP_25_positions.csv'


class ERPDataError(ValueError):
    """Invalid source data must never produce a stock verification."""


def load_erp(path=None):
    path = Path(path) if path is not None else ERP_FILE
    try:
        with path.open(encoding='utf-8-sig', newline='') as stream:
            reader = csv.DictReader(stream)
            required = {'랙번호','단수','ERP_왼쪽제품','ERP_오른쪽제품','ERP_등록수'}
            if not required.issubset(reader.fieldnames or []):
                raise ERPDataError('ERP 필수 컬럼이 없습니다.')
            records = list(reader)
    except (OSError, UnicodeError) as exc:
        raise ERPDataError('ERP 데이터를 읽을 수 없습니다.') from exc
    if not records:
        raise ERPDataError('ERP 위치 데이터가 비어 있습니다.')
    result = {}
    for line, row in enumerate(records, 2):
        if None in row or any(value is None for value in row.values()):
            raise ERPDataError(f'ERP {line}행의 필드 수가 맞지 않습니다.')
        try:
            rack = row['랙번호'].strip()
            floor = int(row['단수'])
            count = int(row['ERP_등록수'])
        except (ValueError, TypeError) as exc:
            raise ERPDataError(f'ERP {line}행의 단수 또는 등록 수가 올바르지 않습니다.') from exc
        left, right = product_ids(row['ERP_왼쪽제품']), product_ids(row['ERP_오른쪽제품'])
        expected = left + right
        if not rack or floor < 1 or len(left)>1 or len(right)>1 or count != len(expected):
            raise ERPDataError(f'ERP {line}행의 위치 또는 등록 수가 제품 목록과 맞지 않습니다.')
        key = (rack, floor)
        if key in result:
            raise ERPDataError(f'ERP 위치가 중복됩니다: {rack} {floor}단')
        result[key] = expected
    return result


def compare_with_erp(ai_result, erp_path=None):
    return compare_inventory(ai_result, load_erp(erp_path))


def compare_inventory(ai_result, erp, *, allow_appearance_location=False):
    comparison, seen = [], set()
    for item in ai_result.get('results', []):
        rack = str(item.get('랙번호') or 'UNMAPPED').strip()
        floor = item.get('단수')
        ids = product_ids(item.get('recognized_product_ids', item.get('인식제품ID')))
        candidates = int(item.get('YOLO탐지후보수') or 0)
        if candidates < 0:
            raise ValueError('탐지 후보 수는 음수가 될 수 없습니다.')
        expected = erp.get((rack, floor))
        verified = False
        observation_verified = (
            item.get('verification_passed') is True
            and item.get('location_verified') is True
            and (candidates == 1 or (allow_appearance_location and item.get('appearance_location_verified') is True))
            and len(ids) == 1
            and floor is not None and rack != 'UNMAPPED'
        )
        if floor is None or rack == 'UNMAPPED':
            status, expected = '위치매핑_확인필요', []
        else:
            key = (rack, floor)
            if key in seen:
                raise ValueError('AI 위치 결과가 중복됩니다.')
            seen.add(key)
            if expected is None:
                status, expected = 'ERP위치없음_확인필요', []
            elif any(product not in expected for product in ids):
                status = '미등록_또는_오적재의심'
            elif ids:
                verified = observation_verified and Counter(ids) == Counter(expected)
                status = 'PASS' if verified else '제품ID일치_추가검증필요'
            elif candidates:
                status = 'YOLO탐지_OCR미인식_재확인'
            elif expected:
                status = '전산재고_미탐지_재확인'
            else:
                status = '빈위치_재확인필요'
        comparison.append({
            'rack':rack, 'floor':floor, 'erp_product_ids':expected,
            'ai_product_ids':ids, 'ai_product_id':','.join(ids),
            'yolo_candidates':candidates, 'review_status':status,
            'verification_passed':verified,
            'observation_verified':observation_verified,
            'location_verified':item.get('location_verified') is True,
            'ocr_max_confidence':item.get('ocr_max_confidence',0.0),
            'ocr_yolo_spatial_match':item.get('ocr_yolo_spatial_match',False),
            'detection_ids':item.get('detection_ids',[]),
            'product_matches':item.get('product_matches',[]),
            'ocr_token_ids':item.get('ocr_token_ids',[]),
            'detection_stable':item.get('detection_stable',False),
            'verified_product_matches':item.get('verified_product_matches',[]),
            'review_reasons':item.get('review_reasons',[]),
        })
    return {'status':'compared','data_source':'LOCAL_TEST_ERP','total_positions':len(comparison),'results':comparison}
