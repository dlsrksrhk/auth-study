# Task 7 Report — ActorContext, 회사 격리와 관리자 API

## Status

- 완료
- 커밋: `feat: expose tenant-safe admin APIs` (이 보고서를 포함하는 Task 7 커밋)

## Files

- 추가: `shared/security`의 `ActorContext`, `SpringSecurityActorContext`, `TenantGuard`
- 추가: 회사/직위/부서/사용자/소속 `presentation` Controller와 request DTO
- 추가: 공통 `PageResponse`, `PageRules`
- 수정: 모든 HR application service 공개 mutation/read 경계에 `AuthenticatedAccount actor`와 서비스 측 tenant/role 재검증
- 수정: 사용자 검색용 company-scoped parameter-bound JPA query와 pagination
- 수정: 회사/사용자 repository 조회 기능, 사용자/부서 update 도메인 동작, 관리자 역할용 `AccountService`
- 수정: malformed JSON/enum/query validation 오류를 공통 `VALIDATION_FAILED` Problem Details로 매핑
- 추가: `TenantAuthorizationIntegrationTest`, `AdminApiContractIntegrationTest`, 기존 서비스 통합 테스트 actor 마이그레이션

## RED

1. 관리자 Controller가 없는 상태에서 지정 통합 테스트 4건이 404로 실패함을 확인했습니다.
2. `page=Integer.MAX_VALUE`에서 `page * size` 정수 overflow로 `IndexOutOfBoundsException`이 발생하는 실패를 확인했습니다.
3. 잘못된 enum JSON이 공통 오류 계약의 `code` 없이 400으로 처리되는 실패를 확인했습니다.

## GREEN

- 지정 테스트: `gradlew.bat test --tests "*TenantAuthorizationIntegrationTest" --tests "*AdminApiContractIntegrationTest"`
- 결과: 10 tests, 0 failures
- 검증 범위: principal 타입 제한, 회사 간 403, 비활성 회사 403, 관리자 역할 권한, 201+Location, page metadata, validation 400, absent 404, duplicate/stale 409, parameter-bound SQL 형태 검색어, 비밀번호 비노출, 큰 page 경계

## Full tests

- 명령: `backend\\gradlew.bat test`
- 결과: `BUILD SUCCESSFUL`, 79 tests, 0 failures, 0 errors, 0 skipped
- 기존 회사 authoritative lock 및 조직/소속 동시성 테스트를 포함한 전체 회귀가 통과했습니다.

## Concerns

- 역할 변경 및 임시 비밀번호 재설정 시 활성 Refresh Token account-wide 폐기와 감사 로그 연계는 계획대로 Task 8 범위에 남겼습니다. 이번 Task에서는 관련 interface/행동을 선행 변경하지 않았습니다.
- 감사 로그 `/api/v1/admin/companies/{companyCode}/audit-logs` 역시 Task 8의 `AuditAdminController` 범위라 이번 커밋에 추가하지 않았습니다.
