"""F03 API: authenticated uploads, reviewable inference and saved results."""
import copy
import hashlib
import io
import json
import logging
import os
import threading
import warnings
from pathlib import Path
from typing import Literal
from uuid import uuid4

from fastapi import Depends, FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from PIL import Image, ImageOps, UnidentifiedImageError, JpegImagePlugin, PngImagePlugin

from f03_auth import AuditUser, external_f04_directory, require_user
from f03_storage import atomic_json, check_filename, job_lock, owner_dir, utc_now
from f03_catalog import AuditConfigError, load_catalog, load_locations
from f03_verification import covered_fraction
from f03_fixture_inventory import fixture_configuration, fixture_inventory, review_fixture
from f03_firebase_rest import FirebaseAccessError, firebase_config
from f03_visual_identity import add_visual_observations, load_visual_assets
from f04_photo_review import review_observations, f04_revision
import ivenai_integrated_ai as pipeline
from ivenai_erp_matcher import ERPDataError, ERP_FILE, load_erp

BASE_DIR=Path(__file__).resolve().parent
MAX_UPLOAD_BYTES=16*1024*1024
MAX_IMAGE_PIXELS=24_000_000
PIPELINE_VERSION='2026-11-13.26'
AuditMode=Literal['live','simulation','test_rack_labels']
app=FastAPI(title='IvenAI F03 API',version='2.0.0')
app.state.upload_dir=Path(os.environ.get('IVENAI_UPLOAD_DIR',str(BASE_DIR/'audit_uploads')))
app.state.erp_path=Path(os.environ.get('IVENAI_ERP_PATH',str(ERP_FILE)))
app.state.catalog_path=pipeline.CATALOG_PATH
app.state.location_map_path=pipeline.LOCATION_MAP_PATH
_analysis_slot=threading.BoundedSemaphore(1)
_model_digest_lock=threading.Lock()
_model_digest_cache={}


def problem(status,code,message):
    return HTTPException(status,detail={'code':code,'message':message})


def model_digest():
    path=pipeline.MODEL_PATH
    stat=path.stat()
    key=(str(path),stat.st_size,stat.st_mtime_ns)
    with _model_digest_lock:
        if key not in _model_digest_cache:
            _model_digest_cache.clear()
            _model_digest_cache[key]=hashlib.sha256(path.read_bytes()).hexdigest()
        return _model_digest_cache[key]


def normalized_jpeg(data,content_type):
    """Verify content and dimensions before decoding; bake EXIF into pixels."""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error',Image.DecompressionBombWarning)
            opener=JpegImagePlugin.JpegImageFile if content_type=='image/jpeg' else PngImagePlugin.PngImageFile
            # Use the two supported decoders directly. Generic Image.open is
            # monkey-patched by YOLO and may try installing HEIF on bad input.
            with opener(io.BytesIO(data)) as probe:
                expected={'image/jpeg':'JPEG','image/png':'PNG'}.get(content_type)
                if probe.format!=expected:
                    raise ValueError('파일 형식과 이미지 내용이 다릅니다.')
                width,height=probe.size
                if width*height>MAX_IMAGE_PIXELS or min(width,height)<64:
                    raise ValueError('이미지는 최소 64픽셀, 최대 2,400만 화소까지 지원합니다.')
                probe.verify()
            with opener(io.BytesIO(data)) as source:
                oriented=ImageOps.exif_transpose(source).convert('RGB')
                output=io.BytesIO()
                oriented.save(output,format='JPEG',quality=95)
                return output.getvalue(),oriented.size
    except (UnidentifiedImageError,OSError,SyntaxError,Image.DecompressionBombError,Image.DecompressionBombWarning) as exc:
        raise ValueError('손상되었거나 지원하지 않는 이미지입니다.') from exc


def save_upload(data,content_type,user):
    directory=owner_dir(app.state.upload_dir,user)
    if sum(1 for _ in directory.glob('*.upload.json'))>=500:
        raise problem(413,'UPLOAD_QUOTA','사진 보관 한도에 도달했습니다. 관리자에게 문의해주세요.')
    jpeg,(width,height)=normalized_jpeg(data,content_type)
    filename=uuid4().hex+'.jpg'
    image_path=directory/filename
    try:
        image_path.write_bytes(jpeg)
        atomic_json(directory/(filename+'.upload.json'),{
            'filename':filename,'uploaded_at':utc_now(),'width':width,'height':height,
            'sha256':hashlib.sha256(jpeg).hexdigest(),
            'source_sha256':hashlib.sha256(data).hexdigest(),
        })
    except Exception:
        image_path.unlink(missing_ok=True)
        raise
    return {'status':'uploaded','filename':filename,'image_id':filename[:-4],
            'image_width':width,'image_height':height,'analysis_status':'not_started',
            'message':'이미지 업로드 성공'}


def existing_image(filename,user):
    try: check_filename(filename)
    except ValueError as exc: raise problem(400,'INVALID_FILENAME',str(exc)) from exc
    directory=owner_dir(app.state.upload_dir,user)
    if not (directory/filename).is_file() or not (directory/(filename+'.upload.json')).is_file():
        raise problem(404,'IMAGE_NOT_FOUND','이 계정의 업로드 사진을 찾을 수 없습니다.')
    return directory


def comparison_detections(raw, rows):
    detections=copy.deepcopy(raw.get('detections',[]))
    for detection in detections:
        positions=[row for row in rows if detection['id'] in row['detection_ids']]
        detection['verification_passed']=bool(positions) and all(
            row['verification_passed'] and not row.get('appearance_location_verified') for row in positions
        )
        detection['review_status']=positions[0]['review_status'] if len(positions)==1 else '위치매핑_확인필요'
    for index,row in enumerate(rows,1):
        if not row.get('appearance_location_verified') or row['f04_status_code'] not in (1,2):
            continue
        match,=row['visual_matches']
        box=match['bbox']
        mark_id=f'v{index}'
        for detection in detections:
            if detection['id'] in row['detection_ids'] and (
                covered_fraction(box,detection['bbox'])>=.80 or covered_fraction(detection['bbox'],box)>=.80
            ):
                detection['overlay_parent_id']=mark_id
        row['detection_ids']=[*row['detection_ids'],mark_id]
        detections.append({'id':mark_id,'bbox':box,'confidence':None,'product_ids':[match['sku']],
                           'source':'registered_appearance','location_verified':True,
                           'verification_passed':row['verification_passed'],'review_status':row['review_status']})
    return detections


def run_analysis(filename,user,warehouse_id,rack):
    directory=existing_image(filename,user)
    with job_lock(directory,filename):
        upload=json.loads((directory/(filename+'.upload.json')).read_text(encoding='utf-8'))
        actual_sha=hashlib.sha256((directory/filename).read_bytes()).hexdigest()
        if actual_sha!=upload['sha256']:
            raise ValueError('업로드 사진이 변경되었습니다. 원본 사진을 다시 업로드해주세요.')
        fixture=fixture_configuration(upload,user.company,warehouse_id)
        inventory=None
        if fixture:
            catalog_path,map_path,locations=fixture
            inventory=fixture_inventory(user.company,id_token=user.firebase_id_token)
            erp_revision=inventory['revision'] if inventory is not None else 'FIRESTORE_UNAVAILABLE'
        else:
            load_erp(app.state.erp_path)
            catalog_path,map_path=app.state.catalog_path,app.state.location_map_path
            locations=load_locations(map_path)
            erp_revision=hashlib.sha256(Path(app.state.erp_path).read_bytes()).hexdigest()
        catalog=load_catalog(catalog_path)
        visual_assets,visual_revision=load_visual_assets(catalog_path) if fixture else (None,None)
        revision={'pipeline':PIPELINE_VERSION,'model_sha256':model_digest(),
                  'erp_sha256':erp_revision,
                  'catalog_sha256':hashlib.sha256(Path(catalog_path).read_bytes()).hexdigest(),
                  'location_map_sha256':hashlib.sha256(Path(map_path).read_bytes()).hexdigest(),
                  'fixture_profile':bool(fixture),
                  'visual_reference_sha256':visual_revision,
                  'f04_sha256':f04_revision(),
                  'image_sha256':upload['sha256'],'warehouse_id':warehouse_id,'rack':rack}
        signature=hashlib.sha256(json.dumps(revision,sort_keys=True).encode()).hexdigest()
        ai_revision={key:value for key,value in revision.items() if key not in ('erp_sha256','f04_sha256')}
        ai_signature=hashlib.sha256(json.dumps(ai_revision,sort_keys=True).encode()).hexdigest()
        target=directory/(filename+'.analysis.json')
        raw=None
        if target.is_file():
            cached=json.loads(target.read_text(encoding='utf-8'))
            if cached.get('signature')==signature: return cached
            if cached.get('analysis_signature')==ai_signature:
                raw=cached['analysis']
        acquired=raw is None and _analysis_slot.acquire(blocking=False)
        if raw is None and not acquired:
            raise problem(429,'ANALYSIS_BUSY','다른 사진을 분석 중입니다. 잠시 후 다시 시도해주세요.')
        try:
            if raw is None:
                raw=pipeline.analyze_image(directory/filename,rack_number=rack,catalog=catalog,
                                           location_map=locations,company_code=user.company,warehouse_id=warehouse_id)
                if visual_assets:
                    add_visual_observations(directory/filename,raw,locations,catalog,visual_assets)
            if fixture:
                comparison=review_fixture(raw,inventory)
            else:
                comparison=review_observations(raw,app.state.erp_path,user.company)
                if locations.get('images'):
                    comparison['data_source']=locations.get('data_source','LOCAL_TEST_ERP')
            detections=comparison_detections(raw,comparison['results'])
            result={**comparison,'module':'F03','status':raw.get('status','review_required'),
                    'job_id':filename[:-4],'filename':filename,'warehouse_id':warehouse_id,
                    'company_code':user.company,
                    'rack':raw['rack'],'analyzed_at':utc_now(),'revision':revision,
                    'detections':detections,'model':raw.get('model',{}),
                    'image_width':raw['image_width'],'image_height':raw['image_height'],
                    'total_candidates':raw.get('total_candidates',sum(r['YOLO탐지후보수'] for r in raw['results'])),
                    'ocr_status':raw.get('ocr_status','completed'),
                    'visual_status':raw.get('visual_status'),
                    'ocr_tokens':raw.get('ocr_tokens',[]),'ocr_languages':raw.get('ocr_languages',[]),
                    'catalog_id':raw.get('catalog_id','UNCONFIGURED'),
                    'verification_scope':raw.get('verification_scope','unmapped_photo')}
            snapshot={'signature':signature,'analysis_signature':ai_signature,'analysis':raw,'comparison':result}
            atomic_json(target,snapshot)
            return snapshot
        finally:
            if acquired: _analysis_slot.release()


async def checked_analysis(filename,user,warehouse_id,rack):
    try:
        return await run_in_threadpool(run_analysis,filename,user,warehouse_id,rack)
    except HTTPException as he:
        import traceback
        print(f"--- [F-03 CHECKED ANALYSIS HTTP EXCEPTION] Status: {he.status_code}, Detail: {he.detail} ---")
        traceback.print_exc()
        raise
    except ERPDataError as exc:
        import traceback
        traceback.print_exc()
        raise problem(503,'ERP_DATA_INVALID',str(exc)) from exc
    except AuditConfigError as exc:
        import traceback
        traceback.print_exc()
        raise problem(503,'AUDIT_CONFIG_INVALID',str(exc)) from exc
    except FileNotFoundError as exc:
        import traceback
        traceback.print_exc()
        raise problem(503,'MODEL_UNAVAILABLE','분석 모델 설정을 확인해주세요.') from exc
    except ValueError as exc:
        import traceback
        traceback.print_exc()
        raise problem(422,'ANALYSIS_INPUT_INVALID',str(exc)) from exc
    except Exception as exc:
        import traceback
        traceback.print_exc()
        logging.exception('F03 analysis failed')
        raise problem(500,'ANALYSIS_FAILED',f'사진 분석에 실패했습니다: {str(exc)}') from exc


@app.get('/health')
def health(mode:AuditMode='live'):
    errors=[]
    external_auth=external_f04_directory() is not None
    if not external_auth:
        try: firebase_config()
        except FirebaseAccessError: errors.append('FIREBASE_APP_CONFIG_INVALID')
    if not pipeline.MODEL_PATH.is_file(): errors.append('MODEL_UNAVAILABLE')
    catalog,locations={'catalog_id':'FIREBASE_REGISTERED_CODES','products':[]},{'images':[]}
    if mode=='simulation':
        try: load_erp(app.state.erp_path)
        except ERPDataError: errors.append('ERP_DATA_INVALID')
        try:
            catalog=load_catalog(app.state.catalog_path)
            locations=load_locations(app.state.location_map_path)
        except AuditConfigError: errors.append('AUDIT_CONFIG_INVALID')
        try: f04_revision()
        except AuditConfigError: errors.append('F04_UNAVAILABLE')
    if errors: raise HTTPException(503,detail={'status':'not_ready','errors':errors})
    return {'module':'F03','status':'ok','pipeline_version':PIPELINE_VERSION,
            'firebase_auth_backend':'F04_EXTERNAL' if external_auth else 'FIREBASE_ID_TOKEN',
            'data_source':locations.get('data_source','LOCAL_TEST_ERP') if mode=='simulation' else 'FIREBASE_READ_ONLY',
            'catalog_id':catalog.get('catalog_id','UNCONFIGURED'),'catalog_products':len(catalog['products']),
            'ocr_languages':['en'],'mapped_photos':len(locations['images']),
            'inventory_verification':'requires_verified_pixel_and_location_evidence'}


@app.post('/f03/audit/upload-image')
async def upload_audit_image(image:UploadFile=File(...),user:AuditUser=Depends(require_user)):
    try:
        print(f"--- [UPLOAD-IMAGE RECEIVED] content_type: {image.content_type}, filename: {image.filename} ---")
        data=bytearray()
        while chunk:=await image.read(128*1024):
            data.extend(chunk)
            if len(data)>MAX_UPLOAD_BYTES:
                raise problem(413,'IMAGE_TOO_LARGE','16MB 이하의 사진을 선택해주세요.')
        actual_content_type = 'image/png' if image.content_type and 'png' in image.content_type else 'image/jpeg'
        try: return await run_in_threadpool(save_upload,bytes(data),actual_content_type,user)
        except ValueError as exc: raise problem(400,'INVALID_IMAGE',str(exc)) from exc
    finally:
        await image.close()


@app.post('/f03/audit/analyze-image')
async def analyze_uploaded_image(request:Request,filename:str,user:AuditUser=Depends(require_user),
                                warehouse_id:int=Query(default=1,gt=0),
                                rack:str|None=Query(default=None,pattern=r'^[A-Za-z0-9_-]{1,32}$'),
                                mode:AuditMode='live',warehouse_code:str|None=None):
    if mode=='simulation':
        raw=(await checked_analysis(filename,user,warehouse_id,rack))['analysis']
    else:
        raw=await run_in_threadpool(run_live_analysis,request,filename,warehouse_id,user,warehouse_code,raw_only=True,
                                    test_rack_labels=mode=='test_rack_labels')
    return {**raw,'job_id':filename[:-4],'filename':filename}


@app.post('/f03/audit/analyze-and-compare')
async def analyze_and_compare(request:Request,filename:str,user:AuditUser=Depends(require_user),
                              warehouse_id:int=Query(default=1,gt=0),
                              rack:str|None=Query(default=None,pattern=r'^[A-Za-z0-9_-]{1,32}$'),
                              mode:AuditMode='live',warehouse_code:str|None=None):
    if mode=='simulation':
        return (await checked_analysis(filename,user,warehouse_id,rack))['comparison']
    return await run_in_threadpool(run_live_analysis,request,filename,warehouse_id,user,warehouse_code,
                                   test_rack_labels=mode=='test_rack_labels')


@app.post('/f04/audit/review-job')
async def review_f03_job(request:Request,filename:str,user:AuditUser=Depends(require_user),
                         warehouse_id:int=Query(default=1,gt=0),mode:AuditMode='live',warehouse_code:str|None=None):
    # Accept an owned job, never client product/confidence/verification fields.
    if mode=='simulation':
        result=(await checked_analysis(filename,user,warehouse_id,None))['comparison']
    else:
        result=await run_in_threadpool(run_live_analysis,request,filename,warehouse_id,user,warehouse_code,
                                       test_rack_labels=mode=='test_rack_labels')
    return {**result,'module':'F04','source_module':'F03'}


def result_suffix(mode):
    return {'live':'.live.json','simulation':'.analysis.json','test_rack_labels':TEST_RACK_LABEL_SUFFIX}[mode]


def saved_result(user,filename=None,mode='live'):
    directory=owner_dir(app.state.upload_dir,user)
    suffix=result_suffix(mode)
    if filename:
        existing_image(filename,user)
        paths=[directory/(filename+suffix)]
    else:
        paths=sorted(directory.glob('*'+suffix),key=lambda p:p.stat().st_mtime_ns,reverse=True)
    if not paths or not paths[0].is_file():
        raise problem(404,'RESULT_NOT_FOUND','저장된 분석 결과가 없습니다. 먼저 사진을 분석해주세요.')
    try:
        result=json.loads(paths[0].read_text(encoding='utf-8'))['comparison']
        revision=result.get('revision',{})
        if revision.get('pipeline')!=PIPELINE_VERSION or (mode=='simulation' and revision.get('f04_sha256')!=f04_revision()):
            raise problem(409,'RESULT_STALE','이전 버전의 판정입니다. 같은 사진을 다시 분석해주세요.')
        return result
    except (OSError,ValueError,KeyError) as exc:
        raise problem(503,'RESULT_UNAVAILABLE','분석 결과를 읽을 수 없습니다. 다시 분석해주세요.') from exc


@app.get('/f03/audit/review-results')
async def get_review_results(user:AuditUser=Depends(require_user),filename:str|None=None,mode:AuditMode='live'):
    return await run_in_threadpool(saved_result,user,filename,mode)


@app.get('/f03/audit/integrated-results')
@app.get('/f03/audit/ai-results')
async def get_integrated_results(user:AuditUser=Depends(require_user),filename:str|None=None,mode:AuditMode='live'):
    result=await run_in_threadpool(saved_result,user,filename,mode)
    rows=[{**row,'erp_product_id':','.join(row['erp_product_ids']),
           'erp_registered':len(row['erp_product_ids']),'ai_candidates':row['yolo_candidates']} for row in result['results']]
    return {**result,'results':rows}


@app.get('/f03/audit/progress')
def get_audit_progress(user:AuditUser=Depends(require_user),mode:AuditMode='live'):
    directory=owner_dir(app.state.upload_dir,user)
    total=sum(1 for _ in directory.glob('*.upload.json'))
    analyzed=sum(1 for _ in directory.glob('*'+result_suffix(mode)))
    return {'module':'F03','data_source':'SAVED_ANALYSIS','unit':'photos',
            'summary':{'total':total,'completed':0,'analyzed':analyzed,
                       'simulated_completed':0,'recheck':analyzed,'not_checked':max(0,total-analyzed)},
            'locations':[]}


from f04_safe_review_api import router as f04_safe_review_router
app.include_router(f04_safe_review_router,dependencies=[Depends(require_user)])

from f03_live_api import TEST_RACK_LABEL_SUFFIX, router as live_router, run_live_analysis
app.state.pipeline_version=PIPELINE_VERSION
app.state.model_digest=model_digest
app.state.analysis_slot=_analysis_slot
app.include_router(live_router)
