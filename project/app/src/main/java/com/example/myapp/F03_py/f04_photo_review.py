"""Review server-generated F03 evidence against the local ERP."""
import hashlib
import importlib.util
import os
import threading
from pathlib import Path
from f03_catalog import AuditConfigError
from ivenai_erp_matcher import compare_with_erp, load_erp

F04_DIR=Path(os.environ.get('IVENAI_F04_DIR',str(Path.home()/'AndroidStudioProjects/InvenAI/IvenAI_F04')))
_lock=threading.Lock()
_cached=None
LOCAL_REVISION = 'f04-local-evidence-v1'


def f04_revision():
    path=F04_DIR/'safe_multi_compare.py'
    if not path.is_file():
        return LOCAL_REVISION
    try: return f'{LOCAL_REVISION}:{hashlib.sha256(path.read_bytes()).hexdigest()}'
    except OSError as exc: raise AuditConfigError('F04 비교 모듈 설정을 확인해주세요.') from exc


def f04_comparer():
    global _cached
    revision=f04_revision()
    with _lock:
        if _cached is None or _cached[0]!=revision:
            path=F04_DIR/'safe_multi_compare.py'
            if not path.is_file():
                _cached=(revision, None)
            else:
                spec=importlib.util.spec_from_file_location('_ivenai_f04_safe_comparer', path)
                module=importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                _cached=(revision, module.compare_safe)
        return _cached[1]


def external_decisions(comparer, rows, erp_path, company_code):
    expected={}
    for (rack,floor),products in load_erp(erp_path).items():
        for sku in products:
            expected[(company_code,f'{rack}:{floor}',sku)]={'quantity':products.count(sku)}
    records=[]
    for row in rows:
        eligible=row['observation_verified']
        records.append({'location':f"{row['rack']}:{row['floor']}",
                        'product_id':row['ai_product_ids'][0] if eligible else None,
                        'confidence':row['ocr_max_confidence'],
                        'detection_state':'detected' if eligible else 'unreadable'})
    reviewed=comparer(expected,records,company_code)
    if not isinstance(reviewed, list) or len(reviewed)!=len(rows):
        return [None] * len(rows)
    return [
        decision.get('status_code')
        if isinstance(decision, dict) and type(decision.get('status_code')) is int else None
        for decision in reviewed
    ]


def review_observations(raw,erp_path,company_code):
    compared=compare_with_erp(raw,erp_path)
    rows=compared['results']
    comparer=f04_comparer()
    approved_codes=external_decisions(comparer,rows,erp_path,company_code) if comparer else None
    for index,row in enumerate(rows):
        code,status=5,'MANUAL_REVIEW_REQUIRED'
        if row['verification_passed']:
            if approved_codes is None or approved_codes[index]==1:
                code,status=1,'NORMAL'
            else:
                row['verification_passed']=False
                row['review_status']='F04추가검증필요'
                row['review_reasons']=[*row['review_reasons'],'F04에서 정상 판정을 확인하지 못했습니다.']
        elif row['observation_verified'] and row['review_status']=='미등록_또는_오적재의심':
            code,status=(2,'MISPLACED') if row['erp_product_ids'] else (4,'UNREGISTERED')
        row['f04_status_code']=code
        row['f04_status']=status
    compared['comparison_module']='F04_SAFE_MULTI_COMPARE_WITH_LOCAL_ERP'
    compared['erp_backend']='LOCAL_CSV'
    compared['firebase_inventory_modified']=False
    return compared
