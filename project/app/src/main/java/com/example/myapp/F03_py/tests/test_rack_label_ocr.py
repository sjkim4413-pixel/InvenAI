import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from f03_photo_observer import reread_weak_rack_labels


class RackLabelOCR(unittest.TestCase):
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
