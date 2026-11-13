"""Decode photographed QR/retail barcodes; never substitute ERP expectations."""
import json
import math
import cv2
import numpy as np


def valid_gtin(value):
    if not isinstance(value,str) or not value.isascii() or not value.isdigit() or len(value) not in (8,12,13,14):
        return False
    digits=[int(c) for c in value]
    weighted=sum(d*(3 if index%2==0 else 1) for index,d in enumerate(reversed(digits[:-1])))
    return (10-weighted%10)%10==digits[-1]


def normalized_box(points,width,height):
    points=np.asarray(points,dtype=float).reshape(-1,2)
    if len(points)<4 or not np.isfinite(points).all():return None
    x1,y1=points.min(axis=0);x2,y2=points.max(axis=0)
    box=[max(0.,x1/width),max(0.,y1/height),min(1.,x2/width),min(1.,y2/height)]
    return list(map(float,box)) if box[2]>box[0] and box[3]>box[1] else None


def decode_codes(image):
    h,w=image.shape[:2]
    observations=[]
    # Bounded native decoding. Original resolution retains the small labels;
    # a second view helps large QR labels without allocating larger images.
    for limit in (2400,1280):
        scale=min(1.,limit/max(w,h))
        frame=cv2.resize(image,None,fx=scale,fy=scale,interpolation=cv2.INTER_AREA) if scale<1 else image
        fh,fw=frame.shape[:2]
        qr=cv2.QRCodeDetector()
        ok,values,points,_=qr.detectAndDecodeMulti(frame)
        if ok and points is not None:
            for value,polygon in zip(values,points):
                box=normalized_box(polygon,fw,fh)
                if value and box:
                    observations.append({'format':'QR_CODE','value':value,'bbox':box,'checksum_valid':None})
        detector=cv2.barcode_BarcodeDetector()
        ok,values,formats,points=detector.detectAndDecodeWithType(frame)
        if ok and points is not None:
            for value,kind,polygon in zip(values,formats,points):
                box=normalized_box(polygon,fw,fh)
                if box and valid_gtin(value):
                    observations.append({'format':str(kind),'value':value,'bbox':box,'checksum_valid':True})
        if scale==1 and max(w,h)<=1280:break
    unique=[]
    for observation in observations:
        if any(o['value']==observation['value'] and max(abs(a-b) for a,b in zip(o['bbox'],observation['bbox']))<.025 for o in unique):continue
        observation['id']=f'code{len(unique)+1}'
        unique.append(observation)
    return unique


def structured_code(observation):
    if observation['format']!='QR_CODE':return None
    try:data=json.loads(observation['value'])
    except (ValueError,TypeError):return None
    if not isinstance(data,dict) or data.get('ivenai')!=1:return None
    if data.get('kind') not in ('product','location'):return None
    return data
