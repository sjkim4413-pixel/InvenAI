# InvenAI

창고 사진의 상품·위치를 전산 재고와 대조하고, 오적재 발견부터 적재자 조치와 검수 결과까지 이어서 관리하는 Android 앱과 Python 분석 서버입니다.

## 주요 기능

- QA·적재자 로그인과 회사별 재고 접근, 관리자 가입 승인·반려·재신청
- 재고 목록, 입고·출고·이동·수량 정정, 전산 재고 재검수
- 창고를 선택한 사진 실사와 상품·위치 대조
- 정상 사진 6장과 오적재 편집본 6장으로 구성한 비교 테스트
- 오적재 알림, 적재자 재적재 회신, QA 재검수 승인·재조치 요청
- 앱 화면과 기기 알림, 홈 최근 현장 로그와 작업·이상·처리 완료 이력

`admin` 관리자는 가입 승인 화면을 사용합니다. 실사와 재검수는 회사·QA 권한이 등록된 계정, 재적재 회신은 같은 회사의 LOADER 계정을 사용합니다. 관리자 비밀번호와 서버 인증키는 저장소에 넣지 않습니다.

## 구성

| 경로 | 내용 |
|---|---|
| `app/src/main/java/com/example/myapp` | Android 화면, 재고·알림·이력 처리 |
| `app/src/main/java/com/example/myapp/F03_py` | FastAPI, 사진 인식·전산 대조, FCM 전송 |
| `test_image_ai` | 테스트 이미지와 상품·위치 기준 정보 |
| `firestore.rules` | 회사·역할 권한과 재고·이력·가입 승인 규칙 |
| `app/src/test`, `app/src/androidTest` | 단위 테스트와 Android 화면 통합 테스트 |
| `test_photos` | 검증 스크립트, 입력 데이터, 검증 결과 요약 |
| `docs/demo-scenario.md` | 촬영 준비, 6분 시연 순서, 화면별 발표 멘트 |

분석 모델 가중치는 `F03_py/runs/detect`에 포함합니다. 가상환경, 다운로드한 OpenCV SDK, 빌드 결과, 실사용 업로드, 알림 토큰 DB, 인증키와 기기별 설정은 제외합니다.

## Android 실행

저장소의 `project` 폴더를 Android Studio에서 엽니다. 이 README와 같은 위치가 Android 프로젝트 루트입니다.

1. Firebase 콘솔에서 해당 Android 앱의 `google-services.json`을 받아 `app/google-services.json`에 둡니다. 앱 패키지는 `com.example.myapp`입니다.
2. Android SDK 경로를 로컬 `local.properties`에 설정합니다. Gradle 실행에는 JDK 17을 사용합니다.
3. OpenCV 4.14.0 Android SDK를 프로젝트 루트의 `opencv-4.14.0-android-sdk/OpenCV-android-sdk/sdk` 위치에 풉니다. 경로는 `app/src/main/cpp/CMakeLists.txt`에 지정되어 있습니다.
4. 다음 명령으로 확인합니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 테스트 이미지 목록은 빌드 과정에서 앱 assets로 복사됩니다.

## 분석 서버 실행

프로젝트 루트에서 분석 서버 폴더로 이동해 Python 가상환경을 준비합니다. 패키지는 `requirements-verified.txt`에 기록되어 있습니다. PyTorch 설치 환경은 사용할 CPU·GPU에 맞춥니다.

```powershell
Set-Location app/src/main/java/com/example/myapp/F03_py
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-verified.txt
.\.venv\Scripts\python.exe -m uvicorn f03_api:app --host 127.0.0.1 --port 8082
```

서버 상태는 `http://127.0.0.1:8082/health`에서 확인합니다. 이미 정상 응답하는 서버가 있으면 같은 포트로 다시 실행하지 않습니다. Android 에뮬레이터는 호스트 PC의 서버에 `10.0.2.2`로 연결합니다. 실제 기기 연결 시에는 기기에서 접근 가능한 서버 주소와 네트워크 설정이 필요합니다.

서버는 앱 설정 파일을 상위 경로에서 찾습니다. 다른 경로를 쓰면 `IVENAI_FIREBASE_CONFIG`를 지정합니다. FCM 기기 알림에는 별도 서버 전용 자격 증명이 필요하며 `IVENAI_FCM_CREDENTIALS` 또는 `GOOGLE_APPLICATION_CREDENTIALS`로 지정할 수 있습니다. 기본 경로는 프로젝트 루트의 `.secrets/fcm-sender.json`입니다. 자격 증명을 APK나 저장소에 포함하지 않습니다.

## 검증

Android 단위 테스트와 빌드는 위 Gradle 명령을 사용합니다. Python 테스트는 분석 서버 폴더에서 실행합니다.

```powershell
.\.venv\Scripts\python.exe -B -m unittest discover -s tests
```

로컬 Firestore·Auth 통합 검증은 `test_photos/ui45/firebase-emulator.json`과 각 검증 폴더의 스크립트를 사용합니다. 에뮬레이터의 Firestore 포트는 18861, Auth 포트는 18862입니다. Firebase 에뮬레이터 실행에는 JDK 21 환경이 필요합니다. Android 통합 테스트는 실행 전에 로컬 에뮬레이터용 계정·재고 데이터를 준비해야 합니다.

실사·재적재 현장 이력 검증 결과와 실행 경로는 `test_photos/history50/README.md`에 정리되어 있습니다. 운영 규칙 배포는 연결 프로젝트를 확인한 뒤 별도로 수행합니다. 소스 변경이나 Git 업로드만으로 운영 규칙이 배포되지는 않습니다.

## 시연에서 구분할 내용

검증된 테스트 이미지의 정상 대조 결과와 처음 촬영한 현장 사진의 인식 성능은 구분해서 설명합니다. 테스트 실사는 상품·위치 비교와 알림·조치 흐름을 확인하는 기능이며 실사 알림 처리만으로 전산 재고 수량이나 위치를 자동 변경하지 않습니다. 실제 전산 재고의 이동은 재고 이동 또는 전산 재검수 승인 과정에서 확인합니다.
