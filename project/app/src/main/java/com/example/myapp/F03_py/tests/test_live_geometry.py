import unittest

from f03_live_inventory import build_snapshot, warehouse_view
from f03_multiscale_detection import PERSPECTIVE_EXTENSION_SOURCE, PERSPECTIVE_RETRY_SOURCE
from f04_live_review import review_live


class LiveLoadGeometry(unittest.TestCase):
    def setUp(self):
        self.stock = build_snapshot('ABC-001', [
            ('expected', dict(companyCode='ABC-001', warehouse='Shelf', location='R12-3F-01',
                              itemCode='BT-200', quantity=1, status='NORMAL')),
            ('elsewhere', dict(companyCode='ABC-001', warehouse='Shelf', location='R14-3F-01',
                               itemCode='BT-201', quantity=1, status='NORMAL')),
        ])
        self.positions = warehouse_view(self.stock, 'Shelf')
        self.load = {'id': 'confirmed', 'bbox': [.600,.479,.695,.622], 'confidence': .70,
                     'detection_stable': True, 'scale_predictions': [{'confidence': .70}, {'confidence': .54}]}
        self.wider = {'id': 'weak', 'bbox': [.593,.390,.702,.646], 'confidence': .38,
                      'detection_stable': False, 'scale_predictions': [{'confidence': .38}]}
        self.rack = {'id': 'rack', 'text': 'R12-3F-01', 'bbox': [.610,.647,.679,.664], 'confidence': .99}
        self.sku = {'id': 'product', 'text': 'SKU BT-201', 'bbox': [.610,.504,.680,.521], 'confidence': .99}

    def decision(self, *, wider=None, additional_tokens=(), codes=()):
        raw = {'detections': [self.load, self.wider if wider is None else wider],
               'ocr_tokens': [self.rack, self.sku, *additional_tokens]}
        result = review_live(raw, codes, self.stock, self.positions, [], {})
        return next(row for row in result['results'] if row.get('location_label') == 'R12-3F-01')

    def test_same_rack_weak_containing_prediction_does_not_block_correct_misplacement(self):
        row = self.decision()
        self.assertEqual(row['f04_status'], 'MISPLACED')
        self.assertEqual(row['ai_product_ids'], ['BT-201'])
        self.assertEqual(row['fragment_detection_ids'], ['weak'])
        self.assertEqual(row['verified_detection_id'], 'confirmed')
        self.assertFalse(row['quantity_verified'])

    def test_keeps_containing_prediction_when_it_has_independent_evidence(self):
        other_evidence = (
            {'id': 'other-rack', 'text': 'R14-3F-01', 'bbox': [.610,.430,.679,.449], 'confidence': .98},
            {'id': 'other-sku', 'text': 'SKU BT-200', 'bbox': [.611,.420,.680,.438], 'confidence': .97},
        )
        for token in other_evidence:
            with self.subTest(token=token['id']):
                row = self.decision(additional_tokens=[token])
                self.assertEqual(row['f04_status'], 'MANUAL_REVIEW_REQUIRED')
                self.assertEqual(row['fragment_detection_ids'], [])
        code = {'id': 'other-code', 'value': 'additional-barcode', 'format': 'QR_CODE',
                'bbox': [.612,.422,.642,.448]}
        self.assertEqual(self.decision(codes=[code])['f04_status'], 'MANUAL_REVIEW_REQUIRED')

    def test_keeps_stable_or_wide_containing_prediction(self):
        for wider in ({**self.wider, 'detection_stable': True},
                      {**self.wider, 'bbox': [.530,.390,.730,.646]}):
            with self.subTest(wider=wider):
                self.assertEqual(self.decision(wider=wider)['f04_status'], 'MANUAL_REVIEW_REQUIRED')

    def test_containing_prediction_does_not_make_unknown_code_an_automatic_result(self):
        self.sku['text'] = 'SKU XX-300'
        row = self.decision()
        self.assertEqual(row['ai_product_ids'], ['XX-300'])
        self.assertEqual(row['f04_status'], 'MANUAL_REVIEW_REQUIRED')

    def test_perspective_retry_requires_original_geometry_and_independent_sku_read(self):
        sku = {**self.sku, 'confidence': .73, 'confirmed_reread': True}
        predictions = [
            {'confidence': .43, 'bbox': self.load['bbox'], 'view': (family, scale)}
            for family, scale in [('contrast', 1408), ('left-0.17', 1408), ('left-0.30', 960)]
        ]
        recovered = {**self.load, 'source': PERSPECTIVE_RETRY_SOURCE,
                     'confidence': .43, 'scale_predictions': predictions}

        def review(detection, product, *others):
            raw = {'detections': [detection, *others], 'ocr_tokens': [self.rack, product]}
            rows = review_live(raw, [], self.stock, self.positions, [], {})['results']
            return {row['f04_status'] for row in rows}

        self.assertEqual(review(recovered, sku), {'MISPLACED'})
        self.assertEqual(review(recovered, {**sku, 'confirmed_reread': False}), {'MANUAL_REVIEW_REQUIRED'})
        self.assertEqual(review({**recovered, 'confidence': .65, 'scale_predictions': predictions[1:]}, sku),
                         {'MANUAL_REVIEW_REQUIRED'})
        self.assertEqual(review({**recovered, 'scale_predictions': predictions[:2]}, sku),
                         {'MANUAL_REVIEW_REQUIRED'})
        self.assertEqual(review(recovered, sku, self.wider), {'MANUAL_REVIEW_REQUIRED'})

    def test_perspective_extension_requires_a_matching_original_part_and_different_transforms(self):
        predictions = [
            {'confidence': .61, 'bbox': self.load['bbox'], 'view': (family, scale)}
            for family, scale in [('left-0.37', 960), ('left-0.42', 1024), ('right-0.37', 1152)]
        ]
        sku = {**self.sku, 'confirmed_reread': True}
        native = {'bbox': [.603,.490,.693,.566], 'confidence': .33}
        recovered = {**self.load, 'source': PERSPECTIVE_EXTENSION_SOURCE, 'confidence': .61,
                     'scale_predictions': predictions, 'native_support': native}

        def review(detection, *other_tokens):
            raw = {'detections': [detection], 'ocr_tokens': [self.rack, sku, *other_tokens]}
            rows = review_live(raw, [], self.stock, self.positions, [], {})['results']
            return {row['f04_status'] for row in rows}

        self.assertEqual(review(recovered), {'MISPLACED'})
        self.assertEqual(review({**recovered, 'native_support': {}}), {'MANUAL_REVIEW_REQUIRED'})
        smaller = {**native, 'bbox': [.603,.490,.693,.538]}
        self.assertEqual(review({**recovered, 'native_support': smaller}), {'MANUAL_REVIEW_REQUIRED'})
        self.assertEqual(review({**recovered, 'native_support': {
            **smaller, 'scale_predictions': [smaller, native],
        }}), {'MISPLACED'})
        repeated = [{**prediction, 'view': ('left-0.37', prediction['view'][1])} for prediction in predictions]
        self.assertEqual(review({**recovered, 'scale_predictions': repeated}), {'MANUAL_REVIEW_REQUIRED'})
        other_rack = {**self.rack, 'id': 'neighbor', 'text': 'R14-2F-01', 'bbox': [.610,.568,.679,.584]}
        self.assertEqual(review(recovered, other_rack), {'MANUAL_REVIEW_REQUIRED'})
