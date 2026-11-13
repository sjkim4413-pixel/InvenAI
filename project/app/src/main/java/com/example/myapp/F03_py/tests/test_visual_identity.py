import sys
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from f03_fixture_inventory import DEFAULT_FIXTURE_DIR, review_fixture
from f03_api import comparison_detections
from f03_visual_identity import AppearanceClassifier, add_visual_observations, load_visual_assets
from ivenai_erp_matcher import compare_inventory


class VisualIdentity(unittest.TestCase):
    def test_reference_follows_shifted_pixels_and_rejects_blank_region(self):
        assets, _ = load_visual_assets(DEFAULT_FIXTURE_DIR / 'product_catalog.json')
        samples, photo = assets[1]
        selected = [s for s in samples if s['sku'] in {'SIM-AI02-P06', 'SIM-AI02-P11'}]
        classifier = AppearanceClassifier([(selected, photo)], {s['sku'] for s in selected})
        image = cv2.imdecode(np.frombuffer(photo, dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
        for sample in selected:
            x1, y1, x2, y2 = sample['bbox']
            patch = cv2.resize(image[y1:y2, x1:x2], None, fx=.85, fy=.85, interpolation=cv2.INTER_AREA)
            relocated = cv2.copyMakeBorder(patch, 24, 18, 42, 10, cv2.BORDER_CONSTANT, value=96)
            match, = classifier.identify(relocated)
            self.assertEqual(match['sku'], sample['sku'])
            self.assertAlmostEqual(match['bbox'][0], 42, delta=3)
            self.assertAlmostEqual(match['bbox'][1], 24, delta=3)
        self.assertEqual(classifier.identify(np.full((220, 260), 96, dtype=np.uint8)), [])

    def test_erp_location_cannot_supply_visual_identity(self):
        assets, _ = load_visual_assets(DEFAULT_FIXTURE_DIR / 'product_catalog.json')
        samples, photo = assets[1]
        reference = next(s for s in samples if s['sku'] == 'SIM-AI02-P06')
        source = DEFAULT_FIXTURE_DIR / '31940782-2973-4e71-9765-5ba1d3d0b20b.png'
        # Use one real package and deliberately give its region a different
        # registered location. The observed SKU comes from the package pixels.
        region = [.27, .28, .48, .40]
        locations = {'images':[{'regions':[{'rack':'AI02-P11','floor':3,'bbox':region}]}]}
        row = {'랙번호':'AI02-P11','단수':3,'location_verified':True,'verification_passed':False,
               'YOLO탐지후보수':1,'recognized_product_ids':[],'detection_ids':['d1'],'review_reasons':[]}
        raw = {'results':[row],'detections':[{'id':'d1','bbox':region,'location_verified':True,
                                             'detection_stable':True,'confidence':.9}]}
        add_visual_observations(source, raw, locations, {'products':[{'sku':reference['sku']}]}, [([reference], photo)])
        self.assertEqual(row['recognized_product_ids'], ['SIM-AI02-P06'])
        self.assertEqual(row['identity_method'], 'TEST_PACKAGING_APPEARANCE')
        stock = {'records':[{'zone':'AI02','rack':'P11','floor':3,'sku':'SIM-AI02-P11','status':'NORMAL',
                             'quantity':1,'side':None}],'issues':[]}
        compared, = review_fixture(raw, stock)['results']
        self.assertEqual(compared['f04_status'], 'MISPLACED')
        self.assertFalse(compared['quantity_verified'])

    def test_strong_color_and_geometry_support_test_location_without_yolo(self):
        assets, _ = load_visual_assets(DEFAULT_FIXTURE_DIR / 'product_catalog.json')
        samples, photo = assets[1]
        reference = next(s for s in samples if s['sku'] == 'SIM-AI02-P06')
        source = DEFAULT_FIXTURE_DIR / '31940782-2973-4e71-9765-5ba1d3d0b20b.png'
        region = [.27, .28, .48, .40]
        locations = {'images':[{'regions':[{'rack':'AI02-P06','floor':4,'bbox':region}]}]}
        row = {'랙번호':'AI02-P06','단수':4,'location_verified':True,'verification_passed':False,
               'YOLO탐지후보수':0,'recognized_product_ids':[],'detection_ids':[],'review_reasons':[]}
        raw = {'results':[row],'detections':[]}
        add_visual_observations(source, raw, locations, {'products':[{'sku':reference['sku']}]}, [([reference], photo)])
        self.assertTrue(row['appearance_location_verified'])
        local, = compare_inventory(raw, {('AI02-P06',4):[reference['sku']]})['results']
        self.assertFalse(local['verification_passed'])
        stock = {'records':[{'zone':'AI02','rack':'P06','floor':4,'sku':reference['sku'],'status':'NORMAL',
                             'quantity':1,'side':None}],'issues':[]}
        compared, = review_fixture(raw, stock)['results']
        self.assertTrue(compared['product_location_verified'])
        self.assertFalse(compared['quantity_verified'])
        mark, = comparison_detections(raw, [compared])
        self.assertEqual(compared['yolo_candidates'], 0)
        self.assertEqual(mark['source'], 'registered_appearance')
        self.assertIsNone(mark['confidence'])


if __name__ == '__main__':
    unittest.main()
