"""Safety and evidence tests for the closed-set reference-photo comparison."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import cv2
import numpy as np
from PIL import Image
from fastapi.testclient import TestClient
import f03_api as api
import f05_reference_pairs as pairs
from f03_auth import AuditUser,require_user
from f03_live_inventory import build_snapshot,InventoryInputError


class ReferencePixels(unittest.TestCase):
    def setUp(self):
        self.image=np.zeros((180,320,3),dtype=np.uint8)
        self.image[:,:160]=(30,30,210);self.image[:,160:] = (210,50,20)
        self.image[30:50,:160]=(40,180,210);self.image[100:125,160:] = (180,160,40)
        self.regions=[dict(location='A01-1F',sku='ONE',bbox=[0,0,.5,1]),
                      dict(location='A02-1F',sku='TWO',bbox=[.5,0,1,1])]

    def test_actual_pixels_swap_observed_identity_without_labels_or_inventory(self):
        normal=pairs.observe(self.image,self.image,self.regions)
        moved=np.hstack((self.image[:,160:],self.image[:,:160]))
        changed=pairs.observe(moved,self.image,self.regions)
        self.assertEqual([r['sku'] for r in normal['observations']],['ONE','TWO'])
        self.assertEqual([r['sku'] for r in changed['observations']],['TWO','ONE'])

    def test_identical_reference_packages_are_ambiguous_even_at_zero_distance(self):
        duplicate=np.hstack((self.image[:,:160],self.image[:,:160]))
        raw=pairs.observe(duplicate,duplicate,self.regions)
        self.assertTrue(all(r['sku'] is None for r in raw['observations']))

    def test_unknown_plain_pixels_do_not_get_the_expected_product(self):
        raw=pairs.observe(np.full_like(self.image,255),self.image,self.regions)
        self.assertTrue(all(r['sku'] is None for r in raw['observations']))


class ReferenceApi(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.previous=api.app.state.upload_dir
        api.app.state.upload_dir=Path(self.temp.name)
        root=pairs.FIXTURE_DIR.parent
        self.docs=json.loads((root/'test_photos/accuracy43/inventory_readback.json').read_text(encoding='utf-8'))
        self.source=build_snapshot('ABC-002',self.docs)
        api.app.state.live_inventory_loader=lambda company:self.source
        api.app.dependency_overrides[require_user]=lambda:AuditUser('reference-tests','ABC-002')
        self.client=TestClient(api.app)
        self.profile=json.loads((pairs.FIXTURE_DIR/'reference_pairs.json').read_text(encoding='utf-8'))['pairs'][0]

    def tearDown(self):
        api.app.dependency_overrides.clear();api.app.state.upload_dir=self.previous
        del api.app.state.live_inventory_loader
        self.temp.cleanup()

    def upload(self,file):
        # The client filename deliberately conveys no scene or decision.
        response=self.client.post('/f03/audit/upload-image',files={'image':('arbitrary.png',file,'image/png')})
        self.assertEqual(response.status_code,200)
        return dict(filename=response.json()['filename'],mode=pairs.MODE)

    def test_filename_cannot_choose_normal_and_both_photos_use_identical_expected_erp(self):
        files=list(self.profile['photos'])
        results=[]
        for file in files:
            response=self.client.post('/f03/audit/analyze-and-compare',params=self.upload((pairs.FIXTURE_DIR/file).read_bytes()))
            self.assertEqual(response.status_code,200,response.text);results.append(response.json())
        self.assertTrue(all(r['test_product_location_match'] for r in results[0]['results']))
        self.assertEqual(sum(not r['test_product_location_match'] for r in results[1]['results']),2)
        self.assertEqual([r['erp_product_ids'] for r in results[0]['results']],[r['erp_product_ids'] for r in results[1]['results']])
        self.assertFalse(any(r['product_location_verified'] or r['quantity_verified'] or r['verification_passed']
                             for result in results for r in result['results']))

    def test_erp_change_reuses_pixels_and_removes_match_without_writing_stock(self):
        params=self.upload((pairs.FIXTURE_DIR/self.profile['reference']).read_bytes())
        self.assertEqual(self.client.post('/f03/audit/analyze-and-compare',params=params).status_code,200)
        changed=copy.deepcopy(self.docs);changed[0][1]['quantity']=0
        self.source=build_snapshot('ABC-002',changed)
        with patch.object(pairs,'observe',side_effect=AssertionError('reuse real pixels')):
            response=self.client.post('/f04/audit/review-job',params=params)
        self.assertEqual(response.status_code,200,response.text)
        self.assertEqual(sum(r['test_product_location_match'] for r in response.json()['results']),19)
        self.assertFalse(response.json()['firebase_inventory_modified'])

    def test_unknown_photo_other_company_and_tampered_reference_are_rejected(self):
        content=io.BytesIO();Image.new('RGB',(200,200),'white').save(content,format='PNG')
        params=self.upload(content.getvalue())
        with patch.object(pairs,'observe',side_effect=AssertionError('unknown cannot get fixed answers')):
            self.assertEqual(self.client.post('/f03/audit/analyze-and-compare',params=params).status_code,422)
        upload=dict(source_sha256=self.profile['reference_sha256'])
        for company,warehouse in [('OTHER',1),('ABC-002',2)]:
            with self.assertRaises(InventoryInputError):pairs.profile_for(upload,company,warehouse)
        with patch.object(Path,'read_bytes',return_value=b'changed reference'):
            with self.assertRaises(InventoryInputError):pairs.profile_for(upload,'ABC-002',1)

    def test_auth_and_result_modes_remain_isolated(self):
        params=self.upload((pairs.FIXTURE_DIR/self.profile['reference']).read_bytes())
        self.assertEqual(self.client.post('/f03/audit/analyze-and-compare',params=params).status_code,200)
        self.assertEqual(self.client.get('/f03/audit/review-results',params={**params,'mode':'live'}).status_code,404)
        api.app.dependency_overrides.clear()
        self.assertEqual(self.client.get('/f03/audit/review-results',params=params).status_code,401)

    def test_explicit_photo_warehouse_is_enforced_even_when_a_result_is_cached(self):
        params={**self.upload((pairs.FIXTURE_DIR/self.profile['reference']).read_bytes()),
                'warehouse_code':self.profile['warehouse']}
        response=self.client.post('/f03/audit/analyze-and-compare',params=params)
        self.assertEqual(response.status_code,200,response.text)
        self.assertEqual(response.json()['warehouse_code'],self.profile['warehouse'])
        with patch.object(pairs,'observe',side_effect=AssertionError('wrong warehouse must not run inference')):
            rejected=self.client.post('/f03/audit/analyze-and-compare',params={**params,'warehouse_code':'OTHER'})
        self.assertEqual(rejected.status_code,422,rejected.text)
        self.assertEqual(rejected.json()['detail']['code'],'REFERENCE_PAIR_UNAVAILABLE')
