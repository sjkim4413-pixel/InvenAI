import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from f03_live_ocr import rack_readings
from f03_photo_observer import recover_missing_beam_labels, reread_unresolved_live_labels, reread_weak_rack_labels


class RackLabelOCR(unittest.TestCase):
    def test_local_sku_repair_requires_agreement_in_two_image_preparations(self):
        image=np.zeros((120,200,3),dtype=np.uint8)
        token={'id':'t1','text':'SKU HPY 615','confidence':.59,'bbox':[.25,.4,.52,.47]}
        for second, expected in (('SKU HPY-615',True),('SKU HPY-6150',False)):
            tokens=[token]
            def read(reader,pixels,crop,output,*,binarize,**kwargs):
                output.append({**token,'text':second if binarize else 'SKU HPY-615','confidence':.92})
            with self.subTest(second=second):
                with patch('f03_photo_observer.read_region',side_effect=read):
                    reread_unresolved_live_labels(object(),image,tokens)
                self.assertEqual(len(tokens),2 if expected else 1)
                if expected:
                    self.assertTrue(tokens[-1]['confirmed_reread'])

    def test_clipped_beam_label_requires_agreeing_reads_on_one_load(self):
        image = np.zeros((100, 100, 3), dtype=np.uint8)
        box = [.33,.31,.49,.42]
        load = {'bbox':box,'confidence':.62,'detection_stable':True}
        token = {'id':'t1','bbox':[.36,.445,.45,.465],'text':'HLAO1-4F-01','confidence':.93}
        for alternative, anchors, expected in (
            ('HLA01-4F-01', [load], True),
            ('HLAO1-4F-01', [load], True),
            ('HLA01-4F-02', [load], False),
            ('HLA01-4F-01', [load,{**load,'bbox':[.32,.30,.50,.423]}], False),
        ):
            tokens = []
            def read(reader, pixels, strip, output, *, binarize, **kwargs):
                self.assertGreater(strip[3], token['bbox'][3])
                output.append({**token,'text':alternative if binarize else token['text']})
            with self.subTest(alternative=alternative, anchors=len(anchors)):
                with patch('f03_photo_observer.read_region', side_effect=read):
                    recover_missing_beam_labels(object(), image, anchors, tokens)
                self.assertEqual(bool(tokens),expected)

    def test_incomplete_rack_reread_can_use_one_alternate_box_of_same_sticker(self):
        first={'id':'t1','text':'QAAOZTF-01','confidence':.62,'bbox':[.58,.71,.69,.732]}
        alternate={'id':'t2','text':'QAAO2TF-01','confidence':.61,'bbox':[.59,.712,.69,.732]}
        adjacent={'id':'t3','text':'QAAO3TF-01','confidence':.60,'bbox':[.75,.712,.85,.732]}
        detection={'bbox':[.48,.46,.73,.70],'confidence':.75,'detection_stable':True}
        tokens=[first,alternate,adjacent]
        recovered={**alternate,'text':'QAA02-1F-01','confidence':.74}
        with patch('f03_photo_observer.reread_incomplete_rack_label',side_effect=[None,recovered]) as retry:
            reread_weak_rack_labels(object(),np.zeros((120,200,3),dtype=np.uint8),tokens,[detection])
        self.assertEqual([call.args[2]['id'] for call in retry.call_args_list],['t1','t2'])
        self.assertEqual(tokens[-1],{**recovered,'id':'t4'})

    def test_sticker_frame_and_underscores_do_not_change_rack_code(self):
        parts, normalized = next(rack_readings('(QAA01_4F_02|'))
        self.assertEqual((parts['rack'], parts['tier'], parts['side']), ('QAA01', 4, '02'))
        self.assertEqual(normalized, 'QAA01-4F-02')
        self.assertFalse(list(rack_readings('(SKU QAA01_4F_02|')))
        self.assertFalse(list(rack_readings('QAA01_4F__02')))

    def test_incomplete_code_requires_two_agreeing_pixel_reads_on_one_load(self):
        label = {'id': 't1', 'text': 'QAAO2TF-01', 'confidence': .61, 'bbox': [.5,.70,.62,.72]}
        detection = {'bbox': [.45,.5,.7,.69], 'confidence': .82, 'detection_stable': True}
        image = np.zeros((120, 200, 3), dtype=np.uint8)
        for second, supported, accepted in (
            ('QAAO2-IF-01', True, True),
            ('QAAO2-IF-02', True, False),
            ('QAAO2-IF-01', False, False),
        ):
            tokens = [label.copy()]
            def read(reader, image, crop, output, *, binarize, **options):
                output.append({**label, 'text': second if binarize else 'QAA02-1F-01',
                               'confidence': .79 if binarize else .75})

            with self.subTest(second=second, supported=supported):
                with patch('f03_photo_observer.read_region', side_effect=read):
                    reread_weak_rack_labels(object(), image, tokens, [detection] if supported else [])
                if accepted:
                    self.assertEqual(tokens[1], {**label, 'id': 't2', 'text': 'QAA02-1F-01', 'confidence': .75})
                else:
                    self.assertEqual(len(tokens), 1)

    def test_stable_load_reserves_a_reread_for_a_lower_ranked_label(self):
        tokens = [
            {'id': f't{i+1}', 'text': f'A{i+1:02}-1F', 'confidence': .65,
             'bbox': [.04+i*.10,.20,.09+i*.10,.22]}
            for i in range(6)
        ]
        target = {'id': 't7', 'text': 'A07-1F', 'confidence': .57, 'bbox': [.63,.70,.70,.72]}
        tokens.append(target)
        detection = {'bbox': [.55,.50,.80,.69], 'confidence': .49, 'detection_stable': True,
                     'scale_predictions': [{'confidence': .49}, {'confidence': .39}]}

        def recognize(reader, image, label, original_readings):
            return {**label, 'confidence': .83} if label['id']=='t7' else None

        with (patch('f03_photo_observer.read_region'),
              patch('f03_photo_observer.recognize_rack_label_box', side_effect=recognize)):
            reread_weak_rack_labels(object(), np.zeros((120, 200, 3), dtype=np.uint8), tokens, [detection])
        self.assertEqual(tokens[-1], {**target, 'id':'t8', 'confidence':.83})

    def test_fixed_box_reread_requires_strong_agreement_and_keeps_original_box(self):
        class Reader:
            def __init__(self, text, confidence):
                self.text = text
                self.confidence = confidence
                self.boxes = []

            def readtext(self, image, **options):
                return []

            def recognize(self, gray, *, horizontal_list, **options):
                self.boxes.extend(horizontal_list)
                return [([], self.text, self.confidence)]

        image = np.zeros((120, 200, 3), dtype=np.uint8)
        original = {'id': 't1', 'text': 'A04-4F-0I', 'confidence': .576, 'bbox': [.2, .4, .5, .5]}
        for text, confidence, accepted in (
            ('A04-4F-01', .834, True),
            ('A04-4F-02', .95, False),
            ('A04-4F-01', .69, False),
        ):
            with self.subTest(text=text, confidence=confidence):
                reader = Reader(text, confidence)
                tokens = [original.copy()]
                reread_weak_rack_labels(reader, image, tokens)
                self.assertEqual(tokens[0], original)
                self.assertEqual(len(reader.boxes), 1 if confidence>=.70 else 6)
                if accepted:
                    self.assertEqual(tokens[1], {**original, 'id': 't2', 'text': text, 'confidence': confidence})
                else:
                    self.assertEqual(len(tokens), 1)
