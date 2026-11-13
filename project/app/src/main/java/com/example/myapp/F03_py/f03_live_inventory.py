"""Read-only, company-scoped authoritative inventory with explicit data quality.

Registered item codes are identities. Names/expected positions never become
observed identities, and missing warehouse information is never guessed.
"""
import hashlib
import json
import math
import os
import re
import sys
import unicodedata
from pathlib import Path
from f03_firebase_rest import read_inventory

MAX_COMPANY_RECORDS=10000


class InventoryUnavailable(RuntimeError):pass
class InventoryInputError(ValueError):pass


def clean(value):
    return unicodedata.normalize('NFKC',str(value or '')).strip()


def digest(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def location_parts(value):
    text=clean(value)
    warehouses=[]
    for note in re.findall(r'\(([^)]*)\)',text):
        if '물류센터' in note or '창고' in note:warehouses.append(note.strip())
        elif note.strip() not in ('적재랙','오적재 감지','오적재감지','재검증 대기'):
            raise InventoryInputError('위치의 괄호 설명을 해석할 수 없습니다.')
    text=re.sub(r'\([^)]*\)','',text).strip().upper()
    match=re.fullmatch(r'(?:(?P<zone>[A-Z0-9가-힣]+)구역\s*)?(?P<rack>[A-Z]+\d+)\s*-\s*(?P<tier>[1-9]\d*)\s*(?:층|단|F)(?:\s*-\s*(?P<side>L|R|\d{1,3}))?',text)
    if not match:raise InventoryInputError('위치에 구역·랙·단 정보를 확인해야 합니다.')
    result=match.groupdict();result['tier']=int(result['tier'])
    if len(set(warehouses))>1:raise InventoryInputError('위치에 서로 다른 창고가 기재돼 있습니다.')
    result['warehouse']=warehouses[0] if warehouses else None
    return result


def position_id(parts):
    # Missing zone/side remains missing; L or zone A must not be invented.
    return '/'.join([parts.get('zone') or '-',parts['rack'],str(parts['tier']),parts.get('side') or '-'])


def normalize_record(document_id,data,company):
    if data.get('companyCode')!=company:raise InventoryInputError('조회 회사와 재고 회사가 다릅니다.')
    sku=clean(data.get('itemCode'))
    if not sku:raise InventoryInputError('상품 코드가 없습니다.')
    quantity=data.get('quantity')
    if type(quantity) not in (int,float) or not math.isfinite(quantity) or quantity<0:
        raise InventoryInputError('재고 수량이 올바르지 않습니다.')
    parts=location_parts(data.get('location'))
    explicit_warehouse=clean(data.get('warehouse')) or None
    if explicit_warehouse and parts['warehouse'] and explicit_warehouse!=parts['warehouse']:
        raise InventoryInputError('창고 필드와 위치의 창고가 다릅니다.')
    warehouse=explicit_warehouse or parts['warehouse']
    if not warehouse:raise InventoryInputError('창고가 지정되지 않은 재고입니다.')
    zone=clean(data.get('zone')).upper().removesuffix('구역').strip()
    if zone and parts['zone'] and zone!=parts['zone']:raise InventoryInputError('구역 필드와 위치 구역이 다릅니다.')
    if zone and not parts['zone']:parts['zone']=zone
    for field in ('rack','rackId'):
        value=clean(data.get(field)).upper()
        if not value:continue
        value=re.sub(r'\(적재랙\)','',value).strip()
        match=re.fullmatch(r'(?:(?P<zone>[A-Z0-9가-힣]+)구역\s*)?(?P<rack>[A-Z]+\d+)',value)
        if not match or match['rack']!=parts['rack'] or (match['zone'] and parts['zone'] and match['zone']!=parts['zone']):
            raise InventoryInputError(f'{field} 필드와 위치가 다릅니다.')
    tier=clean(data.get('tier'))
    if tier:
        if any(('창고' in n or '물류센터' in n) and n.strip()!=warehouse for n in re.findall(r'\(([^)]*)\)',tier)):
            raise InventoryInputError('단수 설명의 창고와 전산 창고가 다릅니다.')
        tier=re.sub(r'\([^)]*\)','',tier).strip()
        if not re.fullmatch(r'[1-9]\d*(?:층|단|F)?',tier,re.I) or int(re.match(r'\d+',tier)[0])!=parts['tier']:
            raise InventoryInputError('단수 필드와 위치가 다릅니다.')
    test_photo = {}
    if data.get('isTestData') is True and data.get('dataSource') == 'SIMULATED_AI_IMAGE_ERP':
        test_photo = {'test_photo_reference': clean(data.get('photoReference')),
                      'alternate_test_photo_reference': clean(data.get('alternatePhotoReference')),
                      'test_photo_label': clean(data.get('photoLocationLabel')),
                      'test_photo_label_issue': clean(data.get('photoLabelIssue'))}
    return {'document_id':document_id,'company_code':company,'sku':sku,'name':clean(data.get('itemName')),
            'quantity':quantity,'warehouse':warehouse,'zone':parts['zone'],'rack':parts['rack'],
            'floor':parts['tier'],'side':parts['side'],'position_id':position_id(parts),
            'source_location':clean(data.get('location')),'status':clean(data.get('status')), **test_photo}


def build_snapshot(company,documents):
    if not isinstance(company,str) or not company.strip() or company!=company.strip():
        raise InventoryInputError('확인된 회사 코드가 필요합니다.')
    documents=list(documents)
    if len(documents)>MAX_COMPANY_RECORDS:raise InventoryUnavailable('회사 재고 조회 한도를 초과했습니다. 일부 자료로 판정하지 않습니다.')
    records=[];issues=[];products={}
    for document_id,data in documents:
        if data.get('companyCode')!=company:raise InventoryInputError('다른 회사의 재고가 반환됐습니다.')
        sku=clean(data.get('itemCode'))
        if sku:products.setdefault(sku,{'sku':sku,'name':clean(data.get('itemName'))})
        try:records.append(normalize_record(document_id,data,company))
        except InventoryInputError as exc:issues.append({'document_id':document_id,'sku':sku,'reason':str(exc)})
    duplicates={}
    for row in records:duplicates.setdefault((row['warehouse'],row['position_id'],row['sku']),[]).append(row)
    invalid={}
    for key,rows in duplicates.items():
        if len(rows)>1:
            for row in rows:invalid[row['document_id']]='같은 위치·상품의 전산 문서가 중복됩니다.'
    issues += [{'document_id':r['document_id'],'sku':r['sku'],'reason':invalid[r['document_id']]} for r in records if r['document_id'] in invalid]
    records=[r for r in records if r['document_id'] not in invalid]
    # The revision includes rejected data as well, so fixes/changes cannot reuse
    # a comparison made against a different source snapshot.
    source=[{'id':doc_id,'data':data} for doc_id,data in sorted(documents,key=lambda p:p[0])]
    canonical=json.loads(json.dumps(source,ensure_ascii=False,default=str))
    return {'company_code':company,'source':'FIREBASE_READ_ONLY','source_count':len(documents),
            'records':records,'products':sorted(products.values(),key=lambda p:p['sku']),
            'warehouses':sorted(set(r['warehouse'] for r in records)), 'issues':issues,
            'complete':not issues,'revision':digest(canonical)}


def load_company_inventory(company, *, warehouse=None, id_token=None):
    if not isinstance(company,str) or not company.strip():raise InventoryInputError('회사 코드가 필요합니다.')
    try:
        if id_token:
            docs=read_inventory(id_token,company,warehouse,MAX_COMPANY_RECORDS+1)
        else:
            directory=Path(os.environ.get('IVENAI_F04_DIR') or Path.home()/'AndroidStudioProjects/InvenAI/IvenAI_F04')
            if str(directory) not in sys.path:sys.path.insert(0,str(directory))
            from firebase_inventory import get_firestore_client
            from google.cloud.firestore_v1.base_query import FieldFilter
            query=get_firestore_client().collection('inventory').where(filter=FieldFilter('companyCode','==',company))
            if warehouse is not None:
                query=query.where(filter=FieldFilter('warehouse','==',warehouse))
            docs=[(doc.id,doc.to_dict() or {}) for doc in query.limit(MAX_COMPANY_RECORDS+1).stream(timeout=25)]
    except Exception as exc:
        raise InventoryUnavailable('실제 전산 재고를 읽지 못했습니다. 일부 결과나 테스트 ERP로 대체하지 않습니다.') from exc
    return build_snapshot(company,docs)


def warehouse_view(snapshot,warehouse):
    if warehouse not in snapshot['warehouses']:raise InventoryInputError('이 회사의 확인된 창고가 아닙니다.')
    records=[r for r in snapshot['records'] if r['warehouse']==warehouse]
    positions={}
    for row in records:
        position=positions.setdefault(row['position_id'],{'position_id':row['position_id'],'rack':row['rack'],
            'zone':row['zone'],'floor':row['floor'],'side':row['side'],'source_location':row['source_location'],'items':[]})
        position['items'].append({'sku':row['sku'],'quantity':row['quantity'],'document_id':row['document_id'],'status':row['status']})
    return sorted(positions.values(),key=lambda p:p['position_id'])


def live_catalog(snapshot):
    # Names and expected products are deliberately not OCR aliases. A printed
    # model name cannot silently become a different internal inventory code.
    return {'version':1,'catalog_id':'FIREBASE_REGISTERED_CODES','strict_sku_labels':True,
            'products':[{'sku':p['sku'],'aliases':[]} for p in snapshot['products']]}
