import copy
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from f03_multiscale_detection import crop_predictions, fuse_predictions, detect_objects, retry_empty_regions, DETECTION_SCALES
from f03_photo_observer import observe_photo, read_region, retry_rack_detections


class Multiscale(unittest.TestCase):
    def test_enhanced_ocr_box_is_projected_back_to_original_photo(self):
        class Reader:
            def readtext(self, image, **options):
                self.shape=image.shape
                return [([[20,10],[60,10],[60,30],[20,30]],'SKU BT-200',.97)]

        reader=Reader()
        tokens=[]
        image=np.zeros((100,200,3),dtype=np.uint8)
        read_region(reader,image,[.25,.20,.75,.60],tokens,enhance=True)
        self.assertEqual(reader.shape,(80,200))
        self.assertEqual(tokens[0]['bbox'],[.30,.25,.40,.35])

    def test_repeated_observation_keeps_real_anchor_and_confidence(self):
        a={'bbox':[.1,.1,.4,.4],'confidence':.79,'scale':640}
        b={'bbox':[.11,.1,.41,.4],'confidence':.39,'scale':1024}
        c={'bbox':[.1,.11,.4,.41],'confidence':.28,'scale':1280}
        original=copy.deepcopy([a,b,c])
        fused=fuse_predictions([b,c,a])
        self.assertEqual(len(fused),1)
        self.assertEqual(fused[0]['bbox'],a['bbox'])
        self.assertEqual(fused[0]['confidence'],.79)
        self.assertEqual(fused[0]['support_scales'],list(DETECTION_SCALES))
        self.assertTrue(fused[0]['detection_stable'])
        self.assertEqual([a,b,c],original)

    def test_same_scale_objects_and_distinct_neighbours_never_collapse(self):
        a={'bbox':[.1,.1,.4,.4],'confidence':.9,'scale':640}
        b={**a,'confidence':.8}
        self.assertEqual(len(fuse_predictions([a,b])),2)
        c={'bbox':[.5,.1,.8,.4],'confidence':.85,'scale':1024}
        self.assertEqual(len(fuse_predictions([a,c])),2)

    def test_single_scale_high_confidence_is_still_unstable(self):
        result=fuse_predictions([{'bbox':[0,0,1,1],'confidence':.99,'scale':640}])
        self.assertFalse(result[0]['detection_stable'])

    def test_bad_model_coordinates_are_explicit_errors(self):
        class Model:
            def predict(self,**kwargs):
                return [SimpleNamespace(boxes=[SimpleNamespace(xyxy=np.array([[0,0,np.nan,50]]),conf=[.9])])]
        with self.assertRaises(ValueError): detect_objects(Model(),np.zeros((100,100,3),dtype=np.uint8))

    def test_region_retry_preserves_full_image_coordinates_and_uncertainty(self):
        class Model:
            def predict(self, **kwargs):
                self.shape=kwargs['source'].shape[:2]
                return [SimpleNamespace(boxes=[SimpleNamespace(xyxy=np.array([[10,10,30,30]]),conf=[.9])])]
        model=Model()
        detections=[]
        retry_empty_regions(model, np.zeros((100,100,3),dtype=np.uint8), detections,
                            [{'bbox':[.5,.5,1,1]}])
        self.assertEqual(model.shape,(50,50))
        self.assertEqual(detections[0]['bbox'],[.6,.6,.8,.8])
        self.assertEqual(detections[0]['source'],'mapped_region_retry')
        self.assertFalse(detections[0]['detection_stable'])

    def test_mirrored_band_recovers_review_candidate_in_original_coordinates(self):
        class Model:
            def predict(self, **kwargs):
                if kwargs['source'].shape[:2] == (66,100):
                    return [SimpleNamespace(boxes=[SimpleNamespace(xyxy=np.array([[70,55,90,65]]),conf=[.48])])]
                return [SimpleNamespace(boxes=[])]
        detections=[]
        retry_empty_regions(Model(), np.zeros((100,100,3),dtype=np.uint8), detections,
                            [{'bbox':[.1,.55,.3,.65]}])
        candidate,=detections
        np.testing.assert_allclose(candidate['bbox'],[.1,.55,.3,.65])
        self.assertEqual(candidate['source'],'mapped_mirror_band_retry')
        self.assertFalse(candidate['detection_stable'])

    def test_rack_retry_requires_repeated_detection_above_the_read_sticker(self):
        label={'id':'t1','text':'A01-5F','confidence':.98,'bbox':[.44,.56,.52,.58]}
        load=[.39,.42,.57,.553]
        neighbor=[.59,.42,.77,.553]
        detections=[{'id':'d1','bbox':load,'confidence':.46,'detection_stable':True}]
        image=np.zeros((200,200,3),dtype=np.uint8)

        def predictions(model,image,crop,padding,*,scale):
            return [{'bbox':box,'confidence':.65,'scale':scale} for box in (load,neighbor)]

        with (patch('f03_photo_observer.RACK_RETRY_TILE_RANGES',((0.,.66),)),
              patch('f03_photo_observer.crop_predictions',side_effect=predictions) as infer):
            retry_rack_detections(object(),image,detections,[label])
        self.assertEqual(infer.call_count,2)
        improved,=detections
        self.assertEqual(improved['id'],'d1')
        self.assertEqual(improved['bbox'],load)
        self.assertEqual(improved['support_scales'],list(DETECTION_SCALES[:2]))

        empty=[]
        def one_view(model,image,crop,padding,*,scale,reflected=False,nms_iou=.30):
            if reflected or scale!=DETECTION_SCALES[0]:
                return []
            return [{'bbox':load,'confidence':.91,'scale':scale}]

        with (patch('f03_photo_observer.RACK_RETRY_TILE_RANGES',((0.,.66),)),
              patch('f03_photo_observer.crop_predictions',side_effect=one_view)):
            retry_rack_detections(object(),image,empty,[label])
        self.assertEqual(empty,[])

        # A whole-image prediction can corroborate a different crop even
        # when the two inferences used the same input dimension.
        whole={'bbox':load,'confidence':.46,'scale':DETECTION_SCALES[0]}
        detections=[{'id':'d1','bbox':load,'confidence':.46,'detection_stable':False,
                     'scale_predictions':[whole]}]
        cropped={**whole,'confidence':.65}
        with (patch('f03_photo_observer.RACK_RETRY_TILE_RANGES',((0.,.66),)),
              patch('f03_photo_observer.crop_predictions',side_effect=[[cropped],[]])):
            retry_rack_detections(object(),image,detections,[label])
        improved,=detections
        self.assertTrue(improved['detection_stable'])
        self.assertEqual(improved['confidence'],.65)
        self.assertEqual([p['view'][0] for p in improved['scale_predictions']],['tile','whole'])

    def test_local_retry_crop_follows_the_observed_shelf_spacing(self):
        from f03_photo_observer import local_rack_view

        sticker={'bbox':[.43,.60,.47,.62]}
        neighbor={'bbox':[.53,.60,.57,.62]}
        above={'bbox':[.43,.45,.47,.47]}
        view=local_rack_view(sticker,[sticker,neighbor,above])
        np.testing.assert_allclose(view,[.335,.418,.565,.6445])

    def test_reflected_crop_projects_a_real_detection_to_the_original_side(self):
        image=np.zeros((100,100,3),dtype=np.uint8)
        image[:,20]=1
        with patch('f03_multiscale_detection.predict_boxes',return_value=[
            {'bbox':[.10,.20,.30,.40],'confidence':.76,'scale':640},
        ]) as predict:
            detection,=crop_predictions(object(),image,[.20,.10,.80,.70],0.,reflected=True)
        self.assertTrue(np.all(predict.call_args.args[1][:,-1]==1))
        np.testing.assert_allclose(detection['bbox'],[.62,.22,.74,.34])
        self.assertEqual(detection['confidence'],.76)

    def test_full_photo_is_read_even_when_a_different_area_is_detected(self):
        class Model:
            def predict(self,**kwargs):
                return [SimpleNamespace(boxes=[SimpleNamespace(xyxy=np.array([[0,0,30,30]]),conf=[.9])])]
        class Reader:
            def __init__(self):self.shapes=[]
            def readtext(self,crop,**kwargs):
                self.shapes.append(crop.shape[:2])
                return [([[70,70],[90,70],[90,90],[70,90]],'SKU123',.99)] if crop.shape[0]==100 else []
        reader=Reader()
        result=observe_photo(Path('fixture'),np.zeros((100,100,3),dtype=np.uint8),None,(Model(),reader),Path('m/best.pt'),
            catalog={'products':[{'sku':'SKU123'}]},location_map={'images':[]})
        self.assertEqual(reader.shapes[0],(100,100))
        self.assertEqual(result['results'][0]['recognized_product_ids'],['SKU123'])
        self.assertFalse(result['results'][0]['verification_passed'])

    def test_stronger_crop_reading_replaces_weaker_overview_of_same_label(self):
        class Reader:
            confidence=.6
            def readtext(self,*args,**kwargs):
                return [([[10,10],[30,10],[30,30],[10,30]],'COPY PAPER',self.confidence)]
        reader=Reader(); tokens=[]; image=np.zeros((100,100,3),dtype=np.uint8)
        read_region(reader,image,[0,0,1,1],tokens,overview=True)
        reader.confidence=.97
        read_region(reader,image,[0,0,1,1],tokens)
        self.assertEqual(len(tokens),1)
        self.assertEqual(tokens[0]['confidence'],.97)
        self.assertEqual(tokens[0]['id'],'t1')


if __name__=='__main__':unittest.main()
