import hashlib
from itertools import islice
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import numpy as np
import f03_shelf_detection as shelf


class ShelfRecovery(unittest.TestCase):
    def setUp(self):
        self.image = np.zeros((200,200,3),dtype=np.uint8)
        self.rack = {'id':'rack','text':'R12-3F-01','bbox':[.4,.7,.5,.72],'confidence':.99}
        self.sku = {'id':'sku','text':'SKU BT-201','bbox':[.39,.56,.48,.59],
                    'confidence':.99,'confirmed_reread':True}
        self.box = [.3,.5,.6,.69]

    def observations(self, box=None, confidence=.45):
        return [{'bbox':self.box if box is None else box,'confidence':confidence,'scale':scale,
                 'view':('test-context',scale)} for scale in (640,960)]

    def recover(self, predictions, *, tokens=None, detections=None):
        found = [] if detections is None else detections
        with patch.object(shelf,'shelf_predictions',side_effect=predictions):
            shelf.recover_shelf_detections(self.image,found,tokens or [self.rack,self.sku],model_loader=lambda:object())
        return found

    def test_preserves_actual_confidence_and_box_for_corroborated_candidate(self):
        predictions = self.observations()
        predictions[1]['confidence'] = .38
        result = self.recover(lambda *args: iter(predictions))
        self.assertEqual(len(result),1)
        self.assertEqual(result[0]['bbox'],self.box)
        self.assertEqual(result[0]['confidence'],.45)
        self.assertEqual(result[0]['source'],shelf.SHELF_SOURCE)
        self.assertFalse(result[0]['verification_passed'])

    def test_cannot_turn_duplicate_predictions_from_one_view_into_consensus(self):
        first = self.observations()[0]
        self.assertEqual(self.recover(lambda *args: iter([first,dict(first)])),[])

    def test_leaves_two_distinct_load_candidates_for_manual_review(self):
        predictions = self.observations()+self.observations([.3,.3,.6,.69])
        self.assertEqual(self.recover(lambda *args: iter(predictions)),[])

    def test_recovers_neighbour_by_its_own_sticker_instead_of_crop_centre(self):
        rack = {**self.rack,'id':'rack-neighbour','text':'R13-3F-01','bbox':[.68,.7,.78,.72]}
        sku = {**self.sku,'id':'sku-neighbour','text':'SKU BT-202','bbox':[.67,.56,.76,.59]}
        box = [.61,.5,.85,.69]
        def predictions(model,image,context):
            return iter(self.observations(box) if context['id']==self.rack['id'] else [])
        result = self.recover(predictions,tokens=[self.rack,self.sku,rack,sku])
        self.assertEqual(len(result),1)
        self.assertEqual(result[0]['bbox'],box)

    def test_requires_readable_independently_reread_product_code(self):
        for change in ({'confidence':.84},{'confirmed_reread':False},{'text':'BT-201'}):
            loader = Mock()
            with self.subTest(change=change):
                shelf.recover_shelf_detections(self.image,[],[self.rack,{**self.sku,**change}],model_loader=loader)
                loader.assert_not_called()

    def test_rejects_two_product_codes_in_one_candidate_area(self):
        other = {**self.sku,'id':'other-sku','text':'SKU BT-202','bbox':[.40,.61,.49,.64]}
        loader = Mock()
        shelf.recover_shelf_detections(self.image,[],[self.rack,self.sku,other],model_loader=loader)
        loader.assert_not_called()

    def test_does_not_reanalyse_existing_weak_but_verified_two_label_detection(self):
        existing = {'id':'existing','bbox':self.box,'confidence':.45,'detection_stable':True,
                    'scale_predictions':self.observations()}
        loader = Mock()
        shelf.recover_shelf_detections(self.image,[existing],[self.rack,self.sku],model_loader=loader)
        loader.assert_not_called()

    def test_missing_auxiliary_model_keeps_original_detections(self):
        found = []
        shelf.recover_shelf_detections(self.image,found,[self.rack,self.sku],model_loader=lambda:None)
        self.assertEqual(found,[])

    def test_model_digest_changes_when_weights_change_and_missing_model_is_optional(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'weights.pt'
            with patch.object(shelf,'SHELF_MODEL_PATH',path):
                self.assertIsNone(shelf.shelf_model_digest())
                path.write_bytes(b'first model')
                first = shelf.shelf_model_digest()
                self.assertEqual(first,hashlib.sha256(b'first model').hexdigest())
                path.write_bytes(b'new and different model')
                self.assertNotEqual(first,shelf.shelf_model_digest())

    def row_labels(self):
        return [{**self.rack,'id':f'row-{index}','text':f'R12-3F-0{index+1}',
                 'bbox':[x,y,x+.05,y+.02]}
                for index,(x,y) in enumerate(((.2,.5),(.5,.56),(.8,.62)))]

    def test_row_tilt_uses_three_consistent_labels_on_the_same_tier(self):
        row = self.row_labels()
        other_tier = {**row[1],'id':'different-tier','text':'R12-4F-01','bbox':[.5,.2,.55,.22]}
        self.assertAlmostEqual(shelf.shelf_row_slope(row[0],[*row,other_tier]),.2)

    def test_row_tilt_rejects_two_points_duplicates_and_inconsistent_beams(self):
        row = self.row_labels()
        for observations in (row[:2],[row[0],dict(row[0]),row[2]],
                             [row[0],{**row[1],'bbox':[.5,.8,.55,.82]},row[2]],
                             [{**label,'bbox':[label['bbox'][0],.5,label['bbox'][2],.52]} for label in row]):
            with self.subTest(observations=observations):
                self.assertIsNone(shelf.shelf_row_slope(row[0],observations))

    def test_affine_projection_preserves_original_coordinates_and_detector_confidence(self):
        prediction = {'bbox':[.25,.4,.75,.6],'confidence':.77,'scale':640}
        with patch.object(shelf,'predict_boxes',return_value=[prediction]):
            projected = next(shelf.deskewed_shelf_predictions(object(),self.image,self.rack,.2))
        np.testing.assert_allclose(projected['bbox'],[.29,.463,.61,.637],atol=1e-6)
        self.assertEqual(projected['confidence'],.77)
        self.assertEqual(projected['view'][2],'deskew')

    def test_reflected_deskew_view_projects_back_to_the_same_original_load(self):
        regular = {'bbox':[.2,.4,.5,.6],'confidence':.77,'scale':640}
        mirrored = {**regular,'bbox':[.5,.4,.8,.6]}
        with patch.object(shelf,'predict_boxes',side_effect=[[regular],[regular],[mirrored],[mirrored]]):
            projected = list(islice(shelf.deskewed_shelf_predictions(object(),self.image,self.rack,.2),4))
        self.assertEqual(len(projected),4)
        for prediction in projected:
            np.testing.assert_allclose(prediction['bbox'],[.258,.4566,.45,.605],atol=1e-6)

    def test_affine_padding_and_cut_off_objects_are_not_detector_evidence(self):
        prediction = {'bbox':[.3,0.,.7,.05],'confidence':.99,'scale':640}
        with patch.object(shelf,'predict_boxes',return_value=[prediction]):
            found = list(shelf.deskewed_shelf_predictions(object(),self.image,self.rack,.2))
        self.assertEqual(found,[])

    def recover_deskew(self, predictions):
        found = []
        with (patch.object(shelf,'shelf_row_slope',return_value=.2),
              patch.object(shelf,'deskewed_shelf_predictions',return_value=iter(predictions))):
            shelf.recover_deskewed_shelves(object(),self.image,found,[self.rack,self.sku])
        return found

    def normalized_observations(self,box=None,modes=('deskew','gray')):
        return [{'bbox':self.box if box is None else box,'confidence':.48,'scale':640,
                 'view':('tier-deskew','rack',mode,1.5,False,640)} for mode in modes]

    def test_deskew_requires_a_separate_visual_normalization_to_agree(self):
        recovered = self.recover_deskew(self.normalized_observations())
        self.assertEqual(len(recovered),1)
        self.assertEqual(recovered[0]['source'],shelf.DESKEW_SOURCE)
        self.assertEqual(recovered[0]['confidence'],.48)
        only_deskew = self.normalized_observations(modes=('deskew','deskew'))
        only_deskew[1]['view'] = ('tier-deskew','rack','deskew',1.5,False,960)
        self.assertEqual(self.recover_deskew(only_deskew),[])
        self.assertEqual(self.recover_deskew(self.normalized_observations(modes=('gray','plain'))),[])

    def test_deskew_keeps_ambiguous_load_boundaries_for_manual_review(self):
        alternatives = self.normalized_observations()+self.normalized_observations([.3,.3,.6,.69])
        self.assertEqual(self.recover_deskew(alternatives),[])

    def test_deskew_does_not_run_without_a_measurable_shelf_tilt(self):
        with patch.object(shelf,'deskewed_shelf_predictions') as infer:
            shelf.recover_deskewed_shelves(object(),self.image,[],[self.rack,self.sku])
        infer.assert_not_called()

    def parent_and_processed_fragment(self):
        parent = self.normalized_observations(modes=('deskew','plain'))
        parent[0]['confidence'] = .62
        parent[1].update(confidence=.40,view=('tier-deskew','rack','plain',1.,False,640))
        nested = self.normalized_observations(box=[.38,.53,.51,.68])
        return parent,nested

    def test_original_colour_read_can_resolve_only_nested_processed_fragments(self):
        parent,nested = self.parent_and_processed_fragment()
        recovered = self.recover_deskew(parent+nested)
        self.assertEqual(len(recovered),1)
        self.assertEqual(recovered[0]['bbox'],self.box)
        self.assertEqual(recovered[0]['confidence'],.62)
        self.assertEqual(recovered[0]['extent_resolution'],'original_colour_parent')
        self.assertEqual(len(recovered[0]['nested_processed_candidates']),1)
        self.assertEqual(recovered[0]['nested_processed_candidates'][0]['bbox'],nested[0]['bbox'])

    def test_competing_original_colour_extents_remain_ambiguous(self):
        parent,nested = self.parent_and_processed_fragment()
        nested[1]['view'] = ('tier-deskew','rack','plain',1.,False,960)
        self.assertEqual(self.recover_deskew(parent+nested),[])

    def test_a_noncontained_alternative_is_never_discarded_for_the_original_view(self):
        parent,_ = self.parent_and_processed_fragment()
        alternative = self.normalized_observations(box=[.2,.5,.5,.69])
        self.assertEqual(self.recover_deskew(parent+alternative),[])

    def test_stretched_or_reflected_colour_views_do_not_count_as_original_confirmation(self):
        for changed_view in (('tier-deskew','rack','plain',2.,False,640),
                             ('tier-deskew','rack','plain',1.,True,640)):
            with self.subTest(view=changed_view):
                parent,nested = self.parent_and_processed_fragment()
                parent[1]['view'] = changed_view
                self.assertEqual(self.recover_deskew(parent+nested),[])

    def test_original_view_parent_needs_standard_high_confidence(self):
        parent,nested = self.parent_and_processed_fragment()
        for prediction in parent: prediction['confidence'] = .49
        for prediction in nested: prediction['confidence'] = .30
        self.assertEqual(self.recover_deskew(parent+nested),[])

    def test_a_stronger_processed_alternative_keeps_the_extent_ambiguous(self):
        parent,nested = self.parent_and_processed_fragment()
        for prediction in nested: prediction['confidence'] = .75
        self.assertEqual(self.recover_deskew(parent+nested),[])


if __name__=='__main__':
    unittest.main()
