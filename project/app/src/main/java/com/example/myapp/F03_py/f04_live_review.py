"""Actual product/location review with explicit provenance and unknown quantity."""
from f03_codes import structured_code
from f03_live_ocr import is_load_fragment as is_fragment
from f03_live_ocr import OCR_THRESHOLD, pallet_ocr_box, product_evidence, product_label_area, rack_label_evidence, read_location_regions, same_label, under_load, visible_location
from f03_verification import covered_fraction
from f03_live_ocr import test_group_relation


def identify_codes(codes,snapshot,registry):
    products={p['sku'] for p in snapshot['products']}
    identified=[];unregistered=[]
    for code in codes:
        sku=None;method=None;data=structured_code(code)
        if data and data.get('kind')=='product' and data.get('companyCode')==snapshot['company_code']:
            if data.get('itemCode') in products:sku=data['itemCode'];method='registered_product_qr'
        elif code['value'] in registry:
            candidate=registry[code['value']]['sku']
            if candidate in products and (code['format']=='QR_CODE' or code.get('checksum_valid') is True):
                sku=candidate;method='operator_registered_barcode'
        if sku:
            identified.append({**code,'sku':sku,'identity_method':method})
        elif not (data and data.get('kind')=='location'):
            unregistered.append(code)
    return identified,unregistered


def is_printed_sticker_duplicate(detection,anchor,rack_labels,sticker):
    if detection['id']==anchor['id'] or anchor['confidence']<.75:
        return False
    part,whole=detection['bbox'],anchor['bbox']
    if any(not same_label(label,sticker) and under_load(label['bbox'],part,printed_sticker=True)
           for label in rack_labels):
        return False
    inside=covered_fraction(part,whole)
    share=covered_fraction(whole,part)
    nested_or_wide=((inside>=.90 and share<=.66 and detection['confidence']+.15<=anchor['confidence'])
            or (detection['confidence']<.30 and not detection['detection_stable']
                and share>=.85 and inside>=.45))
    if nested_or_wide:
        return True
    # A weak crop can cover the bottom of an independently corroborated load
    # and run down to its beam sticker. Keep boxes that continue past the beam.
    horizontal=min(part[2],whole[2])-max(part[0],whole[0])
    return (detection['confidence']<.35 and .60<=inside and share<=.55
            and horizontal>=.95*(part[2]-part[0])
            and whole[1]<=part[1]<whole[3]<part[3]<=sticker['bbox'][3]
            and under_load(sticker['bbox'],part,printed_sticker=True)
            and sum(p['confidence']>=.45 for p in anchor.get('scale_predictions',()))>=2)


def weak_containing_duplicate(anchor,others,sticker,rack_labels,evidence,tokens,registered,codes):
    """Resolve one wider, single-read prediction of the same labeled load."""
    if (evidence['sku'] not in registered or not sticker or sticker['confidence']<.85 or anchor['confidence']<.65
            or sum(p['confidence']>=.5 for p in anchor.get('scale_predictions',()))<2
            or covered_fraction(evidence['bbox'],anchor['bbox'])<.95):
        return None
    conflicts=[d for d in others if covered_fraction(evidence['bbox'],d['bbox'])>=.5]
    if len(conflicts)!=1:
        return None
    wider,=conflicts
    if (wider.get('detection_stable') is not False or wider['confidence']>=.4
            or len(wider.get('scale_predictions',()))!=1):
        return None
    box,outer=anchor['bbox'],wider['bbox']
    horizontal=min(box[2],outer[2])-max(box[0],outer[0])
    if (covered_fraction(box,outer)<.95 or covered_fraction(outer,box)<.45
            or horizontal<.85*(outer[2]-outer[0])
            or not under_load(sticker['bbox'],outer)
            or any(not same_label(label,sticker) and
                   (under_load(label['bbox'],outer) or covered_fraction(label['bbox'],outer)>=.5)
                   for label in rack_labels)):
        return None
    search_box=pallet_ocr_box(outer)
    nearby=[token for token in tokens if covered_fraction(token['bbox'],search_box)>=.5]
    if (any(not same_label(other,evidence) or other['sku']!=evidence['sku']
            for other in product_evidence(nearby,registered))
            or any(covered_fraction(code['bbox'],search_box)>=.5 for code in codes)):
        return None
    return wider


def review_live(raw,codes,snapshot,positions,regions,registry,*,test_item_codes=None,printed_stickers=False):
    testing=test_item_codes is not None
    identified,unregistered=([],[]) if testing else identify_codes(codes,snapshot,registry)
    names={p['sku']:p.get('name','') for p in snapshot['products']}
    registered=names.keys()
    tokens=raw.get('ocr_tokens',[])
    rack_labels=list(rack_label_evidence(tokens))
    rack_token_ids={token_id for label in rack_labels for token_id in label['ocr_token_ids']}
    positions_by_id={p['position_id']:p for p in positions}
    regions=[*regions,*read_location_regions(raw,positions,regions,test_rack_labels=testing,
                                             printed_stickers=testing and printed_stickers,registered=registered)]
    rows,assigned=[],set()
    for region in regions:
        position=positions_by_id.get(region['position_id'])
        if position is None:continue
        box=region['bbox']
        detections=[d for d in raw['detections'] if covered_fraction(d['bbox'],box)>=.5]
        anchor=next((d for d in detections if d['id']==region.get('detection_id')),None)
        sticker=region.get('location_evidence')
        fragments={d['id'] for d in detections if anchor and sticker and (
            is_fragment(d,anchor,rack_labels) or
            (testing and printed_stickers and is_printed_sticker_duplicate(d,anchor,rack_labels,sticker))
        )}
        excluded=set()
        if testing and anchor and sticker:
            for detection in detections:
                relation=test_group_relation(detection,anchor,sticker,rack_labels)
                if relation=='contained_part': fragments.add(detection['id'])
                elif relation in ('outside_group','crosses_shelf_sign'): excluded.add(detection['id'])
            fragments.update(region.get('test_group_fragment_ids',[]))
            fragments.difference_update(excluded)
            detections=[d for d in detections if d['id'] not in excluded]
        loads=[d for d in detections if d['id'] not in fragments]
        pallet_box=pallet_ocr_box(loads[0]['bbox']) if len(loads)==1 else box
        local_tokens=[t for t in tokens if covered_fraction(t['bbox'],box)>=.75
                      or covered_fraction(t['bbox'],pallet_box)>=.75]
        location_label=region.get('location_evidence')
        ocr=[] if testing else product_evidence([t for t in local_tokens if t['id'] not in rack_token_ids],registered)
        barcode=[c for c in identified if covered_fraction(c['bbox'],box)>=.95
                 or covered_fraction(c['bbox'],pallet_box)>=.95]
        ids=sorted({e['sku'] for e in ocr+barcode})
        test_detection_consensus=testing and region.get('test_detection_consensus')
        object_verified=(len(loads)==1 and loads[0].get('detection_stable') is True
                         and (loads[0]['confidence']>=.5 or test_detection_consensus
                              or region.get('two_label_detection'))
                         and str(loads[0].get('class_name','')).lower()!='partial')
        supported_ocr,supported_barcode=[],[]
        containing_duplicates={}
        if object_verified:
            verified_load,=loads
            detected_box=verified_load['bbox']
            others=[d for d in raw['detections'] if d['id']!=verified_load['id'] and d['id'] not in fragments]
            other_boxes=[d['bbox'] for d in others]
            for evidence in ocr:
                if (evidence['confidence']>=OCR_THRESHOLD and not evidence.get('confusable_skus')
                        and not evidence.get('requires_separator_reread')):
                    area=product_label_area(evidence['bbox'],detected_box,box,other_boxes)
                    if area is None and len(ids)==1:
                        duplicate=weak_containing_duplicate(verified_load,others,sticker,rack_labels,evidence,
                                                            tokens,registered,codes)
                        if duplicate:
                            recovered=product_label_area(evidence['bbox'],detected_box,box,
                                                         [d['bbox'] for d in others if d is not duplicate])
                            if recovered=='load':
                                area=recovered
                                containing_duplicates[duplicate['id']]=duplicate
                    if area:
                        evidence['evidence_area']=area
                        supported_ocr.append(evidence)
            for evidence in barcode:
                area=product_label_area(evidence['bbox'],detected_box,box,other_boxes)
                if area:supported_barcode.append({**evidence,'evidence_area':area})
        identity_verified=len(ids)==1 and bool(supported_ocr or supported_barcode)
        if identity_verified and containing_duplicates:
            fragments.update(containing_duplicates)
            detections.extend(d for d in containing_duplicates.values() if d not in detections)
        expected=[p['sku'] for p in position['items']]
        matching_items=[p for p in position['items'] if identity_verified and p['sku']==ids[0]]
        empty=bool(matching_items and all(p['quantity']==0 for p in matching_items))
        pending=bool(matching_items and any(p.get('status')!='NORMAL' for p in matching_items))
        match=bool(matching_items and not empty and not pending)
        reasons=[]
        if location_label and location_label.get('normalized_text'):
            reasons.append(f"위치 라벨 OCR 보정 · {location_label['text']} → {location_label['normalized_text']}")
        if region.get('two_label_detection'):
            reasons.append('여러 탐지 입력에서 적재물과 랙 라벨 위치가 확인됐고 상품 라벨을 재판독했습니다.')
        if any(e.get('requires_separator_reread') for e in ocr):
            reasons.append('상품 코드의 구분 기호를 다시 읽어야 합니다.')
        if any(e.get('confusable_skus') and not e.get('requires_separator_reread') for e in ocr):
            reasons.append('상품 코드에 0/O 또는 5/S로 읽힐 수 있는 글자가 있어 확인이 필요합니다.')
        if not ids:reasons.append('적재물에서 상품 코드나 등록된 바코드를 확인하지 못했습니다.')
        elif len(ids)>1:reasons.append('서로 다른 상품 코드가 보여 추가 확인이 필요합니다.')
        if not object_verified:reasons.append('온전히 촬영된 적재물 한 개를 확인해야 합니다.')
        if ids and not identity_verified:reasons.append('상품 코드 판독 신뢰도 또는 적재물과의 위치 관계를 확인해야 합니다.')
        if snapshot['issues']:reasons.append('전산 원자료에 누락·충돌 항목이 있습니다.')
        if not position['side']:reasons.append('전산에 좌우 구분이 없어 랙·단 수준으로 대조합니다.')
        code,decision=5,'MANUAL_REVIEW_REQUIRED'
        if empty:status='실제전산_무재고위치에상품탐지'
        elif pending:status='전산이상상태_재확인'
        elif match:
            status='상품·위치일치_수량확인필요'
            code,decision=1,'PRODUCT_LOCATION_MATCH'
        elif identity_verified and not snapshot['issues']:
            status,code,decision=('실제전산_상품위치불일치',2,'MISPLACED') if ids[0] in registered else ('실제전산_미등록상품',4,'UNREGISTERED')
        elif ids:status='실제상품코드_추가검증필요'
        else:status='실제상품코드_미확인'
        reasons.append('사진 대조는 상품과 위치를 확인합니다. 낱개 수량은 별도로 확인해야 합니다.')
        quantities=[p['quantity'] for p in position['items'] if len(ids)==1 and p['sku']==ids[0]]
        token_ids=[t['id'] for t in local_tokens]
        if location_label:token_ids=list(dict.fromkeys(token_ids+location_label['ocr_token_ids']))
        row={'rack':position['rack'],'floor':position['floor'],'zone':position['zone'],'side':position['side'],
            'position_id':position['position_id'],'location_label':position['source_location'],'location_bbox':box,
            'erp_product_ids':expected,'erp_inventory_ids':[item['document_id'] for item in position['items'] if item.get('document_id')],
            'ai_product_ids':ids,'ai_product_id':','.join(ids),
            'yolo_candidates':len(detections),'review_status':status,'verification_passed':False,
            'product_location_verified':match,'product_identity_verified':identity_verified,
            'location_verified':True,'location_method':region.get('confirmation_method','operator_confirmed'),
            'location_evidence':location_label,
            'identity_method':'OCR_PRODUCT_CODE' if identity_verified and supported_ocr else
                              'REGISTERED_BARCODE' if identity_verified and supported_barcode else None,
            'expected_quantity':quantities[0] if len(quantities)==1 else None,'observed_quantity':None,'quantity_verified':False,
            'ocr_max_confidence':max((e['confidence'] for e in ocr),default=0.),
            'ocr_yolo_spatial_match':bool(supported_ocr),'product_matches':ocr,'ocr_token_ids':token_ids,
            'detection_ids':[d['id'] for d in detections],'decoded_code_evidence':supported_barcode,
            'verified_detection_id':loads[0]['id'] if identity_verified else None,
            'fragment_detection_ids':sorted(fragments) if identity_verified else [],
            'review_reasons':reasons,'f04_status_code':code,'f04_status':decision}
        if excluded: row['excluded_group_candidate_ids']=sorted(excluded)
        if testing:
            expected_code=test_item_codes.get(position['position_id'])
            observed=None
            if location_label:
                parts=visible_location(location_label.get('normalized_text') or location_label['text'])
                if parts:
                    observed=f"{parts['rack']}-{parts['tier']}F"
                    if parts['side']:
                        observed+=f"-{parts['side']}"
            test_match=bool(expected_code and observed==expected_code and object_verified)
            test_reasons=['내장 테스트 이미지의 랙 라벨을 테스트용 상품 코드로 대조했습니다.']
            if test_detection_consensus:
                test_reasons.append('서로 다른 탐지 입력에서 적재물 위치가 반복 확인됐고 랙 라벨도 판독했습니다.')
            if location_label and location_label.get('normalized_text'):
                test_reasons.append(f"위치 라벨 OCR 보정 · {location_label['text']} → {location_label['normalized_text']}")
            if not test_match:
                test_reasons.append('전산의 테스트 라벨과 사진 속 적재물을 함께 확인해야 합니다.')
            row.update(erp_product_ids=[expected_code] if expected_code else [],
                       ai_product_ids=[observed] if observed else [],ai_product_id=observed or '',
                       test_label_match=test_match,identity_method='TEST_RACK_LABEL' if observed else None,
                       verified_detection_id=anchor['id'] if test_match and anchor else None,
                       fragment_detection_ids=sorted(fragments) if test_match else [],
                       review_status='테스트 랙 라벨 일치' if test_match else '테스트 라벨 확인 필요',
                       review_reasons=test_reasons,f04_status_code=1 if test_match else 5,
                       f04_status='TEST_RACK_LABEL_MATCH' if test_match else 'MANUAL_REVIEW_REQUIRED')
        rows.append(row)
        assigned.update(d['id'] for d in detections)

    remaining=[d for d in raw['detections'] if d['id'] not in assigned]
    for detection in remaining or ([None] if not rows else []):
        local=[t for t in tokens if covered_fraction(t['bbox'],pallet_ocr_box(detection['bbox']))>=.95] if detection else tokens
        ocr=[] if testing else product_evidence([t for t in local if t['id'] not in rack_token_ids],registered)
        ids=sorted({e['sku'] for e in ocr})
        reasons=['사진 속 랙·단을 선택한 창고의 전산 위치 한 곳과 연결하지 못했습니다.']
        observed_racks=list(dict.fromkeys(label['text'] for label in rack_labels
                                         if detection and under_load(label['bbox'],detection['bbox'])))
        if observed_racks:
            reasons.append('사진에서 읽은 위치 라벨 · '+', '.join(observed_racks))
            if not detection.get('detection_stable'):
                reasons.append('다른 탐지 입력에서 같은 적재물 위치를 다시 확인하지 못했습니다.')
            elif detection['confidence']<.5:
                reasons.append('적재물 탐지와 랙 라벨의 연결 근거가 자동 확인 기준에 못 미쳤습니다.')
        if not ids and not testing:reasons.append('사진에서 전산과 대조할 상품 코드를 확인하지 못했습니다.')
        rows.append({'rack':'UNMAPPED','floor':None,'erp_product_ids':[],'ai_product_ids':ids,
            'ai_product_id':','.join(ids),'yolo_candidates':int(detection is not None),'review_status':'실제창고위치_확인필요',
            'verification_passed':False,'product_location_verified':False,'location_verified':False,
            'location_method':'unverified','quantity_verified':False,
            'ocr_max_confidence':max((e['confidence'] for e in ocr),default=0.),
            'product_matches':ocr,'ocr_token_ids':[t['id'] for t in local],
            'detection_ids':[detection['id']] if detection else [],
            'review_reasons':reasons,
            'f04_status_code':5,'f04_status':'MANUAL_REVIEW_REQUIRED'})
    unmapped=[]
    if testing:
        # One row per authoritative test position. Raw detector candidates are
        # diagnostics, not additional inventory positions; missing ERP positions
        # must remain in the denominator rather than silently disappearing.
        unmapped=[row for row in rows if not row.get('position_id') and row['detection_ids']]
        localized={}
        for row in rows:
            if row.get('position_id'): localized.setdefault(row['position_id'],[]).append(row)
        expected_rows=[]
        for position in positions:
            found=localized.get(position['position_id'],[])
            if len(found)==1:
                expected_rows.append(found[0]);continue
            expected_code=test_item_codes.get(position['position_id'])
            expected_rows.append(dict(rack=position['rack'],floor=position['floor'],zone=position['zone'],side=position['side'],
                position_id=position['position_id'],location_label=position['source_location'],location_bbox=None,
                erp_product_ids=[expected_code] if expected_code else [],ai_product_ids=[],ai_product_id='',
                yolo_candidates=0,review_status='테스트 라벨·적재물 미확인',verification_passed=False,
                product_location_verified=False,product_identity_verified=False,location_verified=False,
                location_method='unverified',identity_method=None,quantity_verified=False,ocr_max_confidence=0.,
                product_matches=[],ocr_token_ids=[],detection_ids=[],fragment_detection_ids=[],verified_detection_id=None,
                test_label_match=False,f04_status_code=5,f04_status='MANUAL_REVIEW_REQUIRED',
                review_reasons=['전산에 등록된 테스트 위치입니다. 라벨 판독과 적재물 탐지를 함께 확인하지 못했습니다.']))
        rows=expected_rows
    for row in rows:
        codes_in_row=set(row['erp_product_ids']+row['ai_product_ids'])
        row['product_names']={sku:names[sku] for sku in sorted(codes_in_row) if names.get(sku)}
    return {'status':'compared','data_source':'FIREBASE_READ_ONLY',
            'comparison_module':'F04_TEST_RACK_LABEL_REVIEW' if testing else 'F04_LIVE_PRODUCT_LOCATION_REVIEW',
            'erp_backend':'FIRESTORE','firebase_inventory_modified':False,'total_positions':len(rows),'results':rows,
            'registered_code_observations':identified,'unregistered_codes':unregistered,
            'inventory_issues':snapshot['issues'],'inventory_source_count':snapshot['source_count'],
            'inventory_snapshot_complete':snapshot['complete'],'inventory_revision':snapshot['revision'],
            'verification_scope':'test_rack_label_as_item_code' if testing else 'read_product_code_and_verified_location_quantity_unverified',
            **({'unmapped_observations':unmapped,'summary_basis':'all_registered_test_positions',
                'expected_position_count':len(positions)} if testing else {})}
