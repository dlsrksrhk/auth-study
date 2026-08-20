# Task 7 Fix Round 2 Report

## Status

- COMPLETE
- 커밋: `fix: restore business code normalization` (본 보고서를 포함하는 새 커밋)

## Files

- 수정: `shared/validation/BusinessCode` — trim 후 blank/길이/path-safe 정규식을 검증하고 uppercase 반환
- 수정: `shared/validation/ValidCode` 및 추가 `BusinessCodeValidator` — presentation 검증을 application과 동일한 trim 의미로 통일
- 수정: `DepartmentRequests` — nullable parent code도 동일 validator 의미 사용
- 복원: Company/Position/Department/User application 통합 테스트의 앞뒤 whitespace normalization fixture
- 수정: 관리자 HTTP 계약 테스트 — whitespace company code의 canonical 응답/Location, trim 후 최대 길이, unsafe code 무커밋 검증

## RED

```powershell
cd backend
.\gradlew.bat test --tests "*CompanyServiceIntegrationTest" --tests "*DepartmentTreeIntegrationTest" --tests "*UserCreationIntegrationTest" --tests "*AdminApiContractIntegrationTest.surrounding_whitespace_is_trimmed_before_code_validation_and_location_creation"
```

- 결과: 24 tests, 8 failures
- application은 원본 문자열을 정규식 검사해 기존 Company/Position/Department/User whitespace fixture 7건을 거부했습니다.
- presentation의 합성 `@Size/@Pattern`은 whitespace company create를 400으로 거부해 canonical code/Location 계약 1건이 실패했습니다.

## GREEN

- `BusinessCode.isValid`가 원본을 trim한 결과에 대해 non-blank, 최대 50자, `[A-Za-z0-9][A-Za-z0-9_-]*`를 검사합니다.
- `BusinessCode.normalize`는 같은 검증 후 trim 결과를 uppercase로 반환합니다.
- `@ValidCode` custom validator가 같은 함수를 사용하며 nullable optional code만 null을 허용합니다.
- HTTP `" c-..._1 "` 회사 생성은 canonical uppercase code와 `/api/v1/admin/companies/C-..._1` Location을 반환합니다.
- trim 결과 50자는 허용하고 51자, slash, `?`, `#`, 내부 공백은 400으로 거부하며 DB에 저장하지 않습니다.
- 집중 관리자 계약:

```powershell
.\gradlew.bat test --tests "*TenantAuthorizationIntegrationTest" --tests "*AdminApiContractIntegrationTest" --tests "*AdminResourceContractIntegrationTest"
```

- 결과: 30 tests, 0 failures

## Full tests

```powershell
.\gradlew.bat cleanTest test
```

- 결과: `BUILD SUCCESSFUL in 1m 11s`
- 17 suites, 99 tests, 0 failures, 0 errors, 0 skipped
- `git diff --check`: PASS (LF→CRLF 안내만 존재)
- 독립 read-only 리뷰: Critical 0, Important 0, Ready Yes

## Remaining concerns

- 역할 변경의 Refresh Token account-wide 폐기와 감사 로그는 계획대로 Task 8 범위에 유지했습니다.
- 이번 수정은 business code normalization 계약에만 한정했으며 endpoint/interface를 추가하지 않았습니다.
