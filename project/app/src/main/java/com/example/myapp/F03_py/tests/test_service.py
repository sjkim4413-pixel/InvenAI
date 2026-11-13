import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image
from fastapi.testclient import TestClient
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import f03_api as api
import f03_fixture_inventory
from f03_auth import AuditUser,require_user
from f03_live_inventory import build_snapshot
from audit_dataset import audit_dataset


class Service(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.upload_dir=api.app.state.upload_dir
        api.app.state.upload_dir=Path(self.temp.name)
        self.addCleanup(setattr,api.app.state,'upload_dir',self.upload_dir)
        self.addCleanup(api.app.dependency_overrides.clear)
        self.user=AuditUser('test-user','TEST-COMPANY')
        api.app.dependency_overrides[require_user]=lambda:self.user
        self.client=TestClient(api.app,raise_server_exceptions=False)
        self.client.params={'mode':'simulation'}

    def image(self,orientation=None):
        image=Image.new('RGB',(160,100),'green')
        data=io.BytesIO()
        exif=Image.Exif()
        if orientation: exif[274]=orientation
        image.save(data,format='JPEG',exif=exif)
        return data.getvalue()

    def upload(self,data=None):
        response=self.client.post('/f03/audit/upload-image',files={'image':('test.jpg',self.image() if data is None else data,'image/jpeg')})
        return response

    def raw_result(self,*args,**kwargs):
        return {'status':'review_required','rack':'UNMAPPED','total_positions':1,
                'image_width':160,'image_height':100,'total_candidates':1,
                'detections':[{'id':'d1','bbox':[.1,.1,.8,.8],'confidence':.7,'floor':None,'product_ids':[]}],
                'results':[{'랙번호':'UNMAPPED','단수':None,'YOLO탐지후보수':1,
                            '인식제품ID':'','verification_passed':False,'location_verified':False}]}

    def test_requires_authentication(self):
        api.app.dependency_overrides.clear()
        self.assertEqual(self.upload().status_code,401)
        self.assertEqual(self.client.get('/f03/audit/progress').status_code,401)

    def test_rejects_corrupt_and_oversized_uploads(self):
        self.assertEqual(self.upload(b'not an image').status_code,400)
        with patch.object(api,'MAX_UPLOAD_BYTES',20):
            self.assertEqual(self.upload().status_code,413)
        self.assertFalse(list(Path(self.temp.name).rglob('*.jpg')))

    def test_exif_is_applied_before_metadata_is_removed(self):
        response=self.upload(self.image(6))
        self.assertEqual(response.status_code,200)
        self.assertEqual((response.json()['image_width'],response.json()['image_height']),(100,160))
        filename=response.json()['filename']
        path=next(Path(self.temp.name).rglob(filename))
        with Image.open(path) as image:
            self.assertEqual(image.size,(100,160))
            self.assertIsNone(image.getexif().get(274))

    def test_owner_isolation(self):
        filename=self.upload().json()['filename']
        self.user=AuditUser('someone-else','TEST-COMPANY')
        response=self.client.post('/f03/audit/analyze-and-compare',params={'filename':filename})
        self.assertEqual(response.status_code,404)

    def test_results_are_cached_and_read_back_with_evidence(self):
        filename=self.upload().json()['filename']
        with patch.object(api.pipeline,'analyze_image',side_effect=self.raw_result) as inference:
            first=self.client.post('/f03/audit/analyze-and-compare',params={'filename':filename,'warehouse_id':3})
            second=self.client.post('/f03/audit/analyze-and-compare',params={'filename':filename,'warehouse_id':3})
            self.assertEqual(first.status_code,200,first.text)
            self.assertEqual(first.json(),second.json())
            self.assertEqual(inference.call_count,1)
        result=self.client.get('/f03/audit/review-results',params={'filename':filename})
        self.assertEqual(result.json(),first.json())
        self.assertEqual(result.json()['warehouse_id'],3)
        self.assertEqual(result.json()['company_code'],'TEST-COMPANY')
        self.assertEqual(result.json()['detections'][0]['bbox'],[.1,.1,.8,.8])
        self.assertEqual(result.json()['comparison_module'],'F04_SAFE_MULTI_COMPARE_WITH_LOCAL_ERP')
        f04=self.client.post('/f04/audit/review-job',params={'filename':filename,'warehouse_id':3})
        self.assertEqual(f04.status_code,200,f04.text)
        self.assertEqual(f04.json()['module'],'F04')
        self.assertEqual(f04.json()['results'],first.json()['results'])
        self.assertFalse(result.json()['results'][0]['verification_passed'])
        self.assertIn('model_sha256',result.json()['revision'])

    def test_erp_error_and_model_readiness_are_explicit(self):
        filename=self.upload().json()['filename']
        with patch.object(api.app.state,'erp_path',Path(self.temp.name)/'missing.csv'):
            self.assertEqual(self.client.get('/health').status_code,503)
            response=self.client.post('/f03/audit/analyze-and-compare',params={'filename':filename})
            self.assertEqual(response.status_code,503)
            self.assertEqual(response.json()['detail']['code'],'ERP_DATA_INVALID')
        with patch.object(api.pipeline,'MODEL_PATH',Path(self.temp.name)/'missing.pt'):
            self.assertEqual(self.client.get('/health').status_code,503)

    def test_exact_abc002_test_photo_rechecks_firestore_without_repeating_ocr(self):
        image=self.image()
        fixture=Path(self.temp.name)/'fixtures'
        fixture.mkdir()
        (fixture/'location_map.json').write_text(json.dumps({
            'version':1,'company_code':'SIMULATED-AI-IMAGES','warehouse_id':1,'images':[{
                'sha256':[hashlib.sha256(image).hexdigest()],'width':160,'height':100,
                'regions':[{'rack':'AI01-P01','floor':5,'bbox':[0,0,1,1]}],
            }],
        }),encoding='utf-8')
        sku='SIM-AI01-P01'
        (fixture/'product_catalog.json').write_text(json.dumps({
            'version':1,'products':[{'sku':sku}],
        }),encoding='utf-8')
        self.user=AuditUser('test-user','ABC-002')
        filename=self.upload(image).json()['filename']
        params={'filename':filename,'warehouse_id':1}
        raw=self.raw_result()
        raw['results'][0].update({'랙번호':'AI01-P01','단수':5,'recognized_product_ids':[sku],
                                  'verification_passed':True,'location_verified':True})
        record={'companyCode':'ABC-002','warehouse':f03_fixture_inventory.WAREHOUSE,
                'itemCode':sku,'location':'AI01구역 P01-5단','quantity':1,'status':'NORMAL'}
        match=build_snapshot('ABC-002',[('fixture',record)])
        mismatch=build_snapshot('ABC-002',[('fixture',{**record,'itemCode':'OTHER'})])
        with (
            patch.object(f03_fixture_inventory,'FIXTURE_DIR',fixture),
            patch.object(api.pipeline,'analyze_image',return_value=raw) as inference,
            patch.object(api,'fixture_inventory',side_effect=[match,None,mismatch]) as lookup,
        ):
            results=[]
            for endpoint in ('/f03/audit/analyze-and-compare','/f03/audit/analyze-and-compare','/f04/audit/review-job'):
                response=self.client.post(endpoint,params=params)
                self.assertEqual(response.status_code,200,response.text)
                results.append(response.json())
            self.assertEqual(inference.call_count,1)
            self.assertEqual(lookup.call_count,3)
        first, unavailable, last=results
        self.assertEqual(first['data_source'],'FIRESTORE_TEST_ERP')
        self.assertTrue(first['results'][0]['product_location_verified'])
        self.assertFalse(first['results'][0]['quantity_verified'])
        self.assertFalse(unavailable['erp_comparison_available'])
        self.assertEqual(unavailable['results'][0]['erp_product_ids'],[])
        self.assertFalse(unavailable['results'][0]['verification_passed'])
        self.assertEqual(last['results'][0]['f04_status'],'MISPLACED')
        self.assertEqual(last['results'][0]['ai_product_ids'],[sku])

    def test_catalog_revision_invalidates_cache_and_modified_photo_is_rejected(self):
        filename=self.upload().json()['filename']
        catalog=Path(self.temp.name)/'catalog.json'
        catalog.write_text('{"version":1,"products":[]}',encoding='utf-8')
        with patch.object(api.app.state,'catalog_path',catalog),patch.object(api.pipeline,'analyze_image',side_effect=self.raw_result) as inference:
            params={'filename':filename,'warehouse_id':3}
            first=self.client.post('/f03/audit/analyze-and-compare',params=params)
            self.assertEqual(first.status_code,200,first.text)
            catalog.write_text('{"version":1,"products":[{"sku":"NEW123"}]}',encoding='utf-8')
            second=self.client.post('/f03/audit/analyze-and-compare',params=params)
            self.assertEqual(second.status_code,200,second.text)
            self.assertEqual(inference.call_count,2)
            self.assertNotEqual(first.json()['revision']['catalog_sha256'],second.json()['revision']['catalog_sha256'])
            next(Path(self.temp.name).rglob(filename)).write_bytes(self.image(6))
            changed=self.client.post('/f03/audit/analyze-and-compare',params=params)
            self.assertEqual(changed.status_code,422)

    def test_invalid_catalog_is_an_explicit_service_error(self):
        filename=self.upload().json()['filename']
        catalog=Path(self.temp.name)/'catalog.json'
        catalog.write_text('{"version":1,"products":[{"sku":"X"},{"sku":"X"}]}',encoding='utf-8')
        with patch.object(api.app.state,'catalog_path',catalog):
            self.assertEqual(self.client.get('/health').status_code,503)
            result=self.client.post('/f03/audit/analyze-and-compare',params={'filename':filename})
            self.assertEqual(result.status_code,503)
            self.assertEqual(result.json()['detail']['code'],'AUDIT_CONFIG_INVALID')

    def test_erp_changes_recompare_the_same_pixel_observation(self):
        filename=self.upload().json()['filename']
        erp=Path(self.temp.name)/'erp.csv'
        erp.write_bytes(api.ERP_FILE.read_bytes())
        with patch.object(api.app.state,'erp_path',erp),patch.object(api.pipeline,'analyze_image',side_effect=self.raw_result) as inference:
            params={'filename':filename,'warehouse_id':3}
            first=self.client.post('/f03/audit/analyze-and-compare',params=params)
            self.assertEqual(first.status_code,200,first.text)
            erp.write_text(erp.read_text(encoding='utf-8-sig').replace('P001','OTHER123'),encoding='utf-8-sig')
            second=self.client.post('/f03/audit/analyze-and-compare',params=params)
            self.assertEqual(second.status_code,200,second.text)
            self.assertEqual(inference.call_count,1)
            self.assertNotEqual(first.json()['revision']['erp_sha256'],second.json()['revision']['erp_sha256'])

    def test_no_old_csv_or_other_user_result_is_returned(self):
        self.assertEqual(self.client.get('/f03/audit/review-results').status_code,404)
        self.assertEqual(self.client.get('/f03/audit/integrated-results').status_code,404)

    def test_dataset_overlap_is_rejected(self):
        root=Path(self.temp.name)/'dataset'
        for split in ('train','val'):
            (root/'images'/split).mkdir(parents=True)
            (root/'labels'/split).mkdir(parents=True)
            (root/'images'/split/'photo.jpg').write_bytes(self.image())
            (root/'labels'/split/'photo.txt').write_text('0 .5 .5 .4 .4')
        (root/'source_groups.json').write_text(json.dumps({'train/photo.jpg':'scene1','val/photo.jpg':'scene1'}))
        report=audit_dataset(root)
        self.assertFalse(report['ready_for_training'])
        self.assertTrue(any('중복' in message for message in report['errors']))

if __name__=='__main__': unittest.main()
