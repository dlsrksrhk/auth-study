# Task 7 Fix Round 1 Report

## Status

- COMPLETE
- 커밋: `fix: harden tenant-safe admin APIs` (본 보고서를 포함하는 새 커밋)

## Files

- 추가: `authorization/AdministrativeTargetGuard` — 비 SYSTEM_ADMIN이 관리자 계정을 사용자/소속 mutation 대상으로 삼지 못하도록 공통 차단
- 추가: `shared/validation/BusinessCode`, `ValidCode`와 `shared/presentation/Locations` — presentation/application 이중 code 검증과 path-segment Location 생성
- 추가: `shared/application/PageResult` — repository/JPA page 결과를 application 계층으로 전달
- 추가: `shared/error/ApiProblemFactory`, `SecurityProblemWriter` — MVC와 Spring Security Problem Details 직렬화 통합
- 수정: 회사/직위/부서/소속 repository와 JPA query — parameter-bound search/status filter, DB pagination, whitelist sort와 결정적 보조 정렬
- 수정: 사용자 Account 일괄 조회 — 목록의 행별 Account 조회 제거
- 수정: `SecurityConfig`와 SYSTEM_ADMIN 전용 controller — method security 활성화와 `@PreAuthorize` 적용
- 수정: 사용자/소속 application service — 관리자 대상 mutation 공통 guard 적용
- 수정: 관리자 API request/controller — safe code, page 경계, membership ACTIVE/ENDED 검색 계약, 안전한 Location
- 수정/추가: `TenantAuthorizationIntegrationTest`, `AdminApiContractIntegrationTest`, `AdminResourceContractIntegrationTest` 및 기존 normalization/error slice 회귀 fixture

## RED

1. 최초 집중 계약 명령에서 19 tests 중 9 tests가 실패했습니다. 다른 COMPANY_ADMIN에 대한 사용자 update/status/password reset 및 membership mutation, 큰 page, unsafe code, method security, DB pagination/N+1, stable Problem Details 누락을 재현했습니다.
2. unsafe path variable 계약은 `BAD.CODE`가 공통 validation Problem Details를 반환하지 않아 별도 RED를 확인했습니다.
3. 첫 전체 회귀는 96 tests 중 11 failures였습니다. 7개는 새 whitespace-unsafe code 정책과 기존 trim 허용 fixture의 충돌이었고, 4개는 MVC slice가 새 `ApiProblemFactory`를 import하지 않은 컨텍스트 실패였습니다.
4. 독립 리뷰 후 추가한 authenticated unknown-route/405/415 Problem Details와 동률 page 정렬 계약은 2 tests 중 2 failures로 RED였습니다.
5. 405 `Allow`와 415 `Accept` 보존 assertion은 1 test 중 1 failure로 RED였습니다.

## GREEN

- 공통 대상-role guard를 사용자 update/status/reset과 membership assign/update/end에 적용해 COMPANY_ADMIN 대상 mutation을 403으로 통일했습니다.
- `PageRules`가 `offset + size`의 안전한 상한을 검증하고 모든 관리자 목록에서 지나치게 큰 page를 400으로 반환합니다.
- 모든 business code가 양 계층에서 `[A-Za-z0-9][A-Za-z0-9_-]*`, 최대 50자로 검증되며 Location은 `pathSegment`로 생성됩니다.
- 회사 create/update와 관리자 역할 grant/revoke는 method security, controller guard, application guard를 모두 유지합니다.
- 회사/직위/부서/소속 목록은 JPA `Page` query에서 검색·상태·정렬·페이지 크기를 적용하고, 동일 정렬값에는 code/id 보조 정렬을 사용합니다.
- membership은 사용자/부서 code·name과 사번을 검색하고 ACTIVE/ENDED를 필터링합니다. User 목록 Account는 한 번에 일괄 조회합니다.
- MVC, routing 404/405/415, Security entrypoint/access denied가 ErrorCode별 type/title 및 공통 code/status/detail/traceId/fieldErrors shape를 사용합니다. incoming `X-Trace-Id`, 401 `WWW-Authenticate`, 405 `Allow`, 415 `Accept`를 보존합니다.
- 집중 명령:

```powershell
cd backend
.\gradlew.bat test --tests "*TenantAuthorizationIntegrationTest" --tests "*AdminApiContractIntegrationTest" --tests "*AdminResourceContractIntegrationTest"
```

- 결과: 29 tests, 0 failures

## Full tests

```powershell
cd backend
.\gradlew.bat cleanTest test
```

- 결과: `BUILD SUCCESSFUL in 1m 12s`
- 17 suites, 98 tests, 0 failures, 0 errors, 0 skipped
- 기존 Task 3~6 회사 authoritative lock, 조직 및 소속 동시성 회귀를 포함합니다.
- `git diff --check`: PASS (Windows LF→CRLF 안내만 존재, whitespace error 없음)
- 독립 리뷰: Critical 0. 발견된 framework Problem Details, deterministic pagination, 405/415 표준 헤더 Important 항목을 모두 반영했습니다.

## Remaining concerns

- 역할 변경 시 active Refresh Token account-wide 폐기와 감사 로그 연계는 계획대로 Task 8 범위로 남겼고 이번 interface/행동에는 추가하지 않았습니다.
- 감사 로그 endpoint와 UI 등 Task 7 지정 범위 밖 기능은 추가하지 않았습니다.
