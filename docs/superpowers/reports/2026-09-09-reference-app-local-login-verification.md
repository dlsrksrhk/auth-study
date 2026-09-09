# Reference App Task 5 구현·검증 기록

작성일: 2026-09-09

설계: [OIDC 로그인과 앱 내부 사용자 연결](../specs/2026-09-09-reference-app-local-login-design.md)

계획: [구현 계획](../plans/2026-09-09-reference-app-local-login.md)

## 구현 결과

표준 Spring OIDC 처리와 세션 토큰 저장을 유지하면서 로컬 사용자 생성·갱신과 최초 관리자 지정을 연결했습니다. 비활성 사용자 로그인은 DB 변경을 롤백하고 전용 오류로 안내하며, 그 밖의 로그인 실패는 일반 오류로 통일합니다.

인증된 BFF 요청은 DB의 현재 사용자 상태와 역할을 읽습니다. 비활성·삭제 사용자는 세션을 폐기하고, 활성 사용자는 요청별 인증 객체에 현재 로컬 권한을 반영합니다. DB 조회 장애에서는 이전 세션 권한으로 보호된 요청을 실행하지 않습니다.

`GET /bff/session`과 `GET /bff/profile`은 명시적 응답 DTO만 반환합니다. 익명 세션 조회는 새 세션을 만들지 않습니다. 인증 세션 응답은 마스킹된 CSRF 토큰을 포함하며 OAuth 토큰과 내부 identity는 포함하지 않습니다.

## 작업 위치와 커밋

- 작업 브랜치: `codex/reference-app-local-login`
- 작업 공간: `C:/dev/auth-study/.worktrees/reference-app-local-login`
- 구현 시작점: `946c1ae` (승인된 구현 계획)
- 설계 커밋: `c1ec443`
- 원래 `main`의 `frontend/.idea/`는 보존했습니다. merge·push는 실행하지 않았습니다.

| 구현 단위 | 커밋 | 검증 |
|---|---|---|
| UserInfo 변환 | `b07de2a` | 집중 테스트 20개 |
| 로그인 트랜잭션·읽기 전용 조회 | `d235476` | 집중 테스트 6개와 기존 회귀 33개 |
| OIDC principal·실패 처리 연결 | `20ceb65` | 서비스·프로토콜·보안 68개 |
| 요청별 현재 사용자 확인 | `6635bde` | 필터·프로토콜·보안 70개 |
| Session/Profile API | `bf7a7f7` | API·프로토콜·보안 51개 |
| 실제 HTTP·PostgreSQL 통합 | `92eaf36` | 통합 테스트 18개와 전체 테스트 200개 |
| 세션 수립·응답 실패 정리 보완 | `9a7f48d` | 실패 경로 9개 추가, 전체 테스트 209개 |

위 집중 실행에는 중복되는 기존 테스트가 포함되어 있으므로 행의 개수를 합산하지 않습니다. 시작점 전체 테스트는 129개였으며 최종 전체 테스트는 209개입니다.

## 실행 결과

작업 디렉터리: `reference-app/backend`.

```powershell
./gradlew.bat test --tests '*OidcLocalLoginIntegrationTest' --tests '*LocalSessionLifecycleIntegrationTest'
./gradlew.bat clean test --console=plain
```

- 원복 후 실제 OIDC HTTP·PostgreSQL 통합: 18 tests, 0 failures, 0 errors, 0 skipped.
- 최종 fresh XML 25개 suite 집계: **209 tests, 0 failures, 0 errors, 0 skipped**.
- 최종 전체 실행: `BUILD SUCCESSFUL`, 39초, 6 actionable tasks executed.
- 테스트 실행 기록과 별도로 이번 작업 공간의 생성된 XML을 직접 집계하여 결과를 확인했습니다.
- `git diff --check`와 단계별 `git diff --cached --check` 통과.
- production의 IdP 패키지 import·로컬 IdP DB URL 검색 및 domain의 Spring/JPA 의존 검색: 일치 없음.
- 기존 JVM CDS 경고와 테스트의 동일 타입 Optional 연속 반환에 대한 unchecked varargs 안내가 있습니다. 검토에서 기능상 문제는 발견하지 않았습니다.

## 실패 테스트와 결함 감지 증거

구현 단위마다 클래스 또는 API 부재로 실패하는 테스트를 먼저 실행했습니다. UserInfo 경계 사례는 최소 구현에서 13건 실패한 뒤 검증 구현 후 통과했습니다. OIDC HTTP 단계는 비활성 사용자의 오류 redirect가 일반 오류로 처리되는 실패를 확인했고, API 단계는 실제 HTTP 404 실패를 확인했습니다. 테스트 작성 오류와 fixture 의존성 오류는 기능 RED 증거에서 제외하고 수정했습니다.

최종 통합 테스트는 이미 구현된 기능에 처음부터 통과할 수 있으므로 characterization으로 기록했습니다. 다음 두 임시 변경으로 핵심 테스트의 민감도를 확인했습니다.

| 임시 변경 | 테스트 | 실제 실패 |
|---|---|---|
| 요청 인가에 현재 DB 역할 대신 로그인 당시 역할 사용 | `LocalSessionLifecycleIntegrationTest.revokingAdministratorFromLoginPrincipalDeniesNextRequest` | 관리자 회수 후 expected 403, actual 200 |
| 로그인 서비스의 ACTIVE 검사 제거 | `OidcLocalLoginIntegrationTest.disabledLoginRollsBackSnapshotTimesRolesAndBootstrapAndDiscardsOldSession` | expected `local_user_disabled` 오류 redirect, actual SPA 성공 redirect |

두 변경 모두 실제 애플리케이션·DB 실행 후 해당 assertion으로 실패했습니다. `apply_patch`로 즉시 원복했으며, 원복된 코드로 focused 18개와 당시 전체 200개가 통과했습니다. 테스트 민감도 확인을 위한 production 변경은 최종 커밋에 남기지 않았습니다.

최종 검토에서 발견한 세션 수립 실패 경로는 신규 테스트 8개 중 7개가 실패하는 것으로 재현했습니다. 실제 DB 커밋과 인증 저장 이후 예외를 주입하면 expected 302 / actual 500이었으며, I/O·이미 커밋된 응답의 실패에서는 세션이 무효화되지 않았습니다. 수정 후 이 테스트가 통과했습니다. 실패 redirect 자체가 실패했을 때 재시도하지 않는 추가 테스트 1개도 보호 조건을 임시로 제거하면 실패하는 것을 확인했습니다. 최종 원복 코드 전체 209개가 통과했습니다.

## 설계 인수 기준 연결

| 설계 검증 항목 | 검증 위치 |
|---|---|
| 정상 callback, UserInfo 1회, 세션 교체, 사용자 생성 | `OidcLocalLoginIntegrationTest` |
| 재로그인 identity·역할 보존, 최초 관리자 지정 | 위 통합 테스트와 기존 bootstrap 회귀 |
| UserInfo sub·타입·중첩 필드 검증과 DB 무변경 | mapper/service 단위 테스트 및 실제 callback 통합 |
| 비활성 로그인 시 snapshot·시각·역할·bootstrap rollback | `AppLocalLoginIntegrationTest`, `OidcLocalLoginIntegrationTest` |
| PostgreSQL 저장 실패와 세션 정리 | 실제 DB trigger 오류를 사용하는 callback 통합 |
| 삭제·비활성 사용자 재접근 차단 | `LocalSessionLifecycleIntegrationTest` |
| 다음 요청의 역할 부여·회수 및 권한 endpoint 응답 | 위 수명주기 통합 테스트 |
| legacy 인증 거절, 외부 역할의 로컬 권한 미부여 | 필터 테스트와 실제 외부 관리자 로그인 사례 |
| 요청별 읽기·공유 인증 불변·DB 장애 차단 | `CurrentAppUserIntegrationTest`, `CurrentAppUserFilterTest`, 수명주기 통합 |
| DTO allowlist·null·정렬·no-store·토큰 값 미노출 | `BffUserApiIntegrationTest` 및 실제 DB 통합 |
| 실제 CSRF 토큰 사용·이전 토큰 폐기·Origin 검증 | API 통합, 기존 BFF 보안, 실제 DB 통합 |
| DB 커밋 후 세션 저장·redirect 실패의 세션 무효화 | `OidcSessionEstablishmentIntegrationTest`, `OidcCallbackGuardFailureTest` |
| 프로토콜·JIT·bootstrap 전체 회귀 | `clean test` 209개 |

## 계획에서 구체화한 사항

- 기존 DB 없는 HTTP fixture에는 로컬 DB 서비스 대역을 제공하되 실제 mapper·OIDC 서비스·보안 필터는 유지했습니다. 실제 PostgreSQL 통합은 별도 fixture에서 서비스 대역 없이 수행했습니다.
- `ReferenceSecurityPropertiesTest`의 독립 쿠키 테스트 애플리케이션에도 새 서비스 의존성을 제공했습니다. 기존 cookie·host 보안 assertions는 유지했습니다.
- 테스트 순서에 따라 JDK HttpClient의 Host 헤더 허용 설정이 늦게 적용되는 기존 fixture 문제를 테스트 전용 초기화로 보완했습니다.
- UserInfo 호출 계수와 오류 fixture 확장 일부는 최종 통합 단계보다 앞선 OIDC 연결 단계에서 추가하여 그 단계의 HTTP 동작부터 검증했습니다.
- 새로운 관리 API·DB 스키마·별도 OAuth 토큰 저장 객체는 추가하지 않았습니다.

## 검토 및 남은 범위

각 구현 단위의 독립 검토는 설계 준수와 코드 품질 모두 통과했습니다. 전체 브랜치 검토에서 세션 저장·성공 응답 예외가 정리 경로를 우회하는 P2 한 건을 발견하여 `9a7f48d`에서 보완했습니다. 수정 재검토에서 해당 항목의 해결을 확인했으며, 새 지적 사항 없이 검토를 통과했습니다.

DB 커밋 이후 세션 수립이 실패하면 이미 커밋된 사용자·bootstrap은 유지하고 서버의 인증 세션을 폐기합니다. 응답이 이미 커밋되었거나 연결이 끊긴 경우 쿠키 만료 전달은 보장할 수 없지만 서버 세션은 무효화합니다. 수정은 콜백 범위에 한정하며 다른 API의 예외를 가로채지 않습니다.

실제 외부 IdP와 Chromium 브라우저를 연결한 E2E는 실행하지 않았습니다. 토큰 갱신·revocation·logout은 Task 6, 관리자 API는 Task 7, SPA와 실제 브라우저 E2E는 후속 Task 8–10의 범위입니다.
