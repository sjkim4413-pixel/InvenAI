"""Detect physical white code plates and reread their pixels without ERP hints."""
import cv2
import numpy as np
from f03_live_ocr import rack_readings,same_label

ALPHABET='ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-'


def redact_sticker_text(image,tokens):
    """Match detector training preprocessing; retain the original OCR image."""
    result=image.copy();height,width=result.shape[:2]
    signs=[]
    for token in tokens:
        if not token.get('confirmed_sticker_reread') or any(same_label(token,prior) for prior in signs): continue
        signs.append(token)
        a,b,c,d=token['bbox']
        left,top=max(0,round(a*width)-4),max(0,round(b*height)-4)
        right,bottom=min(width,round(c*width)+4),min(height,round(d*height)+4)
        result[top:bottom,left:right]=(65,70,75)
    return result,len(signs)


def codes(text):
    return {(p['zone'],p['rack'],p['tier'],p['side']) for p,_ in rack_readings(text)}


def plate_candidates(image):
    gray=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY)
    height,width=gray.shape
    count,_,stats,_=cv2.connectedComponentsWithStats(cv2.inRange(gray,215,255))
    candidates=[]
    for x,y,w,h,area in stats[1:count]:
        if not (.02*width<=w<=.25*width and .005*height<=h<=.045*height and 1.7<=w/h<=13 and area>=.35*w*h):
            continue
        if x<2 or y<2 or x+w>=width-2 or y+h>=height-2: continue
        sides=[gray[y-1,x+2:x+w-2],gray[y+h,x+2:x+w-2],gray[y+2:y+h-2,x-1],gray[y+2:y+h-2,x+w]]
        if sum(float(np.mean(side<170))>=.45 for side in sides)<3: continue
        patch=gray[y:y+h,x:x+w]
        dark=float(np.mean(patch<130))
        if not .04<=dark<=.48: continue
        components,_,ink,_=cv2.connectedComponentsWithStats(cv2.inRange(patch,0,130))
        letters=[(a,b,c,d) for a,b,c,d,size in ink[1:components]
                 if .22*h<=d<=.93*h and size>=3 and a>0 and b>0 and a+c<w and b+d<h]
        if len(letters)<4: continue
        left=min(a for a,b,c,d in letters); top=min(b for a,b,c,d in letters)
        right=max(a+c for a,b,c,d in letters); bottom=max(b+d for a,b,c,d in letters)
        text=patch[max(0,top-2):min(h,bottom+2),max(0,left-3):min(w,right+3)]
        # The crop comes from observed ink, including intervening dashes; no
        # text is synthesized and no expected rack number enters recognition.
        prepared=cv2.copyMakeBorder(text,5,5,5,5,cv2.BORDER_CONSTANT,value=255)
        bounds=[x/width,y/height,(x+w)/width,(y+h)/height]
        candidates.append((area/(w*h),bounds,prepared))
    return sorted(candidates,reverse=True,key=lambda row:row[0])[:160]


def read_stickers(reader,image,tokens):
    for _,bounds,patch in plate_candidates(image):
        if any(t.get('confirmed_sticker_reread') and same_label(t,{'bbox':bounds}) for t in tokens): continue
        zoom=max(1.,44/patch.shape[0])
        patch=cv2.resize(patch,None,fx=zoom,fy=zoom,interpolation=cv2.INTER_CUBIC)
        _,binary=cv2.threshold(patch,0,255,cv2.THRESH_BINARY|cv2.THRESH_OTSU)
        views=(patch,binary)
        readings=[]
        for view in views:
            result=reader.recognize(view,detail=1,paragraph=False,allowlist=ALPHABET,decoder='beamsearch',beamWidth=5)
            for _,text,confidence in result:
                if float(confidence)>=.75 and codes(text): readings.append((text,float(confidence),codes(text)))
        if len(readings)!=2 or not readings[0][2]&readings[1][2]:
            structured=read_structured_plate(reader,patch)
            if structured:
                tokens.append(dict(id=f't{len(tokens)+1}',bbox=bounds,**structured))
            continue
        tokens.append(dict(id=f't{len(tokens)+1}',text=readings[0][0],confidence=min(r[1] for r in readings),bbox=bounds,
            confirmed_sticker_reread=True,sticker_readings=[dict(text=t,confidence=c) for t,c,_ in readings],
            ocr_preparations=['grayscale_ink_crop','otsu_ink_crop'],ocr_alphabet=ALPHABET))


def read_structured_plate(reader,patch,trace=None):
    """Read a test sign's fields separately using its photographed dash/glyphs.

    The fixture format has a letter prefix, two rack digits, tier digits + F,
    and an optional two-digit side. No expected number or ERP list is supplied.
    """
    height,width=patch.shape
    count,_,stats,_=cv2.connectedComponentsWithStats(cv2.inRange(patch,0,130))
    glyphs=[(int(x),int(y),int(w),int(h)) for x,y,w,h,area in stats[1:count]
            if h>=.42*height and area>=5]
    dashes=sorted((int(x),int(y),int(w),int(h)) for x,y,w,h,area in stats[1:count]
                  if w>=1.7*h and .025*height<=h<=.22*height and .28*height<=y<=.80*height)
    if trace is not None: trace.update(glyphs=glyphs,dashes=dashes,size=[width,height])
    if not 1<=len(dashes)<=2: return None
    first=dashes[0][0]; second=dashes[1][0] if len(dashes)==2 else width
    rack=sorted(g for g in glyphs if g[0]+g[2]<=first)
    tier=sorted(g for g in glyphs if g[0]>first and g[0]+g[2]<=second)
    side=sorted(g for g in glyphs if g[0]>second) if len(dashes)==2 else []
    if len(rack)<3 or len(tier)<2 or (side and len(side)!=2): return None
    fields=[('prefix',rack[:-2],'ABCDEFGHIJKLMNOPQRSTUVWXYZ'),('rack_digits',rack[-2:],'0123456789'),
            ('tier_digits',tier[:-1],'0123456789'),('tier_suffix',tier[-1:],'ABCDEFGHIJKLMNOPQRSTUVWXYZ')]
    if side: fields.append(('side',side,'0123456789'))
    observations=[]; confidence=[]
    for name,letters,alphabet in fields:
        left=min(g[0] for g in letters); top=min(g[1] for g in letters)
        right=max(g[0]+g[2] for g in letters); bottom=max(g[1]+g[3] for g in letters)
        crop=patch[max(0,top-2):min(height,bottom+2),max(0,left-2):min(width,right+2)]
        crop=cv2.copyMakeBorder(crop,6,6,6,6,cv2.BORDER_CONSTANT,value=255)
        _,binary=cv2.threshold(crop,0,255,cv2.THRESH_BINARY|cv2.THRESH_OTSU)
        reads=[]
        enlarged=cv2.resize(crop,None,fx=1.4,fy=1.4,interpolation=cv2.INTER_CUBIC)
        enhanced=cv2.createCLAHE(clipLimit=2.,tileGridSize=(2,2)).apply(enlarged)
        for view in (crop,binary,enhanced):
            result=reader.recognize(view,detail=1,paragraph=False,allowlist=alphabet,decoder='beamsearch',beamWidth=5)
            if len(result)!=1: continue
            _,text,score=result[0]
            text=str(text).strip()
            if trace is not None: trace.setdefault('fields',[]).append(dict(field=name,text=text,confidence=float(score)))
            if float(score)<.75 or not text or any(c not in alphabet for c in text): continue
            reads.append(dict(text=text,confidence=float(score)))
        if len(reads)<2 or len({r['text'] for r in reads})!=1: return None
        observations.append(dict(field=name,reads=reads))
        confidence.extend(r['confidence'] for r in reads)
    values={o['field']:o['reads'][0]['text'] for o in observations}
    if len(values['rack_digits'])!=2 or values['tier_suffix']!='F' or (side and len(values['side'])!=2): return None
    text=values['prefix']+values['rack_digits']+'-'+values['tier_digits']+'F'
    if side: text+='-'+values['side']
    if not codes(text): return None
    return dict(text=text,confidence=min(confidence),confirmed_sticker_reread=True,
                sticker_field_readings=observations,ocr_preparations=['grayscale_glyph_fields','otsu_glyph_fields','clahe_enlarged_glyph_fields'])
