import sys
import time
import csv
import tempfile
import unittest
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import ivenai_integrated_ai as pipeline


class Lifecycle(unittest.TestCase):
    def test_review_fallback_updates_requested_csv(self):
        result={'results':[{'랙번호':'UNMAPPED','단수':None,'YOLO탐지후보수':2,
                            '인식제품ID':'','검토상태':'위치매핑_확인필요','verification_passed':False}]}
        with tempfile.TemporaryDirectory() as temporary:
            target=Path(temporary)/'review.csv'
            with patch.object(pipeline,'_analyze_image',return_value=result):
                pipeline.analyze_image('test.jpg',output_path=target)
            with target.open(encoding='utf-8-sig') as stream: rows=list(csv.DictReader(stream))
            self.assertEqual(rows[0]['랙번호'],'UNMAPPED')
            self.assertEqual(rows[0]['단수'],'')
            self.assertEqual(rows[0]['verification_passed'],'False')

    def test_concurrent_first_calls_construct_models_once(self):
        counts={'model':0,'reader':0}
        def construct(kind):
            counts[kind]+=1
            time.sleep(.03)
            return object()
        with patch.object(pipeline,'_model',None),patch.object(pipeline,'_reader',None),patch.object(pipeline,'YOLO',side_effect=lambda *a,**k:construct('model')),patch.object(pipeline.easyocr,'Reader',side_effect=lambda *a,**k:construct('reader')):
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(pipeline.get_ai_models) for _ in range(2)]
                for future in futures: future.result(timeout=3)
        self.assertEqual(counts,{'model':1,'reader':1})

if __name__=='__main__': unittest.main()
