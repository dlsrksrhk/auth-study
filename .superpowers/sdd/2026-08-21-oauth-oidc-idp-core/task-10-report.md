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
    새 `REQUIRES_NEW` + `REPEATABLE_READ`, read-only transaction과 비운 persistence context에서 다시 조회합니다.
    공용 설정의 OpenEntityManagerInView도 끄므로 security lookup이 읽은 entity가 claim source에 재사용되지 않습니다.
    상태 판단과 공개 claim 선택은 application service에 남기고 infrastructure는 typed snapshot만 조립합니다.
  - organization은 현재 ended membership을 제외하고 active department 및 같은 company/user 소유만 사용합니다.
    position/primary는 optional, secondary는 code/name 순으로 deterministic 정렬하며 중복을 제거합니다.
- exact UserInfo claim matrix
  - `openid`: `sub`
  - `profile`: `name`만 제공하며 HR user code/employee number를 노출하지 않음
  - `email`: non-null일 때만 `email`, 항상 `email_verified=false`
  - `hr.company`: `https://auth-study.local/claims/company` → `{code,name}`
  - `hr.organization`: `https://auth-study.local/claims/organization` → optional `position`, optional
    `primary_department`, 항상 `secondary_departments`; 모든 node는 code/name만 사용합니다.
  - `hr.roles`: `https://auth-study.local/claims/roles` → 정렬된 role name list
  - protocol adapter에서만 insertion-ordered mutable map을 만들며 null, password/lock/must-change,
    employee number 및 내부 numeric id를 넣지 않습니다. ID/access token의 기존 exact allowlist에는 HR claim을
    추가하지 않았습니다.
- SAS 1.5.8 연결
  - `OidcUserInfoEndpointConfigurer`에 `OidcUserInfoMapper`와 UserInfo 전용
    `OidcUserInfoAuthenticationProvider`를 연결했습니다.
  - 공용 authorization mapper는 find-by-id/state/code/access/refresh 어떤 재구성에서도 synthetic ID token을
    만들지 않습니다. 실제 OIDC code finalization 때만 raw token 없이 ID token issued/expires evidence를 V8에
    원자적으로 기록합니다. UserInfo 전용 access-token lookup만 evidence와 현재 persisted opaque subject가 모두
    일치할 때 emitted token이 아닌 최소 typed `OidcIdToken` metadata를 메모리에 복원합니다.
  - `OAuthTokenCustomizer`는 SAS의 system `Instant.now()`를 신뢰하지 않고 injected project `Clock`의 한 시각을
    token HTTP request attribute에 고정해 ID/access `iat`를 exact 동일하게 만들고 각각 configured 5분 TTL로
    `exp`를 설정합니다.
  - finalization은 실제 `OidcIdToken` 후보의 opaque subject, exact client audience, ID/access exact `iat`,
    code consume ≤ `iat` ≤ finalization, 최대 30초의 발급→원자적 저장 구간, 각 configured TTL과 authorization
    lifetime을 검증하며 openid가 없으면 후보/evidence도 없어야 합니다.
  - authorization-server chain에 OAuth RS256 decoder를 명시해 application API의 `@Primary` HR HS256 decoder와
    격리했습니다. 모든 bearer/mapper 실패는 oracle 없는 `401`, exact
    `WWW-Authenticate: Bearer error="invalid_token"`, `{"error":"invalid_token"}`로 응답합니다.

Task 11 refresh/revocation lifecycle과 Task 12 event/CORS는 구현하지 않았습니다. Task 9 browser Origin/token
semantics와 Task 8 ID/access claim allowlist를 유지했습니다.

## Review fix round 1/5

### I1 — fresh authoritative state / OSIV

- RED: `spring.jpa.open-in-view`가 false라는 web contract test는 기본값 true 때문에 실패했습니다.
- GREEN: 공용 `application.yaml`에서 OSIV를 끄고 claim source를 `REQUIRES_NEW` + `REPEATABLE_READ`로 분리한 뒤,
  첫 repository read 전에 `EntityManager.clear()`를 호출합니다.
- deterministic HTTP race 두 건은 SAS access lookup 뒤 claim source를 latch로 멈추고, 별도 transaction에서
  authorization revoke 또는 client disable을 commit한 다음 재개합니다. 둘 다 내부 상태 oracle 없이 exact
  `401`, `Bearer error="invalid_token"`, `{"error":"invalid_token"}`을 반환합니다.

### I2 — 실제 ID-token-issued evidence

- RED 1: 공용 mapper의 synthetic ID token을 금지하는 find-by-id/code/access/refresh 테스트가 기존 구현에서
  실패했습니다. 공용 synthetic 복원을 제거하자 실제 UserInfo scope HTTP 7건이 모두 401이 되었습니다.
- RED 2: 실제 `OidcIdToken` 후보를 요구하는 finalization 테스트는 typed candidate가 없어 compile RED였고,
  DB evidence/잘못된 subject·audience·time 검증은 domain/JPA evidence가 없어 compile RED였습니다.
- GREEN: 공용 reconstruction에는 ID token이 없고, 전용 access lookup만 persisted evidence와 current subject가
  일치할 때 최소 metadata를 붙입니다. 실제 authorize→token은 evidence를 기록하며, evidence를 지운 openid
  access token과 openid 없이 발급된 access token은 UserInfo에서 동일한 generic invalid_token으로 거부됩니다.
  state/code/access/refresh/id generic lookup에는 synthetic ID token이 없음을 단위 테스트로 고정했습니다.
- V8은 아직 배포되지 않은 migration이므로 같은 파일을 안전하게 확장했습니다. 저장 값은
  `id_token_issued_at`, `id_token_expires_at`뿐이며 raw ID token 컬럼/값은 없습니다.
- relevant 첫 실행에서 Task 9 고정 `Clock`과 SAS 1.5.8 `JwtGenerator`의 직접 `Instant.now()` 사용 때문에
  `auth_time` SSO 테스트가 token 400으로 RED였습니다. candidate time은 동일 응답의 access token 대비
  0~5초 발급 지연, 동일 TTL, finalization/authorization expiry를 검증하도록 임시로 바꿔 기존 Task 9 의미와
  wrong-time candidate 거부를 GREEN으로 복원했습니다. 이 상대 비교의 한계는 round 2에서 authoritative
  project Clock 정책으로 대체했습니다.

### I3 — 승인된 exact profile matrix

- RED: application/HTTP 테스트를 `profile -> {sub,name}` exact shape와 user code 재귀 비노출로 먼저 바꾸자
  기존 two-field `Profile` 생성자가 compile RED였습니다.
- GREEN: application snapshot에서 HR user code 자체를 제거하고 typed profile과 protocol mapper를 name-only로
  축소했습니다. HTTP 테스트는 모든 scope 단독/조합에서 exact claim set을 확인하며 user code와 employee number,
  numeric/internal ID를 재귀적으로 거부합니다.

## Review fix round 2/5 — authoritative token issuance clock

### 정책

- ID/access 발급 시각의 유일한 기준은 injected project `Clock`입니다. 첫 JWT customization이 request scope에
  한 `Instant`를 기록하고 같은 token 응답의 ID/access가 이를 공유하므로 두 `iat`는 exact 같습니다.
- ID/access `exp`는 각각 그 `iat`에 configured `idTokenTtl`/`accessTokenTtl`을 더한 값이어야 하며 현재 설정은
  둘 다 5분입니다.
- finalization은 각 `iat`가 code consumption보다 이르거나 finalization보다 미래면 거부합니다. 정상적인 서명과
  저장 지연에는 30초의 one-sided window를 허용하고, `iat < finalizedAt - 30s`는 거부합니다. 두 `exp`는
  finalization 이후이면서 authorization lifetime 안이어야 합니다.

### 엄격 TDD RED → GREEN

- 실제 Task 9 authorize→token 테스트가 ID/access `iat == BASE_TIME + 2h`, 두 `exp == iat + 300s`, 기존
  `auth_time == BASE_TIME`을 요구하도록 먼저 바뀌었습니다. 기존 customizer는 SAS system time을 읽어서
  project-clock `iat` assertion이 RED였습니다.
- persistence table test에서 ID/access를 함께 finalization 미래로 이동, consumption 이전으로 이동,
  consumption 이후지만 30초 window 밖으로 이동한 세 경우가 기존 relative-only 검증에서 `FINALIZED`되어
  assertion RED였습니다. consumption 10초 뒤 발급하고 10초 뒤 finalize하는 기존 5초 초과 정상 경우는
  허용됨을 함께 고정했습니다.
- 별도 TTL RED에서 access만 299초, ID만 299초, 둘 다 같은 299초 TTL인 세 경우가 exact configured TTL 검증을
  제거한 상태에서 모두 `FINALIZED`되어 실패했습니다. configured TTL 검증 복원 후 모두 `INVALID`입니다.
- subject/audience/TTL mismatch는 각 단일 차원만 잘못된 fixture로 분리했습니다.

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

Round 2 fresh focused (persistence/token/UserInfo + Task 8/9):

`./gradlew.bat test --rerun-tasks --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OidcDiscoveryAndTokenContractIntegrationTest" --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeySourceTest" --tests "*OidcUserInfoIntegrationTest" --tests "*OAuthUserInfoServiceTest" --tests "*IdpBrowserFlowIntegrationTest" --tests "*OAuthSecurityPropertiesTest" --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 1m 44s`
- JUnit XML: 205 tests, 0 failures, 0 errors, 0 skipped

Round 2 fresh full backend:

`./gradlew.bat test --rerun-tasks --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 4m 3s`
- JUnit XML: 405 tests, 0 failures, 0 errors, 0 skipped

Round 1 fresh focused/relevant (Task 7/8/9, UserInfo, persistence, HR/web, module boundary):

`./gradlew.bat test --rerun-tasks --tests "*OAuthUserInfoServiceTest" --tests "*OidcUserInfoIntegrationTest" --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OidcDiscoveryAndTokenContractIntegrationTest" --tests "*IdpBrowserFlowIntegrationTest" --tests "*SecurityChainIsolationIntegrationTest" --tests "*AuthenticationIntegrationTest" --tests "*ModuleBoundaryTest" --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeySourceTest" --tests "*OAuthSecurityPropertiesTest" --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 2m 4s`
- JUnit XML: 216 tests, 0 failures, 0 errors, 0 skipped

Round 1 fresh full backend (OSIV off):

`./gradlew.bat test --rerun-tasks --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 4m 3s`
- JUnit XML: 399 tests, 0 failures, 0 errors, 0 skipped

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
  실제 발급 evidence가 있는 UserInfo 전용 provider lookup의 in-memory protocol object일 뿐 response/persistence에는
  노출되지 않습니다. 공용 authorization reconstruction은 evidence가 있어도 synthetic ID token을 붙이지 않습니다.
- SAS default claim clock은 customizer의 project-clock allowlist rewrite로 대체됩니다. 같은 request의 ID/access는
  exact 같은 `iat`를 가지며, finalization은 상대 drift가 아니라 consumption/finalization 양쪽 authoritative
  boundary와 configured TTL을 각각 검증합니다.

## 우려와 의도적 경계

- `email`과 position은 현재 DB schema에서 company user에 대해 필수지만 typed port/view/mapper는 optional을
  안전하게 처리합니다. unit test가 empty email/position/primary를 고정합니다.
- organization은 현재 domain 의미에 맞게 `endedAt`이 없는 membership만 active로 취급합니다.
- UserInfo의 RS256 decoder 명시는 OIDC chain에만 적용되며 기존 `/api/v1/**` HR HS256 token chain을 변경하지
  않습니다.
- token finalization window 30초는 실제 signing/serialization/DB commit 지연을 위한 명시적 상한입니다. 이
  구간을 넘긴 응답은 fail-closed `invalid_grant`이고 새 authorization-code 교환이 필요합니다.
- refresh token rotation/revocation endpoint, protocol audit event, public-RP CORS는 후속 Task 11/12 범위입니다.
