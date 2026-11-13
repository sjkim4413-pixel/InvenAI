"""Authenticated live inventory workflow; all writes are local audit metadata."""
import hashlib
import json
from pathlib import Path
from fastapi import APIRouter,Depends,HTTPException,Query,Request
from pydantic import BaseModel,ConfigDict,Field
from f03_auth import AuditUser,require_user
from f03_storage import owner_dir,check_filename,job_lock,atomic_json,utc_now
from f03_live_inventory import load_company_inventory,warehouse_view,digest,InventoryInputError,InventoryUnavailable
from f03_catalog import AuditConfigError
from f03_fixture_inventory import ADDITIONAL_WAREHOUSE, fixture_rack_codes
from f03_codes import decode_codes,structured_code
from f03_verification import covered_fraction
from f04_live_review import review_live
import ivenai_integrated_ai as pipeline

router=APIRouter(prefix='/f03/audit/live',tags=['Live inventory'])
TEST_RACK_LABEL_SUFFIX='.test-rack-labels.json'


def error(code,message,status=422):return HTTPException(status,detail={'code':code,'message':message})


def snapshot(request,user):
    try:
        loader=getattr(request.app.state,'live_inventory_loader',None)
        result=loader(user.company) if loader else load_company_inventory(user.company,id_token=user.firebase_id_token)
        if result['company_code']!=user.company:raise InventoryInputError('다른 회사의 전산 자료입니다.')
        return result
    except InventoryUnavailable as exc:raise error('LIVE_INVENTORY_UNAVAILABLE',str(exc),503) from exc
    except InventoryInputError as exc:raise error('LIVE_INVENTORY_INVALID',str(exc),503) from exc


def read_json(path,default):
    if not path.is_file():return default
    try:return json.loads(path.read_text(encoding='utf-8'))
    except (OSError,ValueError) as exc:raise error('AUDIT_METADATA_INVALID','저장된 작업 정보를 확인해주세요.',503) from exc


def image_context(request,user,filename):
    try:check_filename(filename)
    except ValueError as exc:raise error('INVALID_FILENAME',str(exc),400) from exc
    directory=owner_dir(request.app.state.upload_dir,user)
    image=directory/filename;metadata=directory/(filename+'.upload.json')
    if not image.is_file() or not metadata.is_file():raise error('IMAGE_NOT_FOUND','이 계정의 업로드 사진이 아닙니다.',404)
    upload=read_json(metadata,{})
    if hashlib.sha256(image.read_bytes()).hexdigest()!=upload.get('sha256'):raise error('IMAGE_CHANGED','사진이 변경됐습니다. 다시 업로드해주세요.')
    return directory,image,upload


def binding(directory,warehouse_id):return read_json(directory/f'warehouse_{warehouse_id}.live-binding.json',None)


def bound_view(directory,warehouse_id,source):
    selected=binding(directory,warehouse_id)
    if not selected:raise error('WAREHOUSE_BINDING_REQUIRED','앱 창고와 실제 전산 창고를 먼저 연결해주세요.',409)
    try:positions=warehouse_view(source,selected['warehouse_code'])
    except InventoryInputError as exc:raise error('WAREHOUSE_BINDING_INVALID',str(exc),409) from exc
    return selected,positions


@router.get('/context')
def live_context(request:Request,warehouse_id:int=Query(gt=0),user:AuditUser=Depends(require_user)):
    source=snapshot(request,user);directory=owner_dir(request.app.state.upload_dir,user)
    selected=binding(directory,warehouse_id)
    positions=[]
    if selected and selected['warehouse_code'] in source['warehouses']:positions=warehouse_view(source,selected['warehouse_code'])
    else:selected=None
    return {'data_source':'FIREBASE_READ_ONLY','company_code':user.company,'warehouse_id':warehouse_id,
        'warehouses':source['warehouses'],'binding':selected,'positions':positions,'products':source['products'],
        'inventory_source_count':source['source_count'],'inventory_issues':source['issues'],
        'inventory_revision':source['revision'],'firebase_inventory_modified':False}


class Input(BaseModel):model_config=ConfigDict(extra='forbid')
class WarehouseBinding(Input):
    warehouse_id:int=Field(gt=0)
    warehouse_code:str=Field(min_length=1,max_length=160)


@router.post('/warehouse-binding')
def bind_warehouse(body:WarehouseBinding,request:Request,user:AuditUser=Depends(require_user)):
    source=snapshot(request,user)
    if body.warehouse_code not in source['warehouses']:raise error('UNKNOWN_WAREHOUSE','이 회사 전산에 확인된 창고가 아닙니다.')
    directory=owner_dir(request.app.state.upload_dir,user)
    result={'warehouse_id':body.warehouse_id,'warehouse_code':body.warehouse_code,
        'company_code':user.company,'confirmed_by':user.uid,'confirmed_at':utc_now(),'source_revision':source['revision']}
    atomic_json(directory/f'warehouse_{body.warehouse_id}.live-binding.json',result)
    return result


@router.post('/analyze-and-compare')
def analyze_live(request:Request,filename:str,warehouse_id:int=Query(gt=0),user:AuditUser=Depends(require_user),
                 warehouse_code:str|None=None):
    return run_live_analysis(request,filename,warehouse_id,user,warehouse_code)


def selected_warehouse(directory,warehouse_id,source,requested):
    warehouses=source['warehouses']
    if requested is not None:
        if requested not in warehouses:
            raise error('UNKNOWN_WAREHOUSE','이 회사 전산에 확인된 창고를 선택해주세요.')
        return requested
    saved=binding(directory,warehouse_id)
    if saved and saved['warehouse_code'] in warehouses:return saved['warehouse_code']
    if len(warehouses)==1:return warehouses[0]
    if warehouses:
        raise HTTPException(409,detail={'code':'WAREHOUSE_SELECTION_REQUIRED',
                                        'message':'사진을 찍은 창고를 선택해주세요.','warehouses':warehouses})
    return None


def run_live_analysis(request,filename,warehouse_id,user,warehouse_code=None,*,raw_only=False,test_rack_labels=False):
    directory,image,upload=image_context(request,user,filename)
    source=snapshot(request,user)
    with job_lock(directory,filename):
        target=directory/(filename+(TEST_RACK_LABEL_SUFFIX if test_rack_labels else '.live.json'))
        cached=read_json(target,{})
        previous=cached.get('comparison',{})
        test_item_codes=None
        if test_rack_labels:
            try:
                warehouse,positions,test_item_codes=fixture_rack_codes(upload,source,warehouse_id)
            except InventoryInputError as exc:
                raise error('TEST_RACK_LABELS_UNAVAILABLE',str(exc)) from exc
            except AuditConfigError as exc:
                raise error('TEST_RACK_LABELS_CONFIG_INVALID',str(exc),503) from exc
            if warehouse_code is not None and warehouse_code!=warehouse:
                raise error('TEST_IMAGE_WAREHOUSE_MISMATCH','이 테스트 이미지에 연결된 전산 창고를 사용해주세요.')
        else:
            if warehouse_code is None and previous.get('warehouse_id')==warehouse_id:
                if previous.get('warehouse_code') in source['warehouses']:
                    warehouse_code=previous['warehouse_code']
            warehouse=selected_warehouse(directory,warehouse_id,source,warehouse_code)
            positions=warehouse_view(source,warehouse) if warehouse is not None else []
        saved={} if test_rack_labels else read_json(directory/(filename+'.live-locations.json'),{})
        regions=saved.get('regions',[]) if saved.get('warehouse_code')==warehouse and saved.get('image_sha256')==upload['sha256'] else []
        # A changed ERP position invalidates its manual photo binding.
        current_positions={p['position_id'] for p in positions}
        regions=[r for r in regions if r['position_id'] in current_positions]
        mapping={'version':1,'company_code':user.company,'warehouse_id':warehouse_id,
                 'verification_scope':'live_product_and_location_ocr',
                 'images':[{'sha256':[upload['sha256']],'width':upload['width'],'height':upload['height'],'regions':regions}] if regions else []}
        registry={} if test_rack_labels else read_json(directory/'live-identifiers.json',{})
        # Pixel OCR is independent of the current stock. Resolve the text
        # against registered codes only when reviewing the observation.
        catalog={'catalog_id':'FIREBASE_REGISTERED_CODES','products':[],'ocr_rack_labels':True}
        if test_rack_labels:
            catalog['test_rack_labels']=True
            if warehouse==ADDITIONAL_WAREHOUSE:
                catalog['printed_rack_stickers']=True
        revision={'pipeline':request.app.state.pipeline_version,'model_sha256':request.app.state.model_digest(),
            'image_sha256':upload['sha256'],'catalog_sha256':digest(catalog),'location_map_sha256':digest(mapping),
            'warehouse_code':warehouse,'warehouse_id':warehouse_id,
            'registry_sha256':digest(registry),'inventory_revision':source['revision']}
        signature=digest(revision);ai_signature=digest({k:v for k,v in revision.items() if k not in ('inventory_revision','registry_sha256')})
        if cached.get('signature')==signature:return cached['analysis' if raw_only else 'comparison']
        raw=cached.get('analysis') if cached.get('ai_signature')==ai_signature else None
        codes=cached.get('codes',[]) if raw else []
        acquired=False
        if raw is None:
            acquired=request.app.state.analysis_slot.acquire(blocking=False)
            if not acquired:raise error('ANALYSIS_BUSY','다른 사진 분석이 끝난 뒤 다시 시도해주세요.',429)
        try:
            if raw is None:
                raw=pipeline.analyze_image(image,catalog=catalog,location_map=mapping,company_code=user.company,warehouse_id=warehouse_id)
                codes=decode_codes(pipeline.read_image(image))
            comparison=review_live(raw,codes,source,positions,regions,registry,test_item_codes=test_item_codes,
                                   printed_stickers=catalog.get('printed_rack_stickers',False))
            detections=[]
            for detection in raw.get('detections',[]):
                item=dict(detection)
                candidates=[r for r in comparison['results'] if detection['id'] in r['detection_ids']]
                row=candidates[0] if len(candidates)==1 else None
                item['product_ids']=row['ai_product_ids'] if row else []
                item.update(verification_passed=False,product_location_verified=bool(
                                row and row['product_location_verified'] and item['id']==row.get('verified_detection_id')),
                            review_status=row['review_status'] if row else '실제창고위치_확인필요')
                if test_rack_labels:
                    item['test_label_match']=bool(row and row.get('test_label_match')
                                                  and item['id']==row.get('verified_detection_id'))
                if row:item.update(rack=row['rack'],floor=row['floor'],
                                   location_verified=row['location_verified'] and covered_fraction(item['bbox'],row['location_bbox'])>=.95,
                                   location_label=row.get('location_label'))
                if row and item['id'] in row.get('fragment_detection_ids',[]):
                    item['overlay_parent_id']=row['verified_detection_id']
                detections.append(item)
            product_tokens={token_id for row in comparison['results'] for match in row['product_matches']
                            for token_id in match['ocr_token_ids']}
            rack_tokens={token_id for row in comparison['results'] if row.get('location_evidence')
                         for token_id in row['location_evidence']['ocr_token_ids']}
            tokens=[{**t,'kind':'product' if t['id'] in product_tokens else 'location_label' if t['id'] in rack_tokens else t.get('kind','other')}
                    for t in raw.get('ocr_tokens',[])]
            result={**comparison,'module':'F03','filename':filename,'job_id':filename[:-4],
                'company_code':user.company,'warehouse_id':warehouse_id,'warehouse_code':warehouse,'erp_warehouse':warehouse,
                'rack':'LIVE','revision':revision,'detections':detections,'codes':codes,
                'image_width':raw['image_width'],'image_height':raw['image_height'],
                'total_candidates':raw['total_candidates'],'ocr_tokens':tokens,
                'ocr_status':raw.get('ocr_status'),'model':raw.get('model',{}),
                'catalog_id':catalog['catalog_id'],'ocr_languages':raw.get('ocr_languages',[]),'analyzed_at':utc_now()}
            atomic_json(target,{'signature':signature,'ai_signature':ai_signature,'analysis':raw,'codes':codes,'comparison':result})
            return raw if raw_only else result
        finally:
            if acquired:request.app.state.analysis_slot.release()


class LocationBinding(Input):
    filename:str
    warehouse_id:int=Field(gt=0)
    detection_id:str
    position_id:str


@router.post('/location-binding')
def bind_location(body:LocationBinding,request:Request,user:AuditUser=Depends(require_user)):
    directory,image,upload=image_context(request,user,body.filename)
    source=snapshot(request,user);selected,positions=bound_view(directory,body.warehouse_id,source)
    position=next((p for p in positions if p['position_id']==body.position_id),None)
    if not position:raise error('UNKNOWN_POSITION','실제 전산에서 확인된 위치를 선택해주세요.')
    with job_lock(directory,body.filename):
        previous=read_json(directory/(body.filename+'.live.json'),{})
        if previous.get('comparison',{}).get('warehouse_code')!=selected['warehouse_code']:raise error('ANALYZE_FIRST','같은 창고로 사진을 먼저 분석해주세요.',409)
        detection=next((d for d in previous.get('analysis',{}).get('detections',[]) if d['id']==body.detection_id),None)
        if not detection:raise error('UNKNOWN_DETECTION','저장된 탐지 후보를 선택해주세요.')
        path=directory/(body.filename+'.live-locations.json');saved=read_json(path,{})
        regions=saved.get('regions',[]) if saved.get('warehouse_code')==selected['warehouse_code'] else []
        regions=[r for r in regions if r['detection_id']!=body.detection_id]
        b=detection['bbox']
        for r in regions:
            a=r['bbox']
            if r['position_id']==body.position_id or (min(a[2],b[2])>max(a[0],b[0]) and min(a[3],b[3])>max(a[1],b[1])):
                raise error('AMBIGUOUS_POSITION_REGION','같은 위치 또는 겹치는 후보는 한 사진에서 자동 연결할 수 없습니다.',409)
        regions.append({'position_id':body.position_id,'detection_id':body.detection_id,
            'rack':'POS-'+hashlib.sha256(body.position_id.encode()).hexdigest()[:12],'floor':position['floor'],
            'bbox':list(b),'confirmation_method':'operator_confirmed','confirmed_by':user.uid,'confirmed_at':utc_now()})
        atomic_json(path,{'warehouse_code':selected['warehouse_code'],'warehouse_id':body.warehouse_id,
                         'image_sha256':upload['sha256'],'company_code':user.company,'regions':regions})
    return {'status':'saved','requires_reanalysis':True,'confirmation_method':'operator_confirmed'}


class IdentifierBinding(Input):
    filename:str
    code_id:str
    sku:str


@router.post('/identifier-binding')
def bind_identifier(body:IdentifierBinding,request:Request,user:AuditUser=Depends(require_user)):
    directory,_,_=image_context(request,user,body.filename);source=snapshot(request,user)
    if body.sku not in {p['sku'] for p in source['products']}:raise error('UNKNOWN_SKU','이 회사 전산의 상품 코드를 선택해주세요.')
    with job_lock(directory,'live-identifiers'):
        previous=read_json(directory/(body.filename+'.live.json'),{})
        code=next((c for c in previous.get('codes',[]) if c['id']==body.code_id),None)
        if code is None:raise error('UNKNOWN_PHOTOGRAPHED_CODE','사진에서 실제 판독된 코드를 선택해주세요.')
        structured=structured_code(code)
        if structured and (structured.get('kind')!='product' or structured.get('companyCode')!=user.company or structured.get('itemCode')!=body.sku):
            raise error('CODE_NAMESPACE_MISMATCH','다른 회사 또는 위치용 QR을 상품 코드로 등록할 수 없습니다.')
        path=directory/'live-identifiers.json';registry=read_json(path,{})
        if code['value'] in registry and registry[code['value']]['sku']!=body.sku:
            raise error('BARCODE_ALREADY_BOUND','이미 다른 상품에 연결된 코드입니다.',409)
        registry[code['value']]={'sku':body.sku,'company_code':user.company,'confirmed_by':user.uid,
            'confirmed_at':utc_now(),'source_image':body.filename,'code_format':code['format']}
        atomic_json(path,registry)
    return {'status':'saved','sku':body.sku,'requires_reanalysis':True}
