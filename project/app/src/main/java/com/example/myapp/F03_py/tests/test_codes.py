import json
import sys
import unittest
from pathlib import Path
import cv2
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from f03_codes import valid_gtin,decode_codes,structured_code


class Codes(unittest.TestCase):
    def test_gtin_check_digit_is_validated_without_dropping_leading_zeroes(self):
        self.assertTrue(valid_gtin('4006381333931'))
        self.assertTrue(valid_gtin('012345678905'))
        self.assertFalse(valid_gtin('4006381333932'))
        self.assertFalse(valid_gtin('４００６３８１３３３９３１'))
        self.assertFalse(valid_gtin('BT-200'))

    def test_actual_native_qr_decoder_reads_structured_fixture_pixels(self):
        text=json.dumps({'ivenai':1,'kind':'product','companyCode':'ABC-001','itemCode':'BT-200'},separators=(',',':'))
        qr=cv2.QRCodeEncoder_create().encode(text)
        qr=cv2.copyMakeBorder(qr,4,4,4,4,cv2.BORDER_CONSTANT,value=255)
        qr=cv2.resize(qr,None,fx=9,fy=9,interpolation=cv2.INTER_NEAREST)
        image=cv2.cvtColor(qr,cv2.COLOR_GRAY2BGR)
        result=decode_codes(image)
        self.assertEqual(result[0]['value'],text)
        self.assertEqual(structured_code(result[0])['itemCode'],'BT-200')
        self.assertIsNone(result[0]['checksum_valid'])

    def test_blank_frame_has_no_invented_codes(self):
        self.assertEqual(decode_codes(np.full((300,300,3),255,dtype=np.uint8)),[])


if __name__=='__main__':unittest.main()
