# 정상 6장 + 오적재 6장 비교 테스트

최신 앱의 선택 메뉴·테스트용 결과 버튼 정리·적재자 자동 알림은 [ui45](../ui45/README.md)에 있습니다. 아래는 `.44`의 이미지 제작과 외관 대조 기록입니다.

2026-11-24, 서버 `2026-11-24.44`. 기존 단독 비교 사진 `compare_01_normal.png`, `compare_02_misplaced.png`를 앱 이미지 폴더에서 제거하고 `test_photos/comparison/retired/`에 보관했습니다. 기존에 라벨 대조 100%를 확인한 원본 6장을 그대로 유지하고, 각 원본에서 **상품 묶음 두 곳을 교환한 AI 편집 사진**을 추가했습니다. 앱 선택 목록은 정상·오적재 한 쌍씩 **총 12장**입니다.

## 사용 방법

승인된 회사 `ABC-002` QA 계정으로 **AI 실사 → 테스트 이미지**를 엽니다. `테스트 01`부터 `테스트 12`까지 순서대로 정상과 오적재가 나란히 표시됩니다. 사진을 선택해 **이 사진 분석**을 누르고 결과 테두리를 누르면 전산 상품과 사진에서 비교한 상품을 볼 수 있습니다.

| 목록 번호 | 사진 | 바꾼 위치 두 곳 | 정상 원본 | 오적재 편집본 |
| --- | --- | --- | --- | --- |
| 01 / 02 | 생활용품 A 정면 | A01-4F ↔ A03-4F | [초록 20](screenshots/pair_01_normal.png) | [초록 18 · 빨강 1 · 주황 1](screenshots/pair_01_misplaced.png) |
| 03 / 04 | 생활용품 B 정면 | A01-1F ↔ A02-1F | [초록 21](screenshots/pair_02_normal.png) | [초록 19 · 빨강 2](screenshots/pair_02_misplaced.png) |
| 05 / 06 | 생활용품 C 전경 | A01-5F ↔ A02-5F | [초록 42](screenshots/pair_03_normal.png) | [초록 40 · 빨강 2](screenshots/pair_03_misplaced.png) |
| 07 / 08 | 가전제품 정면 | QAE01-1F-01 ↔ QAE02-1F-01 | [초록 12](screenshots/pair_04_normal.png) | [초록 10 · 빨강 2](screenshots/pair_04_misplaced.png) |
| 09 / 10 | 장난감 정면 | QAF01-2F-02 ↔ QAF02-2F-01 | [초록 10](screenshots/pair_05_normal.png) | [초록 8 · 빨강 1 · 주황 1](screenshots/pair_05_misplaced.png) |
| 11 / 12 | 포장 팔레트 사선 | QAG01-2F-01 ↔ QAG01-2F-02 | [초록 4](screenshots/pair_06_normal.png) | [초록 2 · 빨강 2](screenshots/pair_06_misplaced.png) |

정상 원본은 **109/109곳 초록**, 편집본은 이동한 **12/12곳만 빨강 또는 주황**, 나머지 **97곳은 초록**입니다. 주황은 외관 후보를 확정하기 어려워 추가 확인하는 경우입니다. 모든 오적재 편집본에는 실제 이동한 두 위치만 확인 필요로 집계됩니다.

## 비교 범위

이전 [accuracy43](../accuracy43/README.md)의 100%는 실제 OCR·탐지에 따른 랙 라벨 대조 결과입니다. 선반 라벨을 유지한 채 상품만 바꾸면 그 검사만으로 오적재를 식별할 수 없으므로, 이번 12장 선택 항목은 별도 `test_reference_pairs` 모드에서 **원본 기준 상품 외관과 위치를 대조**합니다.

위치 영역은 사람이 지정했고, 각 영역의 실제 상품 픽셀에서 포장 색상과 무늬를 비교합니다. 이미지 해시는 같은 기준 장면을 선택하는 데만 사용합니다. 파일명이나 정상·오적재 구분으로 결과를 정하지 않으며, 이동 위치 정답 파일도 서버 추론에서 읽지 않습니다. 한 쌍은 항상 동일한 현재 Firestore 재고와 동일한 원본 상품 표본을 사용합니다. 후보가 모호하거나 전산 상태가 불완전하면 보류합니다.

이 비교는 **등록한 6개 장면의 외관 비교 시연**입니다. 새로운 사진의 일반화 정확도, 실제 상품 코드·수량 검증, 자동 객체 탐지 성능을 의미하지 않습니다. 실제 검증 필드 `verification_passed`, `product_location_verified`, `quantity_verified`는 모두 `false`이고, 별도 테스트 일치 필드로 초록색을 표시합니다. 앱에도 지정 영역과 기준 사진의 판정 범위를 표시합니다. 운영 실사와 기존 랙 라벨 API는 유지합니다.

이번 작업에서는 Firestore 재고를 수정하지 않았습니다. 기존 회사 재고 242건을 다시 읽어 분석에 사용했습니다. 테스트의 전산 수량·위치 변경 반례는 메모리에서만 수행합니다.

## 이미지 제작과 파일

- 내장 `image_gen` 도구로 각 사진의 상품 묶음 두 곳을 자연스럽게 교환했습니다. [편집 요청·출처](generation_manifest.json), [이동 위치 정답](edit_ground_truth.json).
- AI 편집 원본은 `raw/`에 보관합니다. 기존에 허용된 코드 라벨 합성 방식으로 정확한 선반 글자만 다시 붙여 AI가 바꾼 숫자·글자를 정정했습니다. 원본 정상 사진 6장의 해시는 유지합니다.
- 최종 사진: `test_image_ai/pair_01_misplaced.png`부터 `pair_06_misplaced.png`. 정상 원본은 기존 파일명을 유지합니다.
- 앱 목록은 `test_image_ai/test_images.json`, 서버의 기준 사진·영역 등록은 `test_image_ai/reference_pairs.json`입니다. 기준 영역·정답·서버 프로필은 메인 APK에 넣지 않습니다. 이전에 비활성화했던 참고 사진 6장은 계속 선택 목록에서 제외합니다.

## 검증과 재현

[최종 실제 픽셀 분석](final_report.json)은 실제 업로드→F03→F04 경로와 저장된 Firestore 재조회 자료로 12장을 검증했습니다. 로컬 TestClient에서 인증과 재고 공급을 대체했으며 외관 비교는 실제 이미지로 실행했습니다. 화면 검증은 이 실제 응답을 설치한 앱의 결과 화면에 입력하는 방식이며, 로그인부터 네트워크까지 전체 동작을 자동화한 검증과는 구분합니다.

서버 테스트 **148개**, Android 단위 테스트 **20개**가 통과했습니다. 기기 검사에서 기존 6장 렌더러와 수동 확인 DB 검증은 통과했고, 새 12장 검사는 초기 합동 실행의 창 포커스 오류 후 단독으로 재실행하여 **2개 모두 통과**했습니다. 12장의 실제 테두리 색상 픽셀과 클릭 상세 창을 확인했습니다.

서버 검증 기록은 [backend-tests.log](backend-tests.log), APK 빌드·Android 단위 테스트 기록은 [android-build.log](android-build.log), 기기 최초 기록은 [android-device-tests.log](android-device-tests.log), 12장 재검증 기록은 [android-device-retry.log](android-device-retry.log)에 있습니다. 최종 패키지 검사와 화면 캡처는 `package_verification.json`, `screenshots/`를 확인합니다.

```powershell
# 프로젝트 루트에서, 아직 존재하지 않는 출력 폴더를 지정합니다.
& ./app/src/main/java/com/example/myapp/F03_py/.venv/Scripts/python.exe -B -u ./test_photos/paired44/evaluate.py ./paired44-recheck ./test_photos/paired44/inventory_readback.json
```

`prepare.py`는 제작·등록용이며 일반 재검증에 다시 실행할 필요가 없습니다. `.44` 적용 시 기존 8082 포트의 확인된 F03 서버를 같은 포트의 최신 서버로 교체했습니다.
