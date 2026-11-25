"""Controlled OCR observations test decisions; these are not photo accuracy tests."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
import f03_api as api
import f05_comparison_demo as demo
from f03_auth import AuditUser, require_user
from f03_live_inventory import build_snapshot, warehouse_view, InventoryInputError


class F05Comparison(unittest.TestCase):
    def setUp(self):
        self.profile = json.loads((demo.FIXTURE_DIR/'product_comparison.json').read_text(encoding='utf-8'))
        self.documents = [('test-'+str(i), dict(companyCode='ABC-002', warehouse=self.profile['warehouse'],
            itemCode='HPX-'+str(i)*3, itemName='Test product', location=row['location'], quantity=1, status='NORMAL'))
            for i,row in enumerate(self.profile['regions'], 1)]
        self.source = build_snapshot('ABC-002', self.documents)

    def raw(self, codes=('HPX-111','HPX-222','HPX-333')):
        observations, tokens = [], []
        for i,(row,code) in enumerate(zip(self.profile['regions'],codes)):
            reads = [[dict(id=f'{i}-{view}',text=f'SKU {code}',confidence=confidence,bbox=row['load_bbox'])]
                     for view,confidence in enumerate((.95,.78))]
            observations.append(dict(location=row['location'],reads=reads))
            tokens.extend(token for view in reads for token in view)
        return dict(observations=observations,ocr_tokens=tokens,image_width=1536,image_height=1024,ocr_status='completed')

    def compare(self, raw):
        return demo.compare_test_regions(raw,self.source,warehouse_view(self.source,self.profile['warehouse']),self.profile)

    def test_swapped_real_observations_use_same_expected_stock(self):
        normal=self.compare(self.raw())
        swapped=self.compare(self.raw(('HPX-222','HPX-111','HPX-333')))
        self.assertEqual([r['f04_status_code'] for r in normal['results']],[1,1,1])
        self.assertEqual([r['f04_status_code'] for r in swapped['results']],[2,2,1])
        self.assertEqual([r['erp_product_ids'] for r in normal['results']],
                         [r['erp_product_ids'] for r in swapped['results']])
        for result in (normal,swapped):
            self.assertEqual(result['verification_scope'],'test_annotated_product_locations')
            for row in result['results']:
                self.assertFalse(row['verification_passed'])
                self.assertFalse(row['product_location_verified'])
                self.assertFalse(row['quantity_verified'])
                self.assertEqual(row['location_method'],'human_annotated_test_region')
            self.assertTrue(all(d['confidence'] is None for d in result['detections']))

    def test_unclear_contradictory_or_missing_ocr_is_held(self):
        for variant in ('weak','different','missing','no_prefix','ambiguous'):
            with self.subTest(variant=variant):
                raw=self.raw()
                reads=raw['observations'][0]['reads']
                if variant=='weak': reads[1][0]['confidence']=.69
                if variant=='different': reads[1][0]['text']='SKU HPX-222'
                if variant=='missing': reads[1].clear()
                if variant=='no_prefix': reads[1][0]['text']='HPX-111'
                if variant=='ambiguous': reads[0].append(dict(id='other',text='SKU HPX-222',confidence=.98,bbox=[.1,.1,.2,.2]))
                row=self.compare(raw)['results'][0]
                self.assertEqual(row['f04_status_code'],5)
                self.assertEqual(row['ai_product_ids'],[])

    def test_missing_positive_normal_stock_cannot_turn_green(self):
        for field,value in (('quantity',0),('status','ANOMALY')):
            with self.subTest(field=field):
                self.documents[0][1][field]=value
                self.source=build_snapshot('ABC-002',self.documents)
                self.assertEqual(self.compare(self.raw())['results'][0]['f04_status_code'],5)

    def test_profile_rejects_unknown_photo_company_warehouse_and_overlapping_regions(self):
        upload=dict(source_sha256=next(iter(self.profile['photos'].values())))
        self.assertEqual(demo.comparison_profile(upload,'ABC-002',1)['warehouse'],self.profile['warehouse'])
        for content,company,warehouse in (({},'ABC-002',1),(upload,'OTHER',1),(upload,'ABC-002',2)):
            with self.assertRaises(InventoryInputError): demo.comparison_profile(content,company,warehouse)
        with tempfile.TemporaryDirectory() as folder:
            corrupt=copy.deepcopy(self.profile)
            corrupt['regions'][1]['bbox']=corrupt['regions'][0]['bbox']
            (Path(folder)/'product_comparison.json').write_text(json.dumps(corrupt),encoding='utf-8')
            with patch.object(demo,'FIXTURE_DIR',Path(folder)):
                with self.assertRaises(InventoryInputError): demo.comparison_profile(upload,'ABC-002',1)

    def test_api_cache_rechecks_changed_inventory_and_isolates_modes_and_owners(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(api.app.state,'upload_dir',Path(folder)), \
             patch.object(api.app.state,'live_inventory_loader',lambda company:self.source,create=True), \
             patch.object(api.pipeline,'get_ai_models',return_value=(None,None)), \
             patch.object(demo,'read_test_regions',return_value=self.raw()) as inference:
            user=AuditUser('f05-unit','ABC-002')
            api.app.dependency_overrides[require_user]=lambda:user
            self.addCleanup(api.app.dependency_overrides.clear)
            client=TestClient(api.app)
            photo=demo.FIXTURE_DIR.parent/'test_photos/comparison/retired/compare_01_normal.png'
            uploaded=client.post('/f03/audit/upload-image',files={'image':(photo.name,photo.read_bytes(),'image/png')})
            self.assertEqual(uploaded.status_code,200,uploaded.text)
            params=dict(filename=uploaded.json()['filename'],mode=demo.MODE,warehouse_code=self.profile['warehouse'])
            first=client.post('/f03/audit/analyze-and-compare',params=params)
            self.assertEqual(first.status_code,200,first.text)
            self.assertEqual([r['f04_status_code'] for r in first.json()['results']],[1,1,1])
            self.documents[0][1]['itemCode']='HPX-222'
            self.documents[1][1]['itemCode']='HPX-111'
            self.source=build_snapshot('ABC-002',self.documents)
            reviewed=client.post('/f04/audit/review-job',params=params)
            self.assertEqual(reviewed.status_code,200,reviewed.text)
            self.assertEqual([r['f04_status_code'] for r in reviewed.json()['results']],[2,2,1])
            self.assertEqual(inference.call_count,1)
            saved=client.get('/f03/audit/review-results',params=params)
            self.assertEqual(saved.status_code,200,saved.text)
            self.assertEqual(client.get('/f03/audit/review-results',params={**params,'mode':'live'}).status_code,404)
            user=AuditUser('another-user','ABC-002')
            self.assertEqual(client.post('/f03/audit/analyze-and-compare',params=params).status_code,404)
