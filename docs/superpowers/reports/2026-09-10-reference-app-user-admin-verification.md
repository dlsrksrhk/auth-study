# Reference App 사용자 관리 검증 기록

작성일: 2026-09-10. 범위는 승인된 사용자 관리 설계의 Task 7 API입니다. 실제 OIDC callback, 내장 BFF HTTP 서버, PostgreSQL Testcontainer를 사용했습니다.

## 구현 commit

- `d46e268`: 상태·역할 저장과 version 불변식입니다.
- `e968dbc`: 공통 잠금, actor 재인가, 마지막 관리자 보호와 DB 경쟁 검증입니다. 승인된 범위 확장으로 adapter의 UUID 잠금 조회를 find 후 locking refresh로 바꾸었습니다. 같은 영속성 컨텍스트에 캐시된 actor도 최신 DB 권한으로 판단합니다. 기존 identity 기반 로그인 조회 `findByIdentityForUpdate`는 별도로 `jpaRepository.findLocked(issuer, subject)` 후 locking refresh하는 경로를 유지합니다.
- `28461c9`, `6c1287d`: 같은 snapshot의 목록·count와 빈 필터 결과 검증입니다.
- `69e0f96`: 네 HTTP API, 엄격한 입력, 공개 DTO, 오류·보안 헤더입니다.
- Task 5 구현 commit: 이 보고서를 추가하는 `test: verify app user administration end to end` commit입니다. 새로운 production 변경은 없습니다.

## RED 증거

Task 5는 이미 구현된 동작의 통합 회귀입니다. 최초 실행은 아직 추가하지 않은 `loginAs(String, boolean)` fixture 때문에 compileTestJava에서 실패(exit 1, cannot find symbol 16개)했습니다. 이는 동작 RED로 세지 않습니다. 공통 fixture 추가 후 8개 HTTP 테스트가 즉시 통과했습니다.

동작 검증력을 확인하기 위해 AdminApiHeadersFilter의 `no-store`를 일시적으로 `public`으로 바꾸고 다음 명령을 실행했습니다. try/finally로 원본 파일을 복원했습니다.

```powershell
.\gradlew.bat test --tests '*AppUserAdminHttpIntegrationTest.onlyLocalAdminsCanReadAndEverySecurityResponseIsNotCached' --console=plain
```

결과는 exit 1, `1 test completed, 1 failed`, `BUILD FAILED in 16s`입니다. XML은 tests=1/failures=1/errors=0/skipped=0이며 익명 요청에서 `Optional[public]`가 `no-store`를 포함하지 않는다는 정확한 assertion으로 실패했습니다. 이 변형은 commit에 포함하지 않았습니다. 기능 구현 전 RED 증거는 Task 1–4 작업별 기록에 있으며 Task 5에서 기능 부재로 실패했다고 주장하지 않습니다.

## focused·전체 실행

명령은 이 worktree의 `reference-app/backend`에서 실행했습니다.

```powershell
.\gradlew.bat test --tests '*AppUserAdminHttpIntegrationTest' --console=plain
.\gradlew.bat test --console=plain
```

- 변형 복원 후 focused: exit 0, `BUILD SUCCESSFUL in 22s`, XML 1 suite, tests=8, failures=0, errors=0, skipped=0입니다.
- 전체: exit 0, `BUILD SUCCESSFUL in 56s`, XML 41 suites, tests=383, failures=0, errors=0, skipped=0입니다.

전체 회귀는 focused 안정화 후 한 번 실행했습니다. 수치는 해당 실행의 `build/test-results/test/TEST-*.xml` tests/failures/errors/skipped 합계입니다.

## 경쟁·HTTP 결과

- 익명 401, APP_USER 403, COMPANY_ADMIN HR snapshot만 가진 APP_USER 403, 로컬 APP_ADMIN 200과 모두 no-store를 확인했습니다.
- 실제 관리 API로 승격 후 다음 관리 요청 200, 회수 후 403, 비활성화 후 profile 401 및 session 익명 200을 확인했습니다. 다른 활성 관리자를 API로 만든 뒤 자기 강등/비활성화 PUT 자체는 200이고 다음 요청부터 반영됨을 별도 테스트했습니다.
- 네 API의 목록·상세 JSON 키, 상세에만 externalIdentity, no-op 보존, version 증가, 필드 보존, 400/404와 두 종류 409, 안전한 Problem Details와 토큰 미노출을 확인했습니다.
- CSRF 누락과 잘못된 Origin은 403이고 전체 DB 상태가 같습니다.
- 별도 DB 연결이 singleton을 잠근 채 올바른 PUT을 실행하면 MVC 서비스의 실제 lock timeout이 503 SERVICE_UNAVAILABLE로 반환됩니다. 상태·역할·version·bootstrap을 포함한 DB 전체 상태가 보존되며 finally에서 잠금을 풀고 같은 version의 재요청은 200입니다. 필터 DB 실패를 MVC 오류와 혼동하지 않습니다.
- 짧은 Access Token을 가진 대상의 세션 조회 전에 관리자 상세 version을 얻었습니다. 관리 API 승격 뒤 대상의 실제 profile 요청으로 snapshot을 갱신하고 이전 version의 회수 PUT은 409로 거절했습니다. 새 snapshot과 APP_ADMIN은 보존됩니다.
- 전체 suite의 기존 실제 DB 경쟁 테스트는 동시 강등/비활성화, cached actor 권한 상실, 부여·활성화와 회수 경합, JIT/bootstrap과 공통 잠금, snapshot 갱신 경합, 저장 실패 롤백, DB timeout 설정 복원을 포함합니다. 로그인/갱신/로그아웃 회귀도 포함됩니다.

## 경고와 미실행 범위

OpenJDK CDS 경고, Byte Buddy agent 경고, spring.jpa.open-in-view 기본값 경고, Gradle configuration-cache 제안이 있습니다. 의도한 DB rollback·constraint·잠금 제한 사례의 SQL WARN/ERROR와 405/415 요청 경고가 있어 출력이 깨끗하다고 표현하지 않습니다. 테스트 실패와 구별합니다.

IdP 코드·DB·의존성은 바꾸지 않아 IdP suite는 반복하지 않았습니다. 실제 SPA 브라우저 E2E는 실행하지 않았습니다. 사용자/관리자 SPA와 최상위 브라우저 흐름은 Task 8–10의 후속 범위입니다. merge와 push도 실행하지 않았습니다.

## 독립 review 결과

Task 1–5 작업별 독립 검토를 완료했습니다. Task 4의 실제 HTTP 보안 유보 항목은 Task 5 검토에서 해소했습니다. 전체 변경 검토에서 발견한 트랜잭션 시작 실패의 503 누락(P2)과 identity 조회 경로 문서 오류(P3)는 436e5f0에서 수정했습니다. 독립된 수정 범위 재검토에서 두 항목 모두 ADDRESSED, 새로운 결함 없음으로 확인했습니다. 미해결 Critical/Important 항목은 없습니다.

## 최종 검토 지적 수정과 재검증

P2: 관리자 MVC advice가 `CannotCreateTransactionException`도 503 SERVICE_UNAVAILABLE로 변환하도록 구체적인 예외 클래스만 추가했습니다. 기존 안전한 Problem Details와 no-store를 재사용하며 TransactionException·RuntimeException 전체를 잡지 않습니다. 일반 프로그래밍 오류 전파 테스트를 그대로 유지했습니다.

P3: 위 구현 commit 설명을 바로잡았습니다. UUID의 find 후 locking refresh와 identity의 findLocked(issuer, subject) 후 locking refresh는 별도 경로입니다. adapter 자체는 이번 수정에서 바꾸지 않았습니다.

- RED: `.\gradlew.bat test --tests '*AppUserAdminControllerTest.transactionCreationFailureReturnsSafeUnavailableProblem' --console=plain` — exit 1, `BUILD FAILED in 4s`, 1 test/1 failure입니다. 수정 전 CannotCreateTransactionException이 ServletException으로 전파되어 의도한 503 응답 검증에 실패했습니다.
- GREEN: `.\gradlew.bat test --tests '*AppUserAdminControllerTest' --tests '*AppUserAdminHttpIntegrationTest' --console=plain` — exit 0, `BUILD SUCCESSFUL in 25s`, XML 2 suites, tests=24, failures=0, errors=0, skipped=0입니다. 새 사례는 하위 SQLException의 비밀 문자열을 가진 트랜잭션 생성 예외에 대해 응답 키·code·content type·query 없는 instance·no-store·비밀 미노출을 검증합니다. 실제 연결 고갈 재현이 아닌 MVC 오류 매핑 테스트입니다.
- 수정 후 전체: `.\gradlew.bat test --console=plain` — exit 0, `BUILD SUCCESSFUL in 55s`, XML 41 suites, tests=384, failures=0, errors=0, skipped=0입니다. production 오류 처리 수정 후 focused 안정화 뒤 전체 suite를 한 번 실행했습니다. 기존 경고·미실행 범위는 위와 같으며 수정 범위 독립 재검토도 통과했습니다.
