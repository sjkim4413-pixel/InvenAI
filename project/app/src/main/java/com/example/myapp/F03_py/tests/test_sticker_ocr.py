import unittest
from unittest.mock import patch
import cv2
import numpy as np

from f03_sticker_ocr import read_structured_plate,read_stickers,redact_sticker_text
from f03_live_ocr import rack_label_evidence,test_group_relation,test_group_anchor,test_group_box_supported
from f03_live_inventory import build_snapshot,warehouse_view
from f04_live_review import review_live


class StructuredStickerOCR(unittest.TestCase):
    def test_own_sign_inside_a_pallet_is_valid_but_another_tier_is_not(self):
        sign=dict(id='own',bbox=[.3,.57,.4,.6])
        box=[.1,.1,.6,.61]
        self.assertTrue(test_group_box_supported(box,sign,[sign]))
        another=dict(id='other',bbox=[.3,.3,.4,.33])
        self.assertFalse(test_group_box_supported(box,sign,[sign,another]))

    def test_uncertain_pallet_piece_can_be_a_part_without_merging_a_neighbor(self):
        anchor=dict(id='anchor',bbox=[.2,.2,.6,.6],confidence=.95,detection_stable=True)
        part=dict(id='pallet',bbox=[.4,.5,.605,.625],confidence=.60,detection_stable=False)
        sign=dict(bbox=[.4,.59,.5,.62])
        self.assertEqual(test_group_relation(part,anchor,sign,[sign]),'contained_part')
        other=dict(bbox=[.54,.59,.59,.62])
        self.assertEqual(test_group_relation(part,anchor,sign,[sign,other]),'outside_group')

    def test_detector_redaction_keeps_original_pixels_and_goods_unchanged(self):
        source=np.full((200,200,3),255,np.uint8)
        sign=dict(id='s',bbox=[.4,.4,.6,.6],confirmed_sticker_reread=True)
        redacted,count=redact_sticker_text(source,[sign])
        self.assertEqual(count,1)
        self.assertTrue(np.all(source==255))
        self.assertTrue(np.all(redacted[:76]==255))
        self.assertTrue(np.all(redacted[124:]==255))
        self.assertEqual(redacted[100,100].tolist(),[65,70,75])
    def test_group_parts_cannot_hide_a_different_sticker_or_disjoint_load(self):
        parent=dict(id='whole',bbox=[.1,.1,.6,.6],confidence=.8,detection_stable=True)
        child=dict(id='part',bbox=[.15,.3,.55,.59],confidence=.98,detection_stable=True)
        sticker=dict(bbox=[.3,.61,.4,.64])
        self.assertEqual(test_group_relation(child,parent,sticker,[sticker]),'contained_part')
        self.assertEqual(test_group_anchor([parent,child],sticker,[sticker]),([parent],['part']))
        other=dict(bbox=[.2,.48,.27,.51])
        self.assertEqual(test_group_relation(child,parent,sticker,[sticker,other]),'outside_group')
        left={**child,'id':'left','bbox':[.1,.1,.38,.6]}
        right={**child,'id':'right','bbox':[.35,.15,.6,.6]}
        self.assertEqual(test_group_anchor([left,right],sticker,[sticker]),([left,right],[]))
        crossing={**parent,'id':'crossing','bbox':[.1,.1,.6,.8]}
        self.assertEqual(test_group_relation(crossing,parent,sticker,[sticker]),'crosses_shelf_sign')

    def plate(self):
        pixels=np.full((64,440),255,dtype=np.uint8)
        cv2.putText(pixels,'AB12-3F-04',(12,45),cv2.FONT_HERSHEY_SIMPLEX,1.4,0,2,cv2.LINE_AA)
        return pixels

    def reader(self,*,conflict=False,weak=False):
        class Reader:
            def __init__(self): self.calls=0
            def recognize(inner,pixels,*,allowlist,**kwargs):
                field=inner.calls//3;view=inner.calls%3;inner.calls+=1
                text=['AB','12','3','F','04'][field]
                if conflict and field==1 and view==1:text='13'
                score=.4 if weak and field==1 and view>0 else .98
                return [([],text,score)]
        return Reader()

    def test_real_glyph_fields_are_read_without_expected_codes(self):
        result=read_structured_plate(self.reader(),self.plate())
        self.assertEqual(result['text'],'AB12-3F-04')
        self.assertEqual(result['confidence'],.98)
        self.assertEqual(len(result['sticker_field_readings']),5)

    def test_conflicting_or_single_strong_read_cannot_confirm(self):
        self.assertIsNone(read_structured_plate(self.reader(conflict=True),self.plate()))
        self.assertIsNone(read_structured_plate(self.reader(weak=True),self.plate()))
        self.assertIsNone(read_structured_plate(self.reader(),np.full((40,100),255,np.uint8)))

    def test_consensus_replaces_only_same_physical_sticker_overview(self):
        coarse=dict(id='old',text='A03-1F',bbox=[.2,.2,.3,.23],confidence=.99)
        reread=dict(id='new',text='A02-1F',bbox=[.199,.199,.301,.231],confidence=.95,confirmed_sticker_reread=True)
        distant={**coarse,'id':'other','bbox':[.6,.2,.7,.23]}
        self.assertEqual([row['id'] for row in rack_label_evidence([coarse,reread,distant])],['new','other'])
        tight={**coarse,'bbox':[.21,.207,.29,.223]}
        self.assertEqual([row['id'] for row in rack_label_evidence([tight,reread,distant])],['new','other'])

    def test_missing_positions_are_counted_and_raw_candidates_remain_diagnostics(self):
        stock=build_snapshot('ABC-002',[(str(i),dict(companyCode='ABC-002',warehouse='Test',location=f'A0{i}-1F',
            itemCode=f'TEST-{i}',quantity=1,status='NORMAL')) for i in (1,2)])
        positions=warehouse_view(stock,'Test')
        raw=dict(detections=[dict(id='unmapped',bbox=[.1,.1,.8,.8],confidence=.9,detection_stable=True)],ocr_tokens=[])
        result=review_live(raw,[],stock,positions,[],{},test_item_codes={p['position_id']:p['source_location'] for p in positions})
        self.assertEqual(result['total_positions'],2)
        self.assertEqual(len(result['unmapped_observations']),1)
        self.assertTrue(all(row['f04_status_code']==5 and not row['test_label_match'] for row in result['results']))
        live=review_live(raw,[],stock,positions,[],{})
        self.assertNotIn('unmapped_observations',live)
