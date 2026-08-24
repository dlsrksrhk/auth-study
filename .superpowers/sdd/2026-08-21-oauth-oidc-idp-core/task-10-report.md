# Task 10 구현 보고서

## 상태

DONE

BASE는 `142aeb2d512cf785aeaef8233f13f76e4cac8719`입니다. 구현 및 이 보고서의 commit hash는
상위 agent handoff에 함께 기록합니다.

## 구현 범위

- OAuth application 경계
  - `OAuthUserInfoClaimSource`는 authorization/access token/client/opaque subject/account/company/user와
    position/membership을 immutable typed snapshot으로 제공합니다. HR JPA entity나 HR JWT를 application으로
    노출하지 않습니다.
  - `OAuthUserInfoService`가 scope matrix와 현재 상태 정책을 소유합니다. 매 호출마다 account ACTIVE,
    active lock 없음(`now == lockedUntil`은 허용), `mustChangePassword=false`, user/company/client ACTIVE,
    authorization/access token ACTIVE·미폐기·미만료를 검사합니다.
  - persisted authorization/access-token/account/client/company/user/subject 소유 관계와 실제 bearer JWT의
    `sub`, `client_id`, `aud`, signed scope, SAS authorization/access-token scope를 exact 비교합니다.
    ID metadata subject도 같은 persisted opaque `OAuthSubject`와 일치해야 하며 numeric account id를 `sub`로
    사용하지 않습니다. SYSTEM_ADMIN/no-company/no-user는 항상 거부합니다.
- current HR snapshot
  - `HrOAuthUserInfoClaimSource`는 raw bearer의 SHA-256 hash로 access token을 찾고 각 domain repository/port를
    `REPEATABLE_READ`, read-only transaction에서 다시 조회합니다. 상태 판단과 공개 claim 선택은 application
    service에 남기고 infrastructure는 typed snapshot만 조립합니다.
  - organization은 현재 ended membership을 제외하고 active department 및 같은 company/user 소유만 사용합니다.
    position/primary는 optional, secondary는 code/name 순으로 deterministic 정렬하며 중복을 제거합니다.
- exact UserInfo claim matrix
  - `openid`: `sub`
  - `profile`: `name`, `preferred_username`(HR user code)
  - `email`: non-null일 때만 `email`, 항상 `email_verified=false`
  - `hr.company`: `https://auth-study.local/claims/company` → `{code,name}`
  - `hr.organization`: `https://auth-study.local/claims/organization` → optional `position`, optional
    `primary_department`, 항상 `secondary_departments`; 모든 node는 code/name만 사용합니다.
  - `hr.roles`: `https://auth-study.local/claims/roles` → 정렬된 role name list
  - protocol adapter에서만 insertion-ordered mutable map을 만들며 null, password/lock/must-change,
    employee number 및 내부 numeric id를 넣지 않습니다. ID/access token의 기존 exact allowlist에는 HR claim을
    추가하지 않았습니다.
- SAS 1.5.8 연결
  - `OidcUserInfoEndpointConfigurer.userInfoMapper(...)`에 `OidcUserInfoMapper`를 연결했습니다.
  - project authorization persistence는 raw ID token을 보존하지 않으므로 access-token lookup 재구성 때
    emitted token이 아닌 최소 typed `OidcIdToken` metadata를 current persisted opaque subject로 복원합니다.
    mapper는 이것을 실제 bearer JWT subject와 exact 비교합니다.
  - authorization-server chain에 OAuth RS256 decoder를 명시해 application API의 `@Primary` HR HS256 decoder와
    격리했습니다. 모든 bearer/mapper 실패는 oracle 없는 `401`, exact
    `WWW-Authenticate: Bearer error="invalid_token"`, `{"error":"invalid_token"}`로 응답합니다.

Task 11 refresh/revocation lifecycle과 Task 12 event/CORS는 구현하지 않았습니다. Task 9 browser Origin/token
semantics와 Task 8 ID/access claim allowlist를 유지했습니다.

## 엄격 TDD 증거

### 최초 unit RED

Production 구현 전에 `OAuthUserInfoServiceTest`를 작성했습니다.

Command:

`./gradlew.bat test --tests "*OAuthUserInfoServiceTest" --console=plain`

Result:

- `compileTestJava FAILED`
- 77 compiler errors
- `OAuthUserInfoClaimSource`, `OAuthUserInfoService`, `OAuthUserInfoView`가 없어서 실패했습니다.

그 뒤 typed port/view/service의 최소 구현으로 같은 command가 `BUILD SUCCESSFUL in 6s`가 되었습니다.

### 실제 HTTP RED → GREEN

Production endpoint adapter 전에 Discovery의 UserInfo URL을 사용해 실제
authorize→authorization code→public PKCE token→bearer UserInfo flow를 작성했습니다.

Command:

`./gradlew.bat test --tests "*OidcUserInfoIntegrationTest.real_authorize_token_and_discovered_userinfo_publish_only_granted_claims" --console=plain`

RED progression:

1. claim source bean 부재로 application context가 실패했습니다.
2. source 구현 뒤 7 scope case 모두 UserInfo가 401이었습니다. 실제 response의 첫 원인은
   `Signed JWT rejected: Another algorithm expected`였고, OIDC resource-server가 `@Primary` HR HS256 decoder를
   선택한 것이었습니다.
3. OAuth RS256 decoder 지정 뒤 endpoint의 generic `401 invalid_token`까지 도달했습니다. SAS가 재구성된
   authorization에 `OidcIdToken`을 요구하므로 최소 ID metadata를 추가했고, mapper가 wrapper metadata map이
   아니라 typed `OidcIdToken.getSubject()`를 읽도록 연결했습니다.
4. 이후 7 case 모두 HTTP 200이었고, 테스트의 numeric-id substring assertion이 UUID의 한 자리 숫자를 내부
   id로 오인했습니다. scalar exact value와 numeric node 부재를 검사하도록 테스트 의도를 바로잡았습니다.

GREEN:

- openid 단독, 각 profile/email/company/organization/roles 조합, 전체 scope의 7 case가 통과했습니다.
- ID/access/UserInfo `sub`가 같은 opaque UUID이고 account numeric id와 다름을 확인했습니다.
- ID/access token exact claim allowlist와 HR claim 부재, ungranted omission, organization shape/order, role order,
  employee/internal id 및 민감 field 부재를 확인했습니다.

### current-state/ownership mutation RED → GREEN

실제 token 발급 뒤 account lock/must-change/status, user/company/client status, authorization/token
revocation·expiry, authorization client/subject/account, access-token authorization ownership을 DB에서 변경한 뒤
동일 bearer를 다시 호출했습니다.

초기 expiry fixture는 DB time-order CHECK보다 과거 값을 써서 `DataIntegrityViolationException`으로 실패했습니다.
`created_at/issued_at + 1ms`로 DB invariant를 지키면서 현재 시각에는 만료된 값을 사용하도록 바로잡았습니다.
그 뒤 모든 mutation이 동일한 exact 401 invalid_token 계약으로 GREEN이었습니다.

## GREEN / 검증 증거

Task 10 focused:

`./gradlew.bat test --tests "*OAuthUserInfoServiceTest" --tests "*OidcUserInfoIntegrationTest" --console=plain`

- `BUILD SUCCESSFUL in 26s`
- JUnit XML: 46 tests, 0 failures, 0 errors, 0 skipped

Authorization persistence/SAS/Discovery/PKCE relevant regression:

`./gradlew.bat test --tests "*OAuthAuthorizationMapperTest" --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*OidcDiscoveryAndTokenContractIntegrationTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*AuthorizationServerSecurityConfigTest" --console=plain`

- `BUILD SUCCESSFUL in 47s`
- JUnit XML: 95 tests, 0 failures, 0 errors, 0 skipped

Full backend:

`./gradlew.bat test --console=plain`

- `BUILD SUCCESSFUL in 3m 36s`
- JUnit XML: 386 tests, 0 failures, 0 errors, 0 skipped

Static verification:

- `git diff --check`: whitespace error 0건; Windows LF→CRLF checkout warning만 출력
- `progress.md`는 수정하지 않았습니다.

## self-review

- OAuth application service가 scope matrix와 모든 상태/소유권 결정을 담당합니다. infrastructure source는 raw
  bearer lookup과 domain→typed snapshot 변환만 하고 JPA entity/HR JWT를 application에 노출하지 않습니다.
- current-state 검사는 source snapshot을 한 transaction에서 매 요청 새로 읽고, service의 injected `Clock`으로
  expiry와 lock을 판단합니다. `now.isBefore(deadline)`만 active라 authorization/token exact expiry는 거부하고,
  lock은 `now.isBefore(lockedUntil)`일 때만 active라 exact boundary는 허용합니다.
- source authorization subject, current `OAuthSubject`, emitted ID metadata subject, decoded bearer subject의 네 값을
  exact 비교합니다. authorization/access-token/client/account/company/user의 내부 ownership도 모두 연결됩니다.
- claims는 granted authorization/access-token scope의 exact equality 검증 뒤 application service에서만 선택합니다.
  mapper는 typed optional을 non-null protocol claim으로 옮기므로 ungranted/null claim이 새어 나오지 않습니다.
- company/organization nested object와 roles의 ordering이 deterministic이고, organization/roles에는 code/name 및
  role name 외 값을 넣지 않습니다. HTTP test가 recursive field/value 검사를 수행합니다.
- mapper와 resource-server failure handler는 내부 예외 설명이나 entity/status 차이를 제거해 동일한 표준
  invalid_token body/header/status만 반환합니다.
- persisted raw ID token을 새로 저장하거나 numeric principal을 `sub`로 대체하지 않았습니다. 재구성 metadata는
  UserInfo provider가 요구하는 in-memory protocol object일 뿐 response/persistence에는 노출되지 않습니다.

## 우려와 의도적 경계

- `email`과 position은 현재 DB schema에서 company user에 대해 필수지만 typed port/view/mapper는 optional을
  안전하게 처리합니다. unit test가 empty email/position/primary를 고정합니다.
- organization은 현재 domain 의미에 맞게 `endedAt`이 없는 membership만 active로 취급합니다.
- UserInfo의 RS256 decoder 명시는 OIDC chain에만 적용되며 기존 `/api/v1/**` HR HS256 token chain을 변경하지
  않습니다.
- refresh token rotation/revocation endpoint, protocol audit event, public-RP CORS는 후속 Task 11/12 범위입니다.
