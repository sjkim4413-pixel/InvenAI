import csv
import re
import os
os.environ.setdefault('YOLO_AUTOINSTALL','false')
import threading
from pathlib import Path

import cv2
import easyocr
import numpy as np
import torch
from ultralytics import YOLO
from f03_verification import spatially_verified
from f03_photo_observer import observe_photo, read_image

BASE_DIR = Path(__file__).resolve().parent
MODEL_PATH = Path(os.environ.get('IVENAI_MODEL_PATH', str(BASE_DIR / 'runs/detect/runs/pallet_v3/warehouse_v3/weights/best.pt')))
CATALOG_PATH = Path(os.environ.get('IVENAI_PRODUCT_CATALOG', str(BASE_DIR/'config/product_catalog.json')))
LOCATION_MAP_PATH = Path(os.environ.get('IVENAI_LOCATION_MAP', str(BASE_DIR/'config/location_map.json')))
OCR_THRESHOLD = 0.45
PASS_OCR_THRESHOLD = 0.85
PASS_YOLO_THRESHOLD = 0.50
YOLO_CONF_THRESHOLD = 0.10
YOLO_IOU_THRESHOLD = 0.20

_model = None
_reader = None
_model_lock = threading.Lock()
_inference_lock = threading.RLock()


def get_ai_models(include_ocr=True):
    global _model, _reader
    with _model_lock:
        if _model is None:
            if not MODEL_PATH.exists():
                raise FileNotFoundError('YOLO 모델을 찾을 수 없습니다.')
            torch.set_num_threads(max(1, min(8, int(os.environ.get('IVENAI_CPU_THREADS', '4')))))
            _model = YOLO(str(MODEL_PATH))
        if include_ocr and _reader is None:
            _reader = easyocr.Reader(['en'], gpu=False, download_enabled=True)
    return _model, _reader


def _floor_from_y(y, boundaries):
    for row in range(5):
        if boundaries[row] <= y < boundaries[row + 1]:
            return 5 - row
    return 1 if y >= boundaries[-1] else 5


def analyze_image(image_path, rack_number=None, output_path=None, *, single_rack=False,
                  catalog=None, location_map=None, company_code=None, warehouse_id=None):
    with _inference_lock:
        result=_analyze_image(image_path,rack_number,None,single_rack=single_rack,
                              catalog=catalog,location_map=location_map,
                              company_code=company_code,warehouse_id=warehouse_id)
        if output_path is not None:
            target=Path(output_path)
            if not target.is_absolute(): target=BASE_DIR/target
            fields=['랙번호','단수','YOLO탐지후보수','인식제품ID','검토상태',
                    'verification_passed','ocr_max_confidence','ocr_yolo_spatial_match']
            with target.open('w',newline='',encoding='utf-8-sig') as stream:
                writer=csv.DictWriter(stream,fieldnames=fields,extrasaction='ignore')
                writer.writeheader()
                writer.writerows(result['results'])
        return result


def _unmapped_review(image_path,image,rack_number,reason,**context):
    return observe_photo(image_path,image,rack_number,get_ai_models(include_ocr=True),MODEL_PATH,
                         catalog_path=CATALOG_PATH,location_map_path=LOCATION_MAP_PATH,**context)


def _analyze_image(image_path,rack_number=None,output_path=None,*,single_rack=False,
                   catalog=None,location_map=None,company_code=None,warehouse_id=None):
    image_path = Path(image_path)
    if not image_path.is_absolute():
        image_path = BASE_DIR / image_path
    image = read_image(image_path)

    if not single_rack or not rack_number:
        return _unmapped_review(image_path,image,rack_number,'랙과 단 위치가 확인되지 않았습니다.',
                                catalog=catalog,location_map=location_map,
                                company_code=company_code,warehouse_id=warehouse_id)

    h, w = image.shape[:2]
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, np.array([5, 100, 70]), np.array([25, 255, 255]))
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(5, int(w * 0.10)), 3))
    horizontal = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    scores = np.count_nonzero(horizontal, axis=1)
    if scores.max() == 0:
        return _unmapped_review(image_path,image,rack_number,'선반 경계를 확인하지 못했습니다.')

    positions = np.where(scores > scores.max() * 0.4)[0]
    groups = []
    for y in positions:
        if not groups or y - groups[-1][-1] > 20:
            groups.append([int(y)])
        else:
            groups[-1].append(int(y))
    shelves = [int(np.mean(group)) for group in groups]
    shelves = [y for y in shelves if 0.03 * h < y < 0.90 * h]
    merged = []
    for y in shelves:
        if merged and y - merged[-1] < 60:
            merged[-1] = int((merged[-1] + y) / 2)
        else:
            merged.append(y)
    # 5개 선반이 검출된 경우 상단 가로대를 제외
    if len(merged) == 5:
        merged = merged[1:]

    # 선반 경계는 4개여야 5단으로 구분 가능
    if len(merged) != 4:
        return _unmapped_review(image_path,image,rack_number,'5단 선반 구조를 확인하지 못했습니다.')

    boundaries = [0] + merged + [h]

    model, reader = get_ai_models()
    predictions = model.predict(
        source=image, imgsz=640,
        conf=YOLO_CONF_THRESHOLD, iou=YOLO_IOU_THRESHOLD, verbose=False
    )

    # STEP 444: OCR을 먼저 층별로 읽고, 실제 OCR 좌표를 저장합니다.
    floor_recognized = {floor: [] for floor in range(1, 6)}
    for row in range(5):
        floor = 5 - row
        y1, y2 = boundaries[row], boundaries[row + 1]
        crop = image[y1:y2, 0:w]
        if crop.size == 0:
            continue
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        _, binary = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY)
        for bbox, text, confidence in reader.readtext(
            binary, detail=1
        ):
            corrected = str(text).upper().replace(' ', '')
            pattern=os.environ.get('IVENAI_PRODUCT_ID_PATTERN',r'[A-Z][A-Z0-9_-]*[0-9][A-Z0-9_-]*')
            if not re.fullmatch(pattern,corrected):
                continue
            if float(confidence) < OCR_THRESHOLD:
                continue
            cx = float(sum(pt[0] for pt in bbox) / len(bbox))
            cy = float(sum(pt[1] for pt in bbox) / len(bbox)) + y1
            floor_recognized[floor].append({
                'id': corrected,
                'confidence': float(confidence),
                'cx': cx,
                'cy': cy,
                'bbox': (min(pt[0] for pt in bbox), min(pt[1] for pt in bbox)+y1,
                         max(pt[0] for pt in bbox), max(pt[1] for pt in bbox)+y1),
                'inside_yolo_box': False,
            })

    # STEP 444: 각 YOLO 박스는 정확히 한 층에만 배정합니다.
    # 박스 안에 OCR 라벨이 정확히 하나면 그 라벨의 층을 우선합니다.
    # 서로 다른 층의 OCR 라벨을 포함하면 불명확하므로 중심 기준 배정하되
    # 자동 PASS 검증에는 사용하지 않습니다.
    floor_boxes = {floor: [] for floor in range(1, 6)}
    for box in predictions[0].boxes:
        x1, y1, x2, y2 = map(float, box.xyxy[0].tolist())
        confidence = float(box.conf[0])
        center_y = (y1 + y2) / 2
        matched_labels = [
            (floor, item)
            for floor, items in floor_recognized.items()
            for item in items
            if x1 <= item['cx'] <= x2 and y1 <= item['cy'] <= y2
        ]
        matched_floors = {floor for floor, _ in matched_labels}
        unambiguous = len(matched_labels) == 1
        if unambiguous:
            assigned_floor = matched_labels[0][0]
        else:
            assigned_floor = _floor_from_y(center_y, boundaries)

        floor_boxes[assigned_floor].append({
            'xyxy': (x1, y1, x2, y2),
            'confidence': confidence,
            'unambiguous_ocr_match': unambiguous,
            'matched_ocr_count': len(matched_labels),
        })

    all_boxes = [b['xyxy'] for boxes in floor_boxes.values() for b in boxes]
    rows = []
    for row in range(5):
        floor = 5 - row
        recognized = floor_recognized[floor]
        boxes = floor_boxes[floor]
        for entry in recognized:
            matching_boxes = [
                b for b in boxes
                if b['xyxy'][0] <= entry['cx'] <= b['xyxy'][2]
                and b['xyxy'][1] <= entry['cy'] <= b['xyxy'][3]
            ]
            entry['inside_yolo_box'] = (
                len(matching_boxes) == 1 and spatially_verified(
                    entry['bbox'], matching_boxes[0]['xyxy'],
                    (0, boundaries[row], w, boundaries[row+1]), all_boxes
                )
            )

        ids = [entry['id'] for entry in recognized]
        verification_passed = (
            len(recognized) == 1
            and len(boxes) == 1
            and recognized[0]['confidence'] >= PASS_OCR_THRESHOLD
            and recognized[0]['inside_yolo_box']
            and boxes[0]['confidence'] >= PASS_YOLO_THRESHOLD
            and boxes[0]['unambiguous_ocr_match']
        )
        if recognized and boxes:
            status = 'YOLO_OCR_동시확인' if verification_passed else 'YOLO_OCR_추가검증필요'
        elif recognized:
            status = 'OCR인식_YOLO미탐지'
        elif boxes:
            status = 'YOLO탐지_OCR미인식'
        else:
            status = 'AI미확인_재확인'

        rows.append({
            '랙번호': rack_number,
            '단수': floor,
            'YOLO탐지후보수': len(boxes),
            '인식제품ID': ','.join(ids),
            '검토상태': status,
            'verification_passed': verification_passed,
            'location_verified': False,
            'recognized_product_ids': ids,
            'ocr_max_confidence': round(max((e['confidence'] for e in recognized), default=0.0), 4),
            'ocr_yolo_spatial_match': any(e['inside_yolo_box'] for e in recognized),
        })

    if output_path is not None:
        output_path = Path(output_path)
        if not output_path.is_absolute():
            output_path = BASE_DIR / output_path
        fieldnames = ['랙번호', '단수', 'YOLO탐지후보수', '인식제품ID', '검토상태', 'verification_passed', 'ocr_max_confidence', 'ocr_yolo_spatial_match']
        with output_path.open('w', newline='', encoding='utf-8-sig') as file:
            writer = csv.DictWriter(file, fieldnames=fieldnames, extrasaction='ignore')
            writer.writeheader()
            writer.writerows(rows)

    return {
        'status': 'analyzed',
        'image_width': w,
        'image_height': h,
        'rack': rack_number,
        'total_positions': len(rows),
        'shelf_boundaries': merged,
        'results': rows,
    }


if __name__ == '__main__':
    result = analyze_image(
        'test_labeled_racks/A01_P001_test.png',
        rack_number='A01',
        output_path='IvenAI_integrated_test.csv',
    )
    print('===== IvenAI AI 분석 결과 =====')
    for row in result['results']:
        print(row)
    print('분석 위치:', result['total_positions'])
    print('STEP 444 완료')
