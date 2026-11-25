# 테스트 참고 사진 6장 정상 일치 개선

현재 앱의 정상·오적재 12장 구성과 상품 외관 비교는 [paired44](../paired44/README.md)를 사용합니다. 아래 내용은 `.43`의 랙 라벨 대조 검증 기록입니다.

2026-11-24, 파이프라인 `2026-11-24.43`. 사용자가 허용한 사진 라벨 정정, 테스트 전산 정정, OCR 및 적재 묶음 탐지 개선을 적용했습니다. 수정한 사진과 실제 Firestore 재조회 자료로 분석한 결과는 **109/109곳, 100%**입니다.

이 수치는 **해당 6장의 테스트 라벨·위치 대조 결과**입니다. 라벨과 전산 기준을 함께 정정했고, 이 촬영 장면을 별도 테스트 탐지 모델의 학습에 포함했습니다. 새로운 사진에 대한 독립 평가 정확도, 실제 상품 SKU 식별률, 상자 수량 검증률을 의미하지 않습니다. 앱에도 학습 포함 사실과 테스트 판정 범위를 표시합니다.

| 앱 목록 | 수정 전 | 최종 | 화면 |
| --- | ---: | ---: | --- |
| 테스트 03 · 생활용품 A 정면 | 8/20 | 20/20 | [캡처](screenshots/accuracy43_03.png) |
| 테스트 04 · 생활용품 B 정면 | 13/21 | 21/21 | [캡처](screenshots/accuracy43_04.png) |
| 테스트 05 · 생활용품 C 전경 | 27/42 | 42/42 | [캡처](screenshots/accuracy43_05.png) |
| 테스트 12 · 가전제품 B 정면 | 9/12 | 12/12 | [캡처](screenshots/accuracy43_12.png) |
| 테스트 13 · 장난감 정면 | 0/10 | 10/10 | [캡처](screenshots/accuracy43_13.png) |
| 테스트 14 · 포장 팔레트 사선 | 0/4 | 4/4 | [캡처](screenshots/accuracy43_14.png) |
| 합계 | **57/109 (52.29%)** | **109/109 (100%)** | |

수정 전과 최종 결과는 서로 다른 사진 라벨·전산 기준을 사용하므로 인식 모델 단독 성능 향상으로 비교하면 안 됩니다. 원본은 `originals/`, 코드로 정확하게 합성한 최종 사진은 `prepared/`에 보관합니다. 상품 사진은 유지하고 라벨 영역을 정정했습니다. 테스트 03의 `A02-5F`도 포함합니다.

## 앱에서 확인

1. 승인된 `ABC-002` QA 계정으로 로그인하고 **AI 실사 → 테스트 이미지 → 기존 테스트 6장**을 선택합니다.
2. 사진을 선택하고 **이 사진 분석**을 누릅니다.
3. 해당 사진의 등록 위치 전부가 초록색이고, **테스트 라벨 일치율 100%**인지 확인합니다. 테두리를 누르면 실제 OCR과 전산 대조 근거를 볼 수 있습니다.
4. **위치 미연결 탐지 후보 표시**를 켜면 위치와 연결되지 않은 추가 탐지 후보를 확인할 수 있습니다. 이 후보는 등록 위치의 일치율과 별도로 표시됩니다.

기존 정상·오적재 비교 사진 01·02와 참고 사진 6장, 총 8장이 선택 가능합니다. 06~11의 비활성 상태는 유지합니다. 정상·오적재의 상품 교환 비교는 [기존 기능 5번 시연](../comparison/README.md)을 사용합니다.

## 구현과 전산 반영

- 실제 흰색 라벨판을 찾고, 전체 OCR과 라벨 부분 확대 판독을 조합합니다. 확대 판독이 서로 충돌하면 일치로 확정하지 않습니다. OCR에는 정답 라벨이나 전산 위치 목록을 전달하지 않습니다.
- 탐지 입력에서는 인쇄된 라벨 글자를 가려 글자 모양을 적재물로 학습·탐지하는 영향을 줄였습니다. 화면과 OCR에는 원래 사진을 사용합니다.
- 얇은 선반의 세로 확대 재탐지와 여러 배율의 실제 탐지 근거를 사용합니다. 라벨과 다른 층까지 가로지르는 큰 박스는 연결에서 제외하고, 한 포장 묶음 안의 부분 탐지를 정리합니다.
- 등록된 모든 테스트 위치를 분모에 포함합니다. 발견하지 못한 위치는 확인 필요로 남고, 원시 탐지 후보도 보존합니다. 탐지나 OCR 실패를 정상으로 강제하지 않습니다.
- 별도 모델 `app/src/main/java/com/example/myapp/F03_py/runs/detect/shelf_test_v43/weights/best.pt`를 이 테스트 모드에서만 사용합니다. 운영 모델과 기존 독립 선반 모델은 교체하지 않았습니다. 학습 출처와 범위는 같은 모델 폴더의 `training_metadata.json`, `training_provenance.json`에 있습니다.
- Firestore `warehouseqa-8c262/(default)`의 회사 `ABC-002` 재고 242건 중 **허용된 테스트 문서 109건만** 사진 해시·위치·라벨 정보를 정정했습니다. 기존 수량과 상태는 보존했습니다. 저장 전 자료는 `inventory_before_raw.json`, 저장 후 실제 재조회 자료는 `inventory_readback.json`입니다.
- 테스트의 `product_location_verified`, `quantity_verified`, `verification_passed`는 `false`를 유지합니다. 초록색 표시는 테스트 라벨·적재 위치의 일치입니다.

## 검증 근거

- [최종 분석](final_report.json): 실제 OCR·YOLO 추론과 Firestore 재조회 자료로 6장 전부 100%. 인증과 재고 공급은 로컬 TestClient에서 대체했으며 OCR과 탐지는 대체하지 않았습니다.
- [반례 검증](release_verification.json): 첫 사진의 전산 위치 오류, 수량 0, 이상 상태를 각각 메모리에서 주입하면 20곳 중 19곳만 일치합니다. 반례는 Firestore에 쓰지 않았습니다. 미등록 테스트 사진 422, 미인증 요청 401, 테스트/실사 결과 분리도 확인했습니다.
- Python **141개**, Android JVM **19개**, 에뮬레이터 계측 **4개** 통과. 디버그 앱과 테스트 APK 빌드 성공. [기록](verification/)과 [설치 파일·이미지 해시 검사](package_verification.json)를 보관합니다.
- 에뮬레이터 검증은 실제 최종 API 응답을 앱 결과 화면에 입력해 6장 109개 초록색 테두리의 렌더링 픽셀, 요약, 클릭 상세 창을 확인했습니다. 기존 정상·오적재 비교와 수동 확인 DB 테스트도 통과했습니다. 실제 로그인부터 서버 요청까지 전체 흐름의 자동화 검증은 포함하지 않습니다.
- 최종 APK를 `emulator-5556`에 설치했고, 로컬 서버 `127.0.0.1:8082/health`에서 `.43`과 Firebase ID 토큰 인증 유지 상태를 확인했습니다.

## 다시 분석하기

프로젝트 루트에서 기존 Python 가상환경과 로컬 EasyOCR 모델을 사용합니다. Firebase CLI 로그인이 되어 있어야 전산 자료를 읽을 수 있습니다. 읽기·분석 명령은 Firestore를 수정하지 않습니다.

```powershell
$cli = Join-Path (Split-Path (Get-Command firebase.cmd).Source) 'node_modules/firebase-tools/lib'
node ./test_photos/accuracy43/read_inventory.cjs $cli ./inventory-current.json
& ./app/src/main/java/com/example/myapp/F03_py/.venv/Scripts/python.exe -B -u ./test_photos/accuracy43/evaluate.py ./accuracy43-current ./inventory-current.json
& ./app/src/main/java/com/example/myapp/F03_py/.venv/Scripts/python.exe -B ./test_photos/accuracy43/verify_package.py
```

`inventory-current.json`과 `accuracy43-current/`는 새 경로여야 합니다. 분석 결과는 출력 폴더의 `report.json`과 사진별 전체 응답에 저장합니다. `prepare.py`, `train.py`, `register.py`, `install_model.py`, `apply_inventory.cjs`는 이번 제작·학습·반영용 도구이므로 일반 재검증에는 실행할 필요가 없습니다. `annotations.json`의 수동 박스는 이미지 제작·학습·평가용이며 운영 추론 입력으로 사용하지 않습니다.
