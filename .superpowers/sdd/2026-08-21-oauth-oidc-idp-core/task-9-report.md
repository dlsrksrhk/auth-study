# Task 9 구현 보고서

## 상태

DONE

BASE는 `2fbd91ce83c7dad490c720149e9500d1a26e56dd`입니다. 구현 및 이 보고서의 commit hash는
상위 agent handoff에 함께 기록합니다.

## 구현 범위

- authorize request 보존과 로그인
  - Task 1의 `IdpLoginController`와 `login.html` 경계를 그대로 확장했습니다.
  - 로그인 전 세션에는 검증된 active client의 내부/public id, exact registered redirect, RP state,
    nonce, requested scopes, S256 challenge/method, 재구성한 local authorize URI만 보존합니다.
    verifier/code/token/secret 및 임의 URI는 보존하지 않습니다.
  - unknown client/redirect는 local 400으로 중단하고, exact registered redirect가 있는 malformed request는
    SAS가 표준 OAuth 오류를 callback으로 반환하도록 넘깁니다. 따라서 missing/plain PKCE도 기존 Task 7
    `invalid_request` + RP state 계약을 유지합니다.
  - OAuth/OIDC의 optional RP state/nonce를 허용하며 `openid`가 없는 유효 scope 요청도 보존합니다. capture
    실패 또는 교체 때 이전 pending/form을 함께 제거합니다. opaque state/nonce와 encoded redirect/query는
    decode-normalize하거나 이중 encode하지 않고 정확히 한 번만 encode해 resume/deny callback을 만듭니다.
  - login form은 single-use random flow와 pending snapshot 전체에 묶입니다. 같은 세션에서 pending request를
    교체하면 이전 form은 409이며 credential 검증이나 principal 생성으로 진행하지 않습니다.
  - 로그인은 `CredentialAuthenticationService`만 사용하고 HR token을 발급하지 않습니다. 계정/tenant/lock
    실패는 동일한 일반 오류이며, 성공 때 session id를 회전하고 `OAuthSubjectService`의 opaque sub를 해석합니다.
- IdP session과 만료
  - `IdpSessionAuthentication`은 accountId/companyId/userId/roles/opaque sub/authenticatedAt만 보유합니다.
    credentials/details는 null이고 `getName()`이 Task 7의 양수 numeric principalName을 제공하는 명시적
    adapter입니다. PII, password, OAuth/HR token은 세션 principal에 없습니다.
  - session cookie는 host-only `IDP_AUTH_SESSION`, HttpOnly, SameSite=Lax, dev Secure=false, path `/`입니다.
    Secure 설정의 기본값은 true이고 dev/test profile만 false로 명시합니다.
    로그인과 비밀번호 변경 성공 때 fixation 방지를 위해 session id를 회전합니다.
  - injected `Clock`으로 모든 IdP browser request에서 lastAccess 30분과 authenticatedAt 8시간을 검사합니다.
    `now == deadline`인 inclusive boundary에서 context/session/pending state를 invalidate하고 cookie를 만료합니다.
  - authorize/consent browser 경로마다 Account/User/Company와 opaque subject를 다시 읽어 ACTIVE, lock,
    mustChangePassword 및 소유 관계를 확인합니다. code consume/final save도 같은 현재 lock/must-change 경계를
    다시 검사해 stale SSO session으로 token을 발급할 수 없습니다.
- 비밀번호 변경
  - `mustChangePassword` principal은 `/idp/password` 이외 authorize/consent 진행이 막힙니다.
  - 기존 identity `AuthenticationService.changePassword`와 password policy를 사용하며 HR token은 만들지 않습니다.
    password form도 single-use이고, 성공 뒤 session id를 회전하되 최초 credential authenticatedAt은 보존한
    다음에만 정확한 pending authorize URI로 복귀합니다.
- project consent
  - application `OAuthConsentService`가 `OAuthConsentRepository`를 투영해 active client, account/company/sub,
    pending authorization id와 exact requested/approved scope binding을 검증하고 승인 snapshot을 저장합니다.
  - SAS consent adapter의 find/remove와 일반 save는 application service를 사용하고, browser approval save는
    검증된 decision을 request scope에 stage해 authorization code final save와 같은 transaction으로 넘깁니다.
    원문 server state는 새 저장소에 복제하지 않고 Task 7의 hashed pending lookup을 그대로 사용합니다.
  - SAS provider의 consent predicate를 project 정책으로 명시했습니다. `CONSENT_REQUIRED`는 `openid` 단독
    최초 요청도 반드시 화면을 보이며, 기존 승인 scope가 요청의 superset일 때만 재사용합니다.
    `TRUSTED_FIRST_PARTY`만 최초 화면을 생략합니다.
  - 화면은 client display name, 모든 요청 scope, 신규 scope와 `기본 식별`, `프로필`, `이메일`, `회사`,
    `조직/직위`, `HR 역할` 설명을 노출합니다. approve는 SAS POST/state 검증을 그대로 거쳐 snapshot을
    저장합니다. pending/client/identity/consent를 고정 순서로 lock하고 PostgreSQL transaction advisory lock으로
    새 consent row도 직렬화합니다. consent scope와 authorization code는 한 transaction에서 저장하므로 code
    실패 시 consent도 rollback되고, concurrent incremental approval은 승인 scope를 잃지 않습니다.
    deny는 기존 consent를 건드리지 않고 같은 pending decision lock에서 pending만 제거한 뒤 exact callback에
    `access_denied`와 원래 RP state를 반환합니다.
- protocol 시간/Origin
  - domain authorization의 authenticatedAt은 실제 `IdpSessionAuthentication` credential instant에서 얻고
    저장/복원합니다. 수 시간 뒤 SSO code로 만든 ID token의 `auth_time`도 authorization 시각이 아니라 이 값을
    사용합니다. 임의 `Authentication` 객체는 serialization하지 않습니다.
  - exact Origin은 login/password/consent decision/authorize-consent POST/logout 같은 browser state-changing
    endpoint에만 적용합니다. confidential BFF의 `/oauth2/token` POST는 Origin 없이 SAS client auth+PKCE로
    정상 교환됩니다. public RP CORS는 Task 12 경계로 남겼습니다.
- browser security/UI
  - login/password/consent POST에 Spring CSRF와 exact issuer Origin을 함께 요구하며 GET은 safe입니다.
  - 모든 화면은 Thymeleaf server rendering이며 third-party script/resource가 없습니다. dynamic values는
    escaped되고 label, alert, heading, focus state를 제공합니다.
  - Task 10 UserInfo, Task 11 refresh rotation, Task 12 protocol event는 구현하지 않았습니다.

## 엄격 TDD 증거

### 최초 browser RED

Production 구현 전에 `IdpBrowserFlowIntegrationTest`를 먼저 작성했습니다.

Command:

`./gradlew.bat test --tests "*IdpBrowserFlowIntegrationTest" --console=plain`

Result:

- 11 tests 중 8 failures
- 기존 Task 1 scaffold에는 credential POST, password/consent 화면, pending 복원, IdP principal/session timeout이
  없어 real authorize→login flow가 실패했습니다. 기존 최소 login/cookie/chain 경계 3개만 통과했습니다.

### 추가 RED와 최소 수정

1. request fixation test를 먼저 추가하고 단독 실행했을 때 교체된 pending request에도 오래된 form이 200으로
   다시 렌더되어 `expected 409`로 실패했습니다. login flow에 pending snapshot을 함께 묶고 synchronized
   single-use consume에서 현재 snapshot과 equality를 검사했습니다.
2. `CONSENT_REQUIRED`의 `openid` 단독 최초 요청 test를 먼저 추가했습니다. SAS 기본 정책이 consent를
   생략해 consent URI 대신 RP callback으로 가면서 실패했습니다. SAS provider에 project predicate를 설정해
   실제 승인 superset 또는 trusted client만 생략하도록 고쳤습니다.
3. 관련 회귀 최초 실행은 128 tests 중 2 failures였습니다.
   - 커스텀 consent page 도입으로 Task 7의 임시 `Consent required` 문구 assertion이 실패했습니다.
     numeric `IdpSessionAuthentication` adapter로 실제 project consent 화면을 따라가도록 의미를 강화했습니다.
   - pre-login snapshot filter가 registered callback의 missing PKCE를 local 400으로 가로채 Task 7 표준
     `invalid_request` redirect test가 실패했습니다. safe registered redirect 판별을 snapshot completeness와
     분리해 malformed protocol request는 SAS validation에 맡겼습니다. plain test도 익명 pre-login 경로로
     강화했습니다.
4. self-review 중 authorize URL에 의도적으로 raw `code_verifier`, access token, client secret query를 붙이는
   test를 먼저 추가했습니다. custom pending은 안전했지만 Spring 기본 SavedRequest가 원문 query를 세션에
   보존해 실제 RED가 되었습니다. 이 flow는 custom allowlist resume만 사용하므로 authorization chain의
   request cache를 `NullRequestCache`로 바꾸어 원문 저장 경로를 제거했습니다.

### Review fix round 1/5 RED → GREEN

1. Origin 경계를 먼저 실제 confidential authorization-code exchange에서 Origin 없이 호출했습니다. 기존 guard가
   403을 반환해 RED였고, browser state-changing POST allowlist로 좁힌 뒤 token exchange와 browser CSRF/exact
   Origin이 함께 GREEN이었습니다. 구 Task 1의 전역 token-Origin assertion도 승인된 새 계약으로 갱신했습니다.
2. fresh session의 `profile` 요청(state/nonce 없음)은 로그인 뒤 pending이 없어 RED였습니다. optional 값과
   no-openid request를 safe allowlist에 포함하고 capture 실패/교체 시 stale pending/form을 제거했습니다.
3. `+`, `%2F`, `&`, space가 섞인 state/nonce와 기존 encoded query가 있는 redirect는 resume에서 값이 바뀌고
   deny callback은 `%252F`로 이중 encode되어 각각 RED였습니다. raw registered redirect를 유지한 채 새 query
   값만 UTF-8 percent encode하는 builder로 두 경로를 GREEN으로 만들었습니다.
4. 로그인 뒤 account lock/must-change, company inactive, user resigned mutation test를 먼저 추가했습니다.
   stale session이 authorize를 계속해 RED였고 current-state service를 browser filter와 transactional consent
   boundary에 적용했습니다. Task 7 code consume/finalization의 lock/must-change mutation도 token이 발급되는 RED에서
   표준 `invalid_grant` GREEN으로 바뀌었습니다.
5. non-empty partial scope POST는 code를 발급해 RED였습니다. server-state hash, pending authorization ID,
   internal/public client, account/company/user/sub, exact requested/approved scopes를 request-local SAS hand-off와
   transaction 안에서 모두 재검증해 400/no consent/no code가 되었습니다.
6. 실제 PostgreSQL double-approve는 unique constraint race를 일으켜 RED였습니다. pending→client→current
   identity→consent advisory/row lock 순서와 consent+code 단일 transaction을 도입했습니다. double-approve와
   approve-vs-deny는 정확히 한 winner, simulated code-save failure는 consent/code 0 + retryable pending,
   서로 다른 concurrent incremental approval은 두 code와 scope union을 남기는 GREEN입니다. 기존 grant에만
   있던 scope를 새 요청 승인 때 잃는 순차 test와 동시 scope 소실 test도 각각 RED 후 GREEN이었습니다.
   첫 full suite에서는 approve와 deny가 모두 callback을 내는 간헐 실패가 다시 드러났습니다. browser filter의
   read entity가 OpenEntityManagerInView 1차 캐시에 남아 `SELECT FOR UPDATE` 대기 뒤 loser가 stale state를
   재사용한 것이 원인이었습니다. decision transaction 시작 시 그 read snapshot을 clear한 뒤 동일 경쟁 10회
   반복, browser 전체와 두 번째 fresh full이 GREEN이었습니다.
7. login 2시간 뒤 SSO ID token은 authorization 시각을 `auth_time`으로 사용해 RED였습니다. 실제 session
   credential timestamp를 domain에 저장해 original epoch가 되었습니다. 비밀번호 변경은 새 시각으로 absolute
   window를 연장해 RED였고 최초 authenticatedAt을 보존하도록 고쳤습니다.
8. cookie secure configuration test는 accessor/constructor 부재로 compile RED였습니다. safe default true,
   dev/test false와 container/manual cookie 설정을 같은 property로 묶어 GREEN으로 만들었습니다.

## GREEN / 검증 증거

Browser flow:

`./gradlew.bat test --rerun-tasks --tests "*IdpBrowserFlowIntegrationTest" --tests "*OAuthSecurityPropertiesTest" --tests "*ModuleBoundaryTest" --console=plain`

- `BUILD SUCCESSFUL in 55s`
- 30 tests, 0 failures
- real authorize→login→password(optional)→consent→callback, session rotation/cookie/minimal principal,
  consent reuse/incremental/deny/openid-only/trusted skip, tenant mismatch generic denial, CSRF/Origin,
  idle/absolute inclusive expiry, concurrent login single-use, stale consent replay, unsafe redirect와 pending
  replacement, optional state/nonce, exact encoding, current-state mutation, original auth_time, exact scope binding,
  double-approve/approve-deny/code rollback/no-lost-update concurrency를 포함합니다.

Task 1/2/6/7/8 focused regression:

`./gradlew.bat test --rerun-tasks --tests "*SecurityChainIsolationIntegrationTest" --tests "*CredentialAuthenticationServiceTest" --tests "*AuthenticationServiceTest" --tests "*RefreshTokenRotationIntegrationTest" --tests "*AccountRevocationIntegrationTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*OAuthRefreshTokenTest" --tests "*OAuthAuthorizationTest" --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AtomicAuthorizationCodeClientAuthenticationProviderTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeySourceTest" --tests "*OAuthSigningKeyTest" --tests "*OAuthPrivateKeyCipherTest" --tests "*OidcDiscoveryAndTokenContractIntegrationTest" --tests "*ModuleBoundaryTest" --tests "*OAuthSecurityPropertiesTest" --console=plain`

- `BUILD SUCCESSFUL in 1m 32s`
- JUnit XML: 161 tests, 0 failures, 0 errors, 0 skipped

Fresh full backend:

`./gradlew.bat test --rerun-tasks --console=plain`

- `BUILD SUCCESSFUL in 3m 37s`
- JUnit XML: 339 tests, 0 failures, 0 errors, 0 skipped

Static verification:

- `git diff --check`: 오류 0건; Windows LF→CRLF checkout warning만 출력
- pending/session types에는 password/verifier/code/token/secret field가 없습니다.
- SSR templates에는 script 및 third-party URL이 없습니다.
- Task 10/11/12 production 구현은 추가하지 않았습니다.

## self-review

- pending capture와 post-login resume는 allowlist로 URI를 재구성하므로 raw query나 외부 return URL을 신뢰하지
  않습니다. Spring SavedRequest도 비활성화해 verifier/token/secret 이름이나 값이 pre-login 세션에 남지 않습니다.
  client status, exact redirect, allowed scopes, S256를 로그인 전과 tenant login 뒤 모두 검사합니다.
- unknown redirect local rejection과 safe callback SAS error 위임을 분리했습니다. 이 분기를 합치면 missing/plain
  PKCE 회귀 또는 open redirect 음성 test가 실패합니다.
- login flow를 pending snapshot과 묶고 synchronized consume하므로 request 교체 및 동시 이중 제출 중 하나만
  성공합니다. password flow와 SAS consent state도 single-use/replay test가 고정합니다.
- expiry 비교는 `now.isBefore(deadline)`일 때만 유지하므로 정확한 30분/8시간 boundary가 만료입니다. invalidate
  전에 SecurityContext를 clear하고 session 전체를 폐기하므로 pending/form state도 남지 않습니다.
- consent skip은 SAS의 OIDC 특례를 사용하지 않고 project superset 정책을 사용합니다. `openid` 최초 consent,
  reuse, incremental, deny-old-grant tests가 각각 정책 약화를 탐지합니다.
- deny callback은 pending authorization에 들어 있는 SAS-validated exact redirect와 original state만 사용하고,
  app review가 account/company/sub/client/request scope를 재검증한 뒤 pending을 제거합니다.
- session principal의 명시적 field/getter와 session value assertion은 email/password/verifier/token/secret
  비보존을 확인하며 refresh token row가 생기지 않는 것도 browser test가 검사합니다.
- consent 결정은 controller synchronization이 아니라 실제 repository transaction에서 직렬화됩니다. pending
  authorization과 client/current identity를 lock한 뒤 consent pair advisory lock/row lock을 잡아 같은 state의
  approve/deny/replay 중 하나만 이기며 scope와 code를 atomic하게 저장합니다.
- ID token `auth_time`은 최초 credential instant를 보존한 domain authorization에서 나옵니다. password change와
  장시간 SSO가 authorization/code 발급 시각으로 이 값을 덮어쓰지 않는 test가 이를 고정합니다.

## 우려와 의도적 경계

- dev/test profile의 HTTP issuer 때문에 session cookie `Secure=false`를 명시합니다. 설정 누락 시 기본은 true이며
  운영 TLS profile은 이 안전 기본값을 사용합니다.
- password change는 authenticatedAt을 보존하므로 최초 credential login부터 absolute 8시간을 연장하지 않습니다.
- consent 직렬화의 새-row pair lock은 PostgreSQL `pg_advisory_xact_lock`을 사용합니다. 프로젝트의 PostgreSQL
  영속성 전제에는 맞지만 다른 DB로 이식할 때 동등한 transaction-scoped pair lock이 필요합니다.
- UserInfo response, refresh token lifecycle, protocol audit event는 각각 Task 10/11/12 범위로 남겼습니다.
