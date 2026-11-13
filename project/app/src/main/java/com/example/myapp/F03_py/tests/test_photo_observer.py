import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
import numpy as np
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from f03_catalog import AuditConfigError, load_catalog, load_locations, match_catalog, resolve_regions
from f03_photo_observer import observation_row, observe_photo, read_image


class PhotoObserver(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.catalog={'version':1,'products':[{'sku':'SIM-001','aliases':[['LG','TROMM']]}]}
        self.tokens=[{'id':'t1','text':'LG','confidence':.99,'bbox':[.2,.2,.3,.3]},
                     {'id':'t2','text':'TROMM','confidence':.98,'bbox':[.2,.35,.4,.4]}]
        self.detection={'id':'d1','bbox':[.1,.1,.8,.8],'confidence':.8,'detection_stable':True}

    def config(self,name,data):
        path=self.root/name
        path.write_text(json.dumps(data),encoding='utf-8')
        return path

    def row(self,catalog=None,tokens=None,detections=None,location=True):
        detections=[self.detection] if detections is None else detections
        return observation_row('TEST',1,detections,self.tokens if tokens is None else tokens,
                               self.catalog if catalog is None else catalog,location,[0,0,1,1],
                               [d['bbox'] for d in detections],[])

    def test_alias_requires_all_words_and_sufficient_evidence(self):
        self.assertEqual([m['sku'] for m in match_catalog(self.tokens,self.catalog)],['SIM-001'])
        self.assertEqual(match_catalog(self.tokens[:1],self.catalog),[])
        self.tokens[0]['text']='ALGORITHM'
        self.assertEqual(match_catalog(self.tokens,self.catalog),[])
        self.tokens[0].update(text='LG',confidence=.3)
        self.assertEqual(match_catalog(self.tokens,self.catalog),[])

    def test_printed_code_and_compact_phrase_are_read_without_inventing_ids(self):
        catalog={'products':[{'sku':'ERP-123','aliases':[['COPY PAPER']]}]}
        for text in ('erp-123','ERP123','COPY PAPER','COPYPAPER'):
            token={**self.tokens[0],'text':text}
            self.assertEqual(match_catalog([token],catalog)[0]['sku'],'ERP-123')
        self.assertEqual(match_catalog([{**self.tokens[0],'text':'random 2027 words'}],catalog),[])

    def test_ambiguity_low_confidence_duplicate_objects_and_missing_location_never_verify(self):
        self.assertTrue(self.row()['verification_passed'])
        catalog=copy.deepcopy(self.catalog)
        catalog['products'].append({'sku':'OTHER','aliases':[['LG','TROMM']]})
        self.assertFalse(self.row(catalog=catalog)['verification_passed'])
        low=copy.deepcopy(self.tokens); low[1]['confidence']=.6
        self.assertFalse(self.row(tokens=low)['verification_passed'])
        self.assertFalse(self.row(detections=[self.detection,self.detection])['verification_passed'])
        self.assertFalse(self.row(detections=[])['verification_passed'])
        self.assertFalse(self.row(location=False)['verification_passed'])
        self.assertFalse(self.row(detections=[{**self.detection,'confidence':.49}])['verification_passed'])
        self.assertFalse(self.row(detections=[{**self.detection,'detection_stable':False}])['verification_passed'])

    def test_catalog_duplicates_and_overlapping_location_regions_are_rejected(self):
        bad=copy.deepcopy(self.catalog); bad['products']*=2
        with self.assertRaises(AuditConfigError): load_catalog(self.config('catalog.json',bad))
        mapping={'version':1,'company_code':'TEST','warehouse_id':1,'images':[
            {'sha256':['a'*64],'width':100,'height':100,'regions':[
                {'rack':'R1','floor':1,'bbox':[0,0,.7,1]},
                {'rack':'R2','floor':1,'bbox':[.6,0,1,1]}]}]}
        with self.assertRaises(AuditConfigError): load_locations(self.config('map.json',mapping))

    def test_location_binding_requires_photo_hash_company_warehouse_and_dimensions(self):
        path=self.root/'photo.jpg'; path.write_bytes(b'photo-one')
        regions=[{'rack':'R1','floor':1,'bbox':[0,0,1,1]}]
        mapping={'version':1,'company_code':'TEST','warehouse_id':7,'images':[
            {'sha256':[hashlib.sha256(path.read_bytes()).hexdigest()],'width':100,'height':200,'regions':regions}]}
        mapping=load_locations(self.config('map.json',mapping))
        self.assertEqual(resolve_regions(mapping,path,100,200,'TEST',7)[0],regions)
        for company,warehouse in [('OTHER',7),('TEST',8),(None,7),('TEST',None)]:
            self.assertEqual(resolve_regions(mapping,path,100,200,company,warehouse)[0],[])
        with self.assertRaises(AuditConfigError): resolve_regions(mapping,path,200,100,'TEST',7)
        path.write_bytes(b'photo-two')
        self.assertEqual(resolve_regions(mapping,path,100,200,'TEST',7)[0],[])

    def test_korean_path_and_exif_orientation_are_supported(self):
        path=self.root/'트레이더스 사진.jpg'
        exif=Image.Exif(); exif[274]=6
        Image.new('RGB',(160,100),'green').save(path,exif=exif)
        self.assertEqual(read_image(path).shape[:2],(160,100))

    def test_ocr_runs_even_when_detector_finds_nothing(self):
        class Reader:
            calls=0
            def readtext(reader,*args,**kwargs):
                reader.calls+=1
                return [([[10,10],[70,10],[70,30],[10,30]],'ERP123',.99)]
        class Model:
            def predict(model,*args,**kwargs): return [SimpleNamespace(boxes=[])]
        reader=Reader()
        path=self.root/'source.jpg'; path.write_bytes(b'fixture')
        result=observe_photo(path,np.zeros((100,100,3),dtype=np.uint8),None,(Model(),reader),Path('model/best.pt'),
                              catalog={'products':[{'sku':'ERP123'}]},location_map={'images':[]})
        self.assertEqual(reader.calls,1)
        self.assertEqual(result['ocr_status'],'completed')
        self.assertEqual(result['results'][0]['recognized_product_ids'],['ERP123'])
        self.assertFalse(result['results'][0]['verification_passed'])


if __name__=='__main__': unittest.main()
