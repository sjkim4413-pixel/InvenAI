"""API/ownership/cache tests use controlled observations, not photo accuracy evidence."""
import copy
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
import f03_live_api as live
from f03_auth import AuditUser,require_user
from f03_fixture_inventory import DEFAULT_FIXTURE_DIR
from f03_live_inventory import build_snapshot,InventoryUnavailable


class LiveService(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.patch=patch.object(api.app.state,'upload_dir',Path(self.temp.name));self.patch.start();self.addCleanup(self.patch.stop)
        self.user=AuditUser('live-unit','ABC-001')
        api.app.dependency_overrides[require_user]=lambda:self.user
        self.addCleanup(api.app.dependency_overrides.clear)
        self.data={'companyCode':'ABC-001','itemCode':'BT-200','itemName':'Bluetooth','warehouse':'WH1',
                   'location':'B구역 R08-1층','quantity':50,'status':'NORMAL'}
        self.loader=patch.object(api.app.state,'live_inventory_loader',lambda company:build_snapshot(company,[('one',self.data)]),create=True)
        self.loader.start();self.addCleanup(self.loader.stop)
        self.client=TestClient(api.app,raise_server_exceptions=False)
        image=io.BytesIO();Image.new('RGB',(160,100),'white').save(image,format='JPEG')
        self.ordinary_image=image.getvalue()
        response=self.client.post('/f03/audit/upload-image',files={'image':('fixture.jpg',self.ordinary_image,'image/jpeg')})
        self.filename=response.json()['filename']
        self.params={'filename':self.filename,'warehouse_id':3}
        self.code={'id':'code1','format':'EAN_13','value':'4006381333931','bbox':[.2,.2,.3,.3],'checksum_valid':True}

    def raw(self,*args,**kwargs):
        mapping=kwargs['location_map'];regions=mapping['images'][0]['regions'] if mapping['images'] else []
        return {'status':'analyzed','rack':'MAPPED' if regions else 'UNMAPPED','image_width':160,'image_height':100,
            'total_candidates':1,'ocr_tokens':[],'ocr_status':'completed',
            'detections':[{'id':'d1','bbox':[.1,.1,.8,.8],'confidence':.9,'detection_stable':True,
                           'support_scales':[640,1024,1280],'product_ids':[]}],
            'results':[{'랙번호':r['rack'],'단수':r['floor'],'recognized_product_ids':[],
                        'verification_passed':False,'ocr_max_confidence':0} for r in regions]}

    def warehouse(self):
        result=self.client.post('/f03/audit/live/warehouse-binding',json={'warehouse_id':3,'warehouse_code':'WH1'})
        self.assertEqual(result.status_code,200,result.text)

    def analyze(self):return self.client.post('/f03/audit/live/analyze-and-compare',params=self.params)

    def test_real_context_is_company_scoped_and_unknown_binding_is_rejected(self):
        response=self.client.get('/f03/audit/live/context',params={'warehouse_id':3,'company':'OTHER'})
        self.assertEqual(response.status_code,200,response.text)
        self.assertEqual(response.json()['company_code'],'ABC-001')
        self.assertEqual(response.json()['warehouses'],['WH1'])
        bad=self.client.post('/f03/audit/live/warehouse-binding',json={'warehouse_id':3,'warehouse_code':'OTHER'})
        self.assertEqual(bad.status_code,422)
        forged=self.client.post('/f03/audit/live/warehouse-binding',json={'warehouse_id':3,'warehouse_code':'WH1','verification_passed':True})
        self.assertEqual(forged.status_code,422)

    def test_snapshot_failure_does_not_use_local_test_erp(self):
        with patch.object(api.app.state,'live_inventory_loader',side_effect=InventoryUnavailable('unavailable')):
            result=self.client.get('/f03/audit/live/context',params={'warehouse_id':3})
            self.assertEqual(result.status_code,503)
            self.assertEqual(result.json()['detail']['code'],'LIVE_INVENTORY_UNAVAILABLE')

    def test_main_route_reads_photo_sku_and_rack_before_live_erp_comparison(self):
        other={**self.data,'itemCode':'BT-201','location':'B구역 R09-1층'}
        raw=self.raw(location_map={'images':[]})
        raw['ocr_tokens']=[
            {'id':'t1','text':'SKU:','confidence':.98,'bbox':[.2,.17,.25,.19]},
            {'id':'t2','text':'BT','confidence':.96,'bbox':[.2,.20,.23,.22]},
            {'id':'t3','text':'200','confidence':.95,'bbox':[.24,.20,.27,.22]},
            {'id':'t4','text':'R08','confidence':.94,'bbox':[.42,.81,.47,.83]},
            {'id':'t5','text':'1F','confidence':.99,'bbox':[.48,.81,.52,.83]},
        ]
        raw['detections'].append({'id':'fragment','bbox':[.19,.16,.4,.32],
                                  'confidence':.23,'detection_stable':False})
        load=lambda company:build_snapshot(company,[('one',self.data),('two',other)])
        with (
            patch.object(api.app.state,'live_inventory_loader',load),
            patch.object(api.pipeline,'analyze_image',return_value=raw) as inference,
            patch.object(live,'decode_codes',return_value=[]),
            patch.object(api,'fixture_configuration',side_effect=AssertionError('test profile used')),
        ):
            first=self.client.post('/f03/audit/analyze-and-compare',params=self.params)
            self.assertEqual(first.status_code,200,first.text)
            result=first.json()
            self.assertEqual(result['data_source'],'FIREBASE_READ_ONLY')
            self.assertTrue(result['detections'][0]['product_location_verified'])
            self.assertEqual(result['detections'][1]['overlay_parent_id'],'d1')
            row,=result['results']
            self.assertEqual((row['identity_method'],row['location_method']),('OCR_PRODUCT_CODE','ocr_rack_label'))
            self.assertEqual(row['f04_status'],'PRODUCT_LOCATION_MATCH')
            self.assertEqual(row['product_names'],{'BT-200':'Bluetooth'})
            self.assertEqual(row['location_evidence']['ocr_token_ids'],['t4','t5'])
            self.assertFalse(row['quantity_verified'])
            other.update(itemCode='BT-200')
            self.data.update(itemCode='BT-201')
            checked=self.client.post('/f04/audit/review-job',params=self.params)
            self.assertEqual(checked.status_code,200,checked.text)
            row,=checked.json()['results']
            self.assertEqual(row['f04_status'],'MISPLACED')
            self.assertEqual(row['ai_product_ids'],['BT-200'])
            self.assertEqual(row['erp_product_ids'],['BT-201'])
            self.assertEqual(inference.call_count,1)

    def test_multiple_warehouses_require_an_explicit_selection(self):
        second={**self.data,'warehouse':'WH2'}
        load=lambda company:build_snapshot(company,[('one',self.data),('two',second)])
        with (
            patch.object(api.app.state,'live_inventory_loader',load),
            patch.object(api.pipeline,'analyze_image',side_effect=self.raw) as inference,
            patch.object(live,'decode_codes',return_value=[]),
        ):
            result=self.client.post('/f03/audit/analyze-and-compare',params=self.params)
            self.assertEqual(result.status_code,409,result.text)
            self.assertEqual(result.json()['detail']['warehouses'],['WH1','WH2'])
            inference.assert_not_called()
            result=self.client.post('/f03/audit/analyze-and-compare',params={**self.params,'warehouse_code':'WH2'})
            self.assertEqual(result.status_code,200,result.text)
            reviewed=self.client.post('/f04/audit/review-job',params=self.params)
            self.assertEqual(reviewed.status_code,200,reviewed.text)
            self.assertEqual(reviewed.json()['warehouse_code'],'WH2')

    def test_code_and_manual_location_link_do_not_claim_inventory_quantity(self):
        self.warehouse()
        with patch.object(api.pipeline,'analyze_image',side_effect=self.raw),patch.object(live,'decode_codes',return_value=[self.code]):
            first=self.analyze();self.assertEqual(first.status_code,200,first.text)
            bound=self.client.post('/f03/audit/live/location-binding',json={**self.params,'detection_id':'d1','position_id':'B/R08/1/-'})
            self.assertEqual(bound.status_code,200,bound.text)
            linked=self.client.post('/f03/audit/live/identifier-binding',json={'filename':self.filename,'code_id':'code1','sku':'BT-200'})
            self.assertEqual(linked.status_code,200,linked.text)
            result=self.analyze();self.assertEqual(result.status_code,200,result.text)
            row=result.json()['results'][0]
            self.assertTrue(row['product_location_verified'])
            self.assertFalse(row['verification_passed'])
            self.assertEqual(row['expected_quantity'],50)
            self.assertIsNone(row['observed_quantity'])
            self.assertEqual(result.json()['detections'][0]['product_ids'],['BT-200'])
            self.assertEqual(result.json()['data_source'],'FIREBASE_READ_ONLY')

    def test_registry_and_erp_changes_reuse_the_same_actual_observation(self):
        self.warehouse()
        with patch.object(api.pipeline,'analyze_image',side_effect=self.raw) as inference,patch.object(live,'decode_codes',return_value=[self.code]):
            first=self.analyze();self.assertEqual(first.status_code,200,first.text)
            response=self.client.post('/f03/audit/live/identifier-binding',json={'filename':self.filename,'code_id':'code1','sku':'BT-200'})
            self.assertEqual(response.status_code,200,response.text)
            self.assertEqual(self.analyze().status_code,200)
            self.data['quantity']=51
            changed=self.analyze();self.assertEqual(changed.status_code,200,changed.text)
            self.assertEqual(inference.call_count,1)
            self.assertNotEqual(first.json()['inventory_revision'],changed.json()['inventory_revision'])

    def test_bundled_rack_label_mode_uses_only_matching_test_stock_and_separate_results(self):
        self.user=AuditUser('live-unit','ABC-002')
        photo=sorted(DEFAULT_FIXTURE_DIR.glob('*.png'))[1]
        warehouse='AI 이미지 테스트 창고 02'
        data={**self.data,'companyCode':self.user.company,'warehouse':warehouse,'location':'A01-5F',
              'itemCode':'SIM-AI02-P01','isTestData':True,'dataSource':'SIMULATED_AI_IMAGE_ERP',
              'photoReference':photo.name,'photoLocationLabel':'A01-5F'}
        other={**data,'itemCode':'REAL-SKU','isTestData':False}
        raw=self.raw(location_map={'images':[]})
        raw['ocr_tokens']=[{'id':'rack','text':'AO1-SF','confidence':.96,'bbox':[.42,.81,.50,.83]}]
        uploaded=self.client.post('/f03/audit/upload-image',files={'image':(photo.name,photo.read_bytes(),'image/png')})
        self.assertEqual(uploaded.status_code,200,uploaded.text)
        filename=uploaded.json()['filename']
        params={'filename':filename,'mode':'test_rack_labels'}
        endpoint='/f03/audit/analyze-and-compare'
        with (
            patch.object(api.app.state,'live_inventory_loader',
                         lambda company:build_snapshot(company,[('fixture',data),('ordinary',other)])),
            patch.object(api.pipeline,'analyze_image',return_value=raw) as inference,
            patch.object(live,'decode_codes',return_value=[]),
        ):
            wrong_image=self.client.post(endpoint,params={**params,'filename':self.filename})
            self.assertEqual(wrong_image.status_code,404)
            wrong_warehouse=self.client.post(endpoint,params={**params,'warehouse_code':'WH1'})
            self.assertEqual(wrong_warehouse.status_code,422)
            ordinary_upload=self.client.post('/f03/audit/upload-image',
                files={'image':('ordinary.jpg',self.ordinary_image,'image/jpeg')}).json()
            denied=self.client.post(endpoint,params={**params,'filename':ordinary_upload['filename']})
            self.assertEqual(denied.status_code,422,denied.text)
            self.assertEqual(inference.call_count,0)

            comparison=self.client.post(endpoint,params=params)
            self.assertEqual(comparison.status_code,200,comparison.text)
            result=comparison.json()
            self.assertEqual(result['warehouse_code'],warehouse)
            row,=result['results']
            self.assertEqual(row['f04_status'],'TEST_RACK_LABEL_MATCH')
            self.assertEqual(row['ai_product_ids'],['A01-5F'])
            self.assertEqual(row['erp_product_ids'],['A01-5F'])
            self.assertFalse(row['product_location_verified'])
            self.assertTrue(result['detections'][0]['test_label_match'])
            self.assertEqual(result['ocr_tokens'][0]['kind'],'location_label')
            review=self.client.post('/f04/audit/review-job',params=params)
            self.assertEqual(review.status_code,200,review.text)
            self.assertEqual(review.json()['results'][0]['f04_status'],'TEST_RACK_LABEL_MATCH')
            self.assertEqual(inference.call_count,1)
            self.assertEqual(self.client.get('/f03/audit/review-results',params={'filename':filename}).status_code,404)
            saved=self.client.get('/f03/audit/review-results',params=params)
            self.assertEqual(saved.status_code,200,saved.text)

            live_result=self.client.post(endpoint,params={'filename':filename})
            self.assertEqual(live_result.status_code,200,live_result.text)
            live_row,=live_result.json()['results']
            self.assertEqual(live_row['f04_status'],'MANUAL_REVIEW_REQUIRED')
            self.assertEqual(live_row['ai_product_ids'],[])
            self.assertCountEqual(live_row['erp_product_ids'],['SIM-AI02-P01','REAL-SKU'])
            self.assertNotIn('test_label_match',live_row)

    def test_additional_overview_and_closeup_share_stock_without_selecting_other_bays(self):
        from f03_fixture_inventory import ADDITIONAL_WAREHOUSE, fixture_rack_codes

        manifest=json.loads((DEFAULT_FIXTURE_DIR/'rack_labelled_photos.json').read_text(encoding='utf-8'))
        wide='QAD_rack_labels.png'
        closeup='QAD_closeup_rack_labels.png'
        record={'companyCode':'ABC-002','itemCode':'SIM-QAD02-3F-01','warehouse':ADDITIONAL_WAREHOUSE,
                'location':'QAD02-3F-01','quantity':1,'status':'NORMAL','isTestData':True,
                'dataSource':'SIMULATED_AI_IMAGE_ERP','photoReference':wide,
                'alternatePhotoReference':closeup,'photoLocationLabel':'QAD02-3F-01'}
        wide_only={**record,'itemCode':'SIM-QAD01-3F-01','location':'QAD01-3F-01',
                   'photoLocationLabel':'QAD01-3F-01','alternatePhotoReference':''}
        stock=build_snapshot('ABC-002',[('shared',record),('wide',wide_only)])
        for photo,expected in ((wide,{'QAD02-3F-01','QAD01-3F-01'}),(closeup,{'QAD02-3F-01'})):
            warehouse,positions,codes=fixture_rack_codes({'source_sha256':manifest[photo],'sha256':'ignored'},stock,1)
            self.assertEqual(warehouse,ADDITIONAL_WAREHOUSE)
            self.assertEqual({p['source_location'] for p in positions},expected)
            self.assertEqual(set(codes.values()),expected)

    def test_unknown_positions_and_nonexistent_photographed_codes_cannot_be_bound(self):
        self.warehouse()
        with patch.object(api.pipeline,'analyze_image',side_effect=self.raw),patch.object(live,'decode_codes',return_value=[]):
            self.assertEqual(self.analyze().status_code,200)
            bad=self.client.post('/f03/audit/live/location-binding',json={**self.params,'detection_id':'d1','position_id':'OTHER'})
            self.assertEqual(bad.status_code,422)
            bad=self.client.post('/f03/audit/live/identifier-binding',json={'filename':self.filename,'code_id':'fabricated','sku':'BT-200'})
            self.assertEqual(bad.status_code,422)

    def test_live_endpoints_require_login_and_photo_ownership(self):
        api.app.dependency_overrides.clear()
        self.assertEqual(self.client.get('/f03/audit/live/context',params={'warehouse_id':3}).status_code,401)
        self.assertEqual(self.analyze().status_code,401)
        api.app.dependency_overrides[require_user]=lambda:AuditUser('other','ABC-001')
        self.assertEqual(self.analyze().status_code,404)


if __name__=='__main__':unittest.main()
