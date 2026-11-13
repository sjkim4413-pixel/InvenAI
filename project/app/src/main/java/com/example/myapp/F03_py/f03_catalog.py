"""Server-owned product and location configuration; independent of ERP expectations."""
import hashlib
import json
import math
import re
import unicodedata
from pathlib import Path


class AuditConfigError(ValueError):
    pass


def read_config(path, kind):
    try:
        data = json.loads(Path(path).read_text(encoding='utf-8-sig'))
    except (OSError, UnicodeError, ValueError) as exc:
        raise AuditConfigError(f'{kind} 설정을 읽을 수 없습니다.') from exc
    if not isinstance(data, dict) or data.get('version') != 1:
        raise AuditConfigError(f'{kind} 설정 버전이 올바르지 않습니다.')
    return data


def load_catalog(path):
    data = read_config(path, '상품 목록')
    products = data.get('products')
    if not isinstance(products, list):
        raise AuditConfigError('상품 목록이 올바르지 않습니다.')
    seen = set()
    for product in products:
        if not isinstance(product, dict):
            raise AuditConfigError('상품 항목이 올바르지 않습니다.')
        sku = product.get('sku')
        if not isinstance(sku, str) or not sku.strip() or sku in seen:
            raise AuditConfigError('상품 코드가 비어 있거나 중복됩니다.')
        seen.add(sku)
        aliases = product.get('aliases', [])
        if not isinstance(aliases, list) or any(
            not isinstance(alias, list) or not alias or any(not isinstance(p, str) or not compact(p) for p in alias)
            for alias in aliases
        ):
            raise AuditConfigError('상품 별칭은 필수 문구 목록이어야 합니다.')
    return data


def load_locations(path):
    data = read_config(path, '위치 매핑')
    images = data.get('images')
    if not isinstance(images, list):
        raise AuditConfigError('위치 매핑 사진 목록이 올바르지 않습니다.')
    if images and (not isinstance(data.get('company_code'), str) or not data['company_code']
                   or type(data.get('warehouse_id')) is not int or data['warehouse_id'] < 1):
        raise AuditConfigError('위치 매핑에 회사와 창고가 필요합니다.')
    hashes = set()
    for image in images:
        if not isinstance(image, dict):
            raise AuditConfigError('위치 매핑 사진이 올바르지 않습니다.')
        digests = image.get('sha256', [])
        if not isinstance(digests, list):
            raise AuditConfigError('위치 매핑 사진 해시 목록이 올바르지 않습니다.')
        for digest in digests:
            if not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest) or digest in hashes:
                raise AuditConfigError('위치 매핑 사진 해시가 중복되거나 올바르지 않습니다.')
            hashes.add(digest)
        if not digests or any(type(image.get(v)) is not int or image[v] < 64 for v in ('width', 'height')):
            raise AuditConfigError('위치 매핑 사진 크기가 올바르지 않습니다.')
        regions = image.get('regions')
        if not isinstance(regions, list) or not regions:
            raise AuditConfigError('위치 매핑 영역이 없습니다.')
        seen = set()
        previous = []
        for region in regions:
            if not isinstance(region, dict): raise AuditConfigError('위치 매핑 영역이 올바르지 않습니다.')
            box = region.get('bbox', [])
            key = (region.get('rack'), region.get('floor'))
            if not isinstance(key[0], str) or not key[0] or type(key[1]) is not int or key[1] < 1 or key in seen:
                raise AuditConfigError('위치 매핑 위치가 중복되거나 올바르지 않습니다.')
            seen.add(key)
            if not isinstance(box, list) or len(box) != 4 or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in box) or box[2] <= box[0] or box[3] <= box[1]:
                raise AuditConfigError('위치 매핑 좌표가 올바르지 않습니다.')
            for b in previous:
                if min(box[2], b[2]) > max(box[0], b[0]) and min(box[3], b[3]) > max(box[1], b[1]):
                    raise AuditConfigError('위치 매핑 영역이 겹칩니다.')
            previous.append(box)
    return data


def resolve_regions(mapping, image_path, width, height, company_code, warehouse_id):
    if not mapping.get('images'):
        return [], '등록된 사진 위치 매핑이 없습니다.'
    if company_code != mapping['company_code'] or warehouse_id != mapping['warehouse_id']:
        return [], '위치 매핑의 회사·창고가 현재 작업과 다릅니다.'
    digest = hashlib.sha256(Path(image_path).read_bytes()).hexdigest()
    for image in mapping['images']:
        if digest in image['sha256']:
            if (width, height) != (image['width'], image['height']):
                raise AuditConfigError('위치 매핑 사진 크기가 일치하지 않습니다.')
            return image['regions'], ''
    return [], '이 사진에 대해 확인된 위치 매핑이 없습니다.'


def words(text):
    return re.findall(r'[A-Z0-9]+', unicodedata.normalize('NFKC', str(text)).upper())


def compact(text):
    return ''.join(words(text))


def phrase_matches(phrase, text):
    expected, actual = words(phrase), words(text)
    if ''.join(expected) == ''.join(actual): return True
    return any(actual[i:i+len(expected)] == expected for i in range(len(actual)-len(expected)+1))


def match_catalog(tokens, catalog, threshold=.45):
    matches = []
    for product in catalog['products']:
        alternatives = [([product['sku']], 'printed_sku')]
        if catalog.get('strict_sku_labels'):
            alternatives=[([f'{prefix} {product["sku"]}'],'printed_sku') for prefix in ('SKU','ITEM CODE','ITEMCODE')]
        alternatives += [(alias, 'catalog_alias') for alias in product.get('aliases', [])]
        choices = []
        for phrases, kind in alternatives:
            evidence = []
            for phrase in phrases:
                candidates = [token for token in tokens if token['confidence'] >= threshold and phrase_matches(phrase, token['text'])]
                if not candidates: break
                evidence.append(max(candidates, key=lambda t: t['confidence']))
            else:
                box = [min(t['bbox'][0] for t in evidence), min(t['bbox'][1] for t in evidence),
                       max(t['bbox'][2] for t in evidence), max(t['bbox'][3] for t in evidence)]
                choices.append({'sku': product['sku'], 'confidence': min(t['confidence'] for t in evidence),
                                'bbox': box, 'ocr_token_ids': list(dict.fromkeys(t['id'] for t in evidence)), 'method': kind})
        if choices: matches.append(max(choices, key=lambda m: m['confidence']))
    return matches
