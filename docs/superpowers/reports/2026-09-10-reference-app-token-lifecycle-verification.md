# Reference App 토큰 수명·로그아웃 검증 기록

검증일: 2026-09-10 (Asia/Seoul). 브랜치: `codex/reference-app-token-lifecycle`.

승인된 [설계](../specs/2026-09-10-reference-app-token-lifecycle-design.md)와 [세부 계획](../plans/2026-09-10-reference-app-token-lifecycle.md)의 Tasks 1–6를 구현했습니다. 실제 내장 HTTP 서버와 PostgreSQL Testcontainers를 사용한 Reference App 전체 테스트가 통과했습니다. 병합·push와 실제 브라우저 E2E는 수행하지 않았습니다.

## 실행 명령과 측정 결과

명령은 표의 작업 디렉터리에서 PowerShell로 실행했습니다. 수치는 Gradle의 추정치나 이전 Task 5 수치가 아닌 `build/test-results/test/TEST-*.xml`의 tests/failures/errors/skipped 속성을 합산했습니다.

| 작업 디렉터리 | 명령 | Exit | tests / failures / errors / skipped | 결과 |
| --- | --- | --- | --- | --- |
| `reference-app/backend` | `.\gradlew.bat test --tests '*ReferenceLogoutIntegrationTest.fullLogoutReturnsOpaqueOneUseContinuationAndFixedRedirect' --console=plain` | 1 | 1 / 1 / 0 / 0 | 절대 BFF origin 단언이 기존 상대 URL에서 실패, 수정 전 RED |
| `reference-app/backend` | `.\gradlew.bat test --tests '*ReferenceLogoutIntegrationTest' --console=plain` | 0 | 5 / 0 / 0 / 0 | 설정 origin 수정 후 GREEN, Host·Forwarded·query 오염 검증 포함 |
| `reference-app/backend` | `.\gradlew.bat test --tests '*TokenLifecycleHttpIntegrationTest' --console=plain` | 0 | 12 / 0 / 0 / 0 | 새 HTTP 경쟁·실패 통합 검증 |
| `reference-app/backend` | `.\gradlew.bat clean test --console=plain` | 0 | 270 / 0 / 0 / 0 | 34 suites, 6 Gradle tasks 모두 실행, 46초 |
| `backend` | `.\gradlew.bat test --console=plain` | 0 | 486 / 0 / 0 / 0 | 61 suites, 5 Gradle tasks 모두 UP-TO-DATE, 12초 |
| 저장소 worktree 루트 | `git diff --check` | 0 | 해당 없음 | whitespace 오류 없음 |

Reference App 최종 XML 작성 시각은 13:20:28 KST입니다. IdP XML은 앞선 Task 5 전체 실행의 13:10:31 KST 결과입니다. 최종 IdP 명령은 입력 변경이 없어서 재실행하지 않았으며, 486개를 이번 명령에서 새로 실행했다고 주장하지 않습니다. 이번 Task 6에서는 IdP 소스·테스트를 변경하지 않았습니다.

Docker/PostgreSQL 환경 문제는 없었습니다. 초기 절대 URL 실패는 환경 문제가 아닌 assertion 실패였습니다. 전체 빌드에는 기존 CurrentAppUserFilterTest의 unchecked 컴파일 알림과 JVM class-sharing 경고가 있었으나 실패는 없었습니다.

## HTTP·DB 경쟁 검증

모든 신규 사례는 실제 OIDC callback 로그인을 완료한 RP_SESSION으로 실제 BFF HTTP endpoint를 호출합니다. mock issuer는 RSA 서명 ID Token과 초기 20초 Access Token을 발급하며, 갱신 응답의 Access Token 수명은 300초로 유지합니다. 원래 code 교환과 별도로 refresh 카운터를 측정합니다.

| 사례 | 제어 방법 | 확인한 결과 |
| --- | --- | --- |
| 동일 세션 8개 profile 요청 | 요청 시작 latch와 mock token 응답 latch | refresh 1회·UserInfo 증가 1회, 전부 200과 새 외부 이름, 로컬 roles/status/createdAt/lastLoginAt 보존, updatedAt 변경 |
| token 교환 중 앱 logout | issuer가 요청을 수신한 뒤 응답을 보류 | logout 204·쿠키 삭제, 대기 profile 401, 원래/후속 Refresh Token 각각 1회 폐기, 이전 세션 복원 없음 |
| DB 커밋 후 게시 전 logout | 테스트 전용 외부 proxy가 실제 transactional service 반환 후 보류 | 활성 트랜잭션 없음 단언, 별도 JDBC 연결에서 변경값 조회 후 logout, 커밋된 사본 유지·세션 401·후속 토큰 폐기 |
| 전체 작업 deadline 이후 token 도착 | token 응답 latch를 HTTP 실패 응답 확인까지 닫음 | 1500ms 테스트 작업 상한으로 401·쿠키 삭제, 이후 token 응답을 풀면 후속 토큰 폐기, 새 세션 없음 |
| invalid_grant / UserInfo 오류 / numeric·missing·불일치 sub | 실제 mock endpoint의 오류·원본 JSON | 보호 POST 401, 업무 probe 진입 0, cookie 삭제, session 익명 200·profile 401, 재시도 없음 |
| session endpoint 갱신 실패 | token endpoint invalid_grant | 직접 익명 200·cookie 삭제, 후속 보호 API 401 |
| 실제 DB 저장 실패 | 테스트 동안만 PostgreSQL CHECK constraint 추가 | 새 사본 UPDATE 실패·롤백, 기존 DB 전체 상태 보존, 원래/후속 token 폐기, 업무 진입 0 |
| 갱신 제외 및 CSRF/Origin 거절 | 만료 임박 토큰으로 csrf/login/continuation/full logout 및 잘못된 POST | token·UserInfo 증가 없음, 업무 진입 0 |

경쟁 테스트에는 임의 sleep을 넣지 않았습니다. latch와 제한된 Future 대기를 사용하며 실패 시에도 gate를 해제합니다. DB 커밋 이후 gate는 transaction 안의 repository 호출을 멈추는 방식이 아닙니다. 실제 Spring transaction proxy 바깥에 테스트 전용 decorator를 두고, transaction 종료와 다른 연결에서 보이는 커밋을 함께 검증합니다. production 클래스에는 테스트 hook이나 endpoint를 추가하지 않았습니다.

공통 HTTP fixture의 작업 상한은 1500ms, read 상한은 5초로 override하여 deadline 뒤 token 도착을 실제 HTTP로 재현합니다. 기본 운영값 2초 연결·3초 응답·10초 갱신·3초 revocation은 변경하지 않았습니다. 같은 고정 포트의 하위 테스트마다 별도 Spring context를 만들지 않도록 설정과 decorator를 공통 fixture에 두었습니다.

기존 단위/통합 suite는 30초 만료 경계, 세션별 독립 조율, 종료 sentinel, 사본 UUID·identity·ACTIVE 검증, handoff의 60초·1000개 제한과 단일 소비, 원격 revoke 오류·시간 제한에도 로컬 종료, 만료 ID Token의 IdP 현재 세션 결합 및 일반 decoder 만료 거절을 검증합니다. 이 결과를 실제 SPA 브라우저 검증으로 해석하지 않습니다.

## 구현 결정과 근거

1. **문서 경로:** 계획의 `reference-app/README.md`는 존재하지 않아 실제 `reference-app/backend/README.md`를 수정했습니다. 저장소 구조에 맞춘 결정이며 상위 계획에서도 경로를 바로잡았습니다.
2. **bounded worker와 게시 소유권:** 동기 네트워크 I/O와 DB timeout의 합이 전체 10초 상한을 넘을 수 있어 프로토콜·DB 작업만 최대 32개 worker에 배정했습니다. 원래 servlet 스레드가 deadline을 기다리고 request/response를 사용해 게시합니다. worker는 게시 승인 전까지 candidate 폐기 책임을 유지합니다. 대기 상한 이후에도 이미 실행 중인 DB 사본 변경은 커밋될 수 있지만 세션 복구는 허용하지 않습니다.
3. **Apache HTTP transport:** 계획에 있던 JDK client 대신 자동 재시도·redirect를 명시적으로 끄는 Apache HttpClient를 사용했습니다. 이전 drop/body-stall 회귀에서 확인한 단일 시도와 전체 시간 제한 요구가 라이브러리 선택보다 우선이기 때문입니다. 추가 dependency와 HTTP client 종료 관리가 필요하지만 token 교환 재시도를 방지합니다.
4. **종료 sentinel:** `close(existingSession)`은 조율 상태가 없는 기존 live 세션에도 닫힌 상태를 기록합니다. 그렇지 않으면 logout이 mutex를 놓은 뒤 cleaner가 무효화하기 전에 새 refresh가 시작될 수 있습니다. 이 결정은 새 HttpSession을 만들지 않으며 기존 세션 수명 안에서만 최소 상태를 유지합니다.
5. **폐기 단일 소유권:** 세 인자 RpSessionCleaner가 보유 Refresh Token 폐기를 담당합니다. logout 서비스가 중복 폐기하지 않고, 후속 candidate는 token service 또는 coordinator worker가 담당합니다. 로컬 정리 이후 제한된 폐기를 시도하므로 원격 실패로 로그아웃이 되돌아가지 않습니다.
6. **절대 continuation URL:** 승인된 별도 SPA/BFF origin 계약에 맞춰 ReferenceSecurityProperties.bffOrigin으로 절대 URL을 생성했습니다. 요청 Host·Forwarded와 임의 query는 반영하지 않습니다. 회귀 테스트에서 실제 악성 Host를 전송해 고정 origin을 확인했습니다.

## 남은 범위와 운영 한계

실제 외부 IdP와 SPA를 연결한 최상위 브라우저 이동, 브라우저 쿠키 정책, UI의 로그인 표시 제거는 실행하지 않았습니다. SPA 구현은 Task 8–10, 실제 브라우저 E2E는 Task 10의 범위입니다. 분산 세션·Redis·프로세스 재시작 복원·백그라운드 갱신은 포함하지 않습니다.

ticket은 최대 60초 동안 프로세스 메모리에 있으며 서버 재시작이나 재사용·만료는 410입니다. ID Token이 전체 logout 303 Location의 id_token_hint로 URL에 노출되는 예외는 승인된 설계대로 유지합니다. 배포 인프라 로그·APM의 ticket 및 Location 값 제거는 README에 안내했으며 실제 운영 인프라의 로그 설정은 이번 테스트로 검증하지 않았습니다. 이미 업무에 진입한 다른 요청의 DB 작업을 logout이 취소한다는 보장은 없습니다.
