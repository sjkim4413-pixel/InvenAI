"""Closed-set reference-photo appearance comparison for twelve demonstration assets.

Regions are human annotations, not detections. Image hashes select a reference
scene only; all decisions use observed product pixels and the current inventory.
No normal/misplaced flags or edit ground truth are read by this module.
"""
import hashlib
import json
from pathlib import Path
import re

import cv2
import numpy as np
from fastapi import HTTPException

from f03_fixture_inventory import FIXTURE_DIR, fixture_rack_codes
from f03_live_inventory import InventoryInputError, digest
from f03_storage import atomic_json, job_lock, utc_now

MODE='test_reference_pairs'
SCOPE='test_reference_product_locations'
SUFFIX='.test-reference-pairs.json'


def profile_for(upload,company,warehouse_id):
    try:
        config=json.loads((FIXTURE_DIR/'reference_pairs.json').read_text(encoding='utf-8'))
        assert config['version']==1 and company==config['company_code'] and warehouse_id==1
        assert len(config['pairs'])==6
        selected=[]
        for pair in config['pairs']:
            assert re.fullmatch(r'pair_[0-9]{2}',pair['id'])
            assert len(pair['photos'])==2 and pair['photos'][pair['reference']]==pair['reference_sha256']
            assert Path(pair['reference']).name==pair['reference'] and pair['reference'].endswith('.png')
            assert all(re.fullmatch(r'[a-f0-9]{64}',value) for value in pair['photos'].values())
            assert 1<=len(pair['regions'])<=64
            assert len({row['location'] for row in pair['regions']})==len(pair['regions'])
            assert len({row['sku'] for row in pair['regions']})==len(pair['regions'])
            for row in pair['regions']:
                x1,y1,x2,y2=row['bbox']
                assert all(type(v) in (int,float) and 0<=v<=1 for v in row['bbox'])
                assert x1<x2 and y1<y2
            if upload.get('source_sha256') in pair['photos'].values():selected.append(pair)
        assert len(selected)==1
        pair=selected[0]
        content=(FIXTURE_DIR/pair['reference']).read_bytes()
        assert hashlib.sha256(content).hexdigest()==pair['reference_sha256']
        return pair,content
    except (OSError,ValueError,KeyError,TypeError,AssertionError) as exc:
        raise InventoryInputError('등록된 정상·오적재 12장 중 하나를 선택하고 기준 사진을 확인해 주세요.') from exc


def crop(image,box):
    height,width=image.shape[:2]
    x1,y1,x2,y2=box
    # Exclude the outer background, pallet and rack-text boundary from identity.
    dx=(x2-x1)*.06;dy=(y2-y1)*.08
    return image[round((y1+dy)*height):round((y2-dy)*height),
                 round((x1+dx)*width):round((x2-dx)*width)]


def descriptor(image):
    if image.size==0:raise ValueError('Empty appearance region')
    small=cv2.resize(image,(48,48),interpolation=cv2.INTER_AREA)
    lab=cv2.cvtColor(small,cv2.COLOR_BGR2LAB).astype(np.float32)
    grid=cv2.resize(lab,(4,4),interpolation=cv2.INTER_AREA)
    hsv=cv2.cvtColor(small,cv2.COLOR_BGR2HSV)
    histogram=cv2.calcHist([hsv],[0,1],None,[18,4],[0,180,0,256]).ravel()
    histogram=histogram/max(float(histogram.sum()),1.)
    gray=cv2.cvtColor(small,cv2.COLOR_BGR2GRAY).astype(np.float32)
    gray=(gray-gray.mean())/max(float(gray.std()),16.)
    return grid,histogram,cv2.resize(gray,(12,12),interpolation=cv2.INTER_AREA)


def distance(a,b):
    color=float(np.sqrt(np.mean((a[0]-b[0])**2)))/128.
    histogram=float(np.sqrt(np.maximum(a[1],0)*np.maximum(b[1],0)).sum())
    shape=float(np.sqrt(np.mean((a[2]-b[2])**2)))/3.
    return .45*color+.35*(1-min(1.,histogram))+.20*shape


def observe(image,reference,regions):
    templates=[descriptor(crop(reference,row['bbox'])) for row in regions]
    observations=[]
    for row in regions:
        feature=descriptor(crop(image,row['bbox']))
        scores=sorted((distance(feature,target),index) for index,target in enumerate(templates))
        best,index=scores[0];runner=scores[1][0] if len(scores)>1 else 1.
        margin=runner-best
        # Same thresholds for every position, photo and variant. Ambiguity holds.
        confident=best<=.22 and margin>=.02
        observations.append(dict(location=row['location'],sku=regions[index]['sku'] if confident else None,
            candidate_sku=regions[index]['sku'],reference_location=regions[index]['location'],
            distance=round(best,5),margin=round(margin,5),accepted=bool(confident),
            candidates=[dict(sku=regions[i]['sku'],distance=round(score,5)) for score,i in scores[:3]]))
    return dict(observations=observations,image_width=image.shape[1],image_height=image.shape[0])


def compare(raw,source,positions,pair):
    by_location={row['source_location']:row for row in positions}
    names={product['sku']:product['name'] for product in source['products']}
    rows=[];detections=[]
    for index,(region,observation) in enumerate(zip(pair['regions'],raw['observations']),1):
        position=by_location.get(region['location'])
        items=position['items'] if position else []
        expected=sorted(item['sku'] for item in items)
        ready=bool(position and not source['issues'] and len(items)==1 and items[0]['quantity']>0
                   and items[0]['status']=='NORMAL')
        sku=observation['sku']
        code=(1 if expected==[sku] else 2) if ready and sku else 5
        match=code==1
        title={1:'기준 사진 상품·위치 일치',2:'기준 사진 대비 오적재',5:'상품 외관 추가 확인'}[code]
        mark=f'reference-region-{index}'
        reasons=['사람이 지정한 테스트 영역에서 기준 사진의 포장 색상·무늬와 대조했습니다.',
                 '기준 사진 6장에 한정한 외관 비교입니다. 실제 상품 코드와 수량은 별도 확인합니다.']
        if not sku:reasons.append('후보의 외관 차이가 작거나 기준 사진과 달라 판정을 보류했습니다.')
        if not ready:reasons.append('현재 전산의 위치·수량·상태를 확인해 주세요.')
        row=dict(rack=position['rack'] if position else '',floor=position['floor'] if position else None,
            side=position['side'] if position else None,zone=position['zone'] if position else '',
            position_id=position['position_id'] if position else None,location_label=region['location'],
            location_bbox=region['bbox'],erp_product_ids=expected,erp_inventory_ids=[item['document_id'] for item in items if item.get('document_id')],
            ai_product_ids=[sku] if sku else [],ai_product_id=sku or '',
            product_names=names,yolo_candidates=0,ocr_max_confidence=0,ocr_token_ids=[],product_matches=[],
            review_status=title,review_reasons=reasons,location_verified=True,
            location_method='human_annotated_test_region',verification_passed=False,product_location_verified=False,
            quantity_verified=False,expected_quantity=items[0]['quantity'] if len(items)==1 else None,
            identity_method='TEST_REFERENCE_APPEARANCE',test_product_location_match=match,
            reference_evidence=observation,detection_ids=[mark],verified_detection_id=mark if sku else None,
            f04_status_code=code,f04_status={1:'TEST_REFERENCE_MATCH',2:'TEST_REFERENCE_MISPLACED',5:'MANUAL_REVIEW_REQUIRED'}[code])
        rows.append(row)
        detections.append(dict(id=mark,bbox=region['bbox'],confidence=None,source='annotated_reference_test_region',
            product_ids=row['ai_product_ids'],location_verified=True,verification_passed=False,product_location_verified=False,
            detection_stable=False,test_product_location_match=match,review_status=title))
    return dict(status='compared',rack='REFERENCE_PAIRS',total_positions=len(rows),results=rows,detections=detections,
        data_source='FIRESTORE_TEST_ERP',erp_backend='FIRESTORE',erp_comparison_available=True,
        erp_warehouse=pair['warehouse'],verification_scope=SCOPE,comparison_module='F05_REFERENCE_APPEARANCE_COMPARISON',
        detector_status='not_evaluated_in_reference_region_test',visual_status='reference_comparison_completed',
        ocr_status='not_used_reference_appearance',ocr_tokens=[],quantity_verified=False,firebase_inventory_modified=False,
        inventory_issues=source['issues'],product_names=names,image_width=raw['image_width'],image_height=raw['image_height'],
        total_candidates=len(rows),codes=[],reference_pair_id=pair['id'])


def run_reference_pairs(request,filename,warehouse_id,user,warehouse_code,directory,image,upload,source,*,raw_only=False):
    try:
        pair,content=profile_for(upload,user.company,warehouse_id)
        if warehouse_code is not None and warehouse_code!=pair['warehouse']:
            raise InventoryInputError('선택한 사진과 같은 기준 창고를 사용해 주세요.')
        warehouse,positions,_=fixture_rack_codes({'source_sha256':pair['reference_sha256'],'sha256':pair['reference_sha256']},source,warehouse_id)
        if warehouse!=pair['warehouse']:raise InventoryInputError('기준 전산 창고를 확인해 주세요.')
        if any(position['source_location'] not in {row['location'] for row in pair['regions']} for position in positions):
            raise InventoryInputError('기준 사진에 연결된 전산 위치가 변경됐습니다. 테스트 기준을 확인해 주세요.')
    except InventoryInputError as exc:
        raise HTTPException(422,detail={'code':'REFERENCE_PAIR_UNAVAILABLE','message':str(exc)}) from exc
    revision=dict(pipeline=request.app.state.pipeline_version,image_sha256=upload['sha256'],
        reference_sha256=pair['reference_sha256'],profile_sha256=digest(pair),inventory_revision=source['revision'],mode=MODE)
    signature=digest(revision);ai_signature=digest({k:v for k,v in revision.items() if k!='inventory_revision'})
    target=directory/(filename+SUFFIX)
    with job_lock(directory,filename):
        cached=json.loads(target.read_text(encoding='utf-8')) if target.is_file() else {}
        if cached.get('signature')==signature:return cached['analysis' if raw_only else 'comparison']
        raw=cached.get('analysis') if cached.get('ai_signature')==ai_signature else None
        if raw is None:
            if not request.app.state.analysis_slot.acquire(blocking=False):
                raise HTTPException(429,detail={'code':'ANALYSIS_BUSY','message':'다른 사진 분석이 끝난 뒤 다시 시도해 주세요.'})
            try:
                import ivenai_integrated_ai as pipeline
                reference=cv2.imdecode(np.frombuffer(content,dtype=np.uint8),cv2.IMREAD_COLOR)
                raw=observe(pipeline.read_image(image),reference,pair['regions'])
            finally:request.app.state.analysis_slot.release()
        result=compare(raw,source,positions,pair)
        result.update(module='F03',filename=filename,job_id=filename[:-4],company_code=user.company,warehouse_id=warehouse_id,
            warehouse_code=warehouse,revision=revision,model={'name':'REFERENCE_PACKAGING_APPEARANCE',
                'used_for':'registered_reference_pair_demo_only'},analyzed_at=utc_now())
        atomic_json(target,dict(signature=signature,ai_signature=ai_signature,analysis=raw,comparison=result))
        return raw if raw_only else result
