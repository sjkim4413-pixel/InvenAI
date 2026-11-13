import csv
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from f03_verification import spatially_verified
from ivenai_erp_matcher import compare_with_erp, load_erp, ERPDataError
from f04_photo_review import review_observations


class Decisions(unittest.TestCase):
    def fixture(self,rows):
        tmp=tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path=Path(tmp.name)/'erp.csv'
        with path.open('w',encoding='utf-8-sig',newline='') as f:
            writer=csv.writer(f)
            writer.writerow(['랙번호','단수','ERP_왼쪽제품','ERP_오른쪽제품','ERP_등록수'])
            writer.writerows(rows)
        return path

    def test_geometry_rejects_spanning_and_center_only_boxes(self):
        label=[10,1914,210,1944]
        floor=[0,1904,4000,1972]
        for box in ([0,1700,400,2300],[100,1924,120,1934]):
            self.assertFalse(spatially_verified(label,box,floor,[box]))
        good=[0,1905,250,1960]
        self.assertTrue(spatially_verified(label,good,floor,[good]))
        self.assertFalse(spatially_verified(label,good,floor,[good,good]))

    def test_two_matching_ids_are_not_misplaced(self):
        result=compare_with_erp({'results':[{'랙번호':'A02','단수':2,'인식제품ID':'P006,P011','YOLO탐지후보수':2}]})
        self.assertEqual(result['results'][0]['review_status'],'제품ID일치_추가검증필요')

    def test_bad_erp_is_rejected(self):
        for rows in ([['A01',3,'P001','']], [['A01',3,'P001','',2]], [['A01',3,'P001','',1],['A01',3,'P999','',1]]):
            with self.assertRaises(ERPDataError): load_erp(self.fixture(rows))

    def test_pass_requires_confirmed_location_and_exact_inventory(self):
        item={'랙번호':'A01','단수':3,'인식제품ID':'P001','YOLO탐지후보수':1,'verification_passed':True,'location_verified':True}
        def status(): return compare_with_erp({'results':[item]})['results'][0]['review_status']
        self.assertEqual(status(),'PASS')
        item['location_verified']=False
        self.assertNotEqual(status(),'PASS')
        item['location_verified']=True
        item['YOLO탐지후보수']=2
        self.assertNotEqual(status(),'PASS')

    def test_current_erp_has_consistent_counts(self):
        self.assertEqual(len(load_erp()),25)

    def test_existing_f04_module_runs_after_pixel_and_location_checks(self):
        path=self.fixture([['R1',1,'SKU1','',1]])
        row={'랙번호':'R1','단수':1,'recognized_product_ids':['SKU1'],'YOLO탐지후보수':1,
             'verification_passed':True,'location_verified':True,'ocr_max_confidence':.99}
        result=review_observations({'results':[row]},path,'TEST')
        self.assertEqual(result['results'][0]['review_status'],'PASS')
        self.assertEqual(result['results'][0]['f04_status_code'],1)
        self.assertEqual(result['erp_backend'],'LOCAL_CSV')
        self.assertFalse(result['firebase_inventory_modified'])
        row['location_verified']=False
        result=review_observations({'results':[row]},path,'TEST')
        self.assertFalse(result['results'][0]['verification_passed'])
        self.assertEqual(result['results'][0]['f04_status_code'],5)

    def test_local_f04_classifies_only_verified_observations(self):
        path=self.fixture([['R1',1,'SKU1','',1], ['R1',2,'','',0], ['R1',3,'SKU1','',1]])
        rows=[
            {'랙번호':'R1','단수':1,'recognized_product_ids':['SKU2'],'YOLO탐지후보수':1},
            {'랙번호':'R1','단수':2,'recognized_product_ids':['SKU2'],'YOLO탐지후보수':1},
            {'랙번호':'R1','단수':3,'recognized_product_ids':[],'YOLO탐지후보수':0},
            {'랙번호':'R1','단수':4,'recognized_product_ids':['SKU2'],'YOLO탐지후보수':1},
        ]
        with patch('f04_photo_review.f04_comparer', return_value=None):
            for row in rows:
                row.update(location_verified=True, verification_passed=True)
            decisions=review_observations({'results':rows},path,'TEST')['results']
            self.assertEqual([row['f04_status_code'] for row in decisions], [2,4,5,5])
            self.assertFalse(any(row['verification_passed'] for row in decisions))
            rows[0]['verification_passed']=False
            self.assertEqual(review_observations({'results':rows},path,'TEST')['results'][0]['f04_status_code'],5)

    def test_invalid_external_f04_result_does_not_approve(self):
        row={'랙번호':'A01','단수':3,'recognized_product_ids':['P001'],'YOLO탐지후보수':1,
             'verification_passed':True,'location_verified':True}
        for invalid in ([], [{}], [{'status_code': True}]):
            with patch('f04_photo_review.f04_comparer', return_value=lambda *args: invalid):
                result=review_observations({'results':[row]},None,'TEST')['results'][0]
            self.assertFalse(result['verification_passed'])
            self.assertEqual(result['f04_status_code'],5)

if __name__=='__main__': unittest.main()
