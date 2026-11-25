# 회원가입 승인 및 관리자 화면 검증

신규 가입은 Firebase Authentication 계정과 `signupRequests/{uid}`의 승인 대기 요청을 함께 생성한다. 인증 계정 생성 후 네트워크 오류로 요청 저장에 실패한 경우, 로그인해서 다시 요청할 수 있다. 기존에 인증 계정만 만들었던 회원도 로그인한 뒤 회사·역할을 선택하여 요청한다.

관리자는 회사 코드 없이 로그인하면 관리자 화면으로 이동한다. `warehouseAdmins/{uid}.enabled`는 IAM 권한으로만 등록할 수 있고 앱에서 변경할 수 없다. 관리자 비밀번호는 Firebase Authentication에만 설정하며 앱 소스나 APK에 넣지 않는다. 아이디와 비밀번호를 비교하는 로컬 관리자 우회 경로는 없다.

관리자는 승인 대기·승인 완료·반려 목록을 조회한다. 요청별 회사 코드와 QA/LOADER 역할을 지정해 승인하거나 사유를 입력해 반려한다. 가입 승인과 `warehouseMemberships/{uid}` 생성은 하나의 Firestore 트랜잭션이며 규칙의 `getAfter`로 두 문서의 일치를 강제한다. 기존 직원의 권한 수정·삭제 기능은 추가하지 않았다. 관리자 표식만 가진 계정에는 회사별 재고 접근 권한이 부여되지 않는다.

검증 결과:

- Android 단위 테스트 42개 통과 (`../approval49-build.log`).
- 로컬 Firebase 승인·권한 상승 차단 테스트 37개 통과 (`rules-verification.json`).
- 기존 재고·오적재 규칙 테스트 31개 통과 (`existing-rules-tests.log`).
- Android 실제 화면 시나리오 통과 (`android-tests.log`): 회원가입, 승인 대기 로그인, 회사 코드 없는 관리자 로그인, 회사 지정 승인, 반려 후 재신청, 승인된 계정의 작업자 로그인.
- 운영 규칙 배포 성공, 배포 규칙과 검증 소스 일치 (`cloud-rules-after.json`). 재고·오적재·작업 이력 규칙은 배포 전 백업과 일치한다.
- 운영 관리자 인증, 자기 관리자 문서 조회, 대기 목록 조회 성공 (`cloud-admin-verification.json`). 운영 재고 쓰기 0건. 확인 당시 대기 요청 0건.

운영 규칙 백업은 `cloud-rules-before.json`에 보관했다. 로컬 시험은 기존 18861/18862 에뮬레이터를 사용했고, 추가 실행 시도는 포트 충돌로 종료되어 기존 프로세스를 중단하지 않았다. 테스트에서 스크린샷 API가 이미지를 반환하지 않아 화면 캡처는 없으며, 화면 요소와 서버 문서로 동작을 검증했다.

배포 APK: `app/build/outputs/apk/debug/app-debug.apk` (프로젝트 루트 기준).

공식 구현 근거: [Firestore 원자적 작업 검증](https://firebase.google.com/docs/firestore/security/rules-conditions#access_other_documents), [Firebase Admin SDK 사용자 생성 구현](https://github.com/firebase/firebase-admin-node/blob/master/src/auth/auth-api-request.ts).
