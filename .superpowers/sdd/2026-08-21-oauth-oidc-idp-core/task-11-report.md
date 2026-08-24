# Task 11 구현 보고서

## 상태

DONE

BASE는 `0223eea0176f27c1e9cf62d173931d28ef96dc03`입니다. 구현 및 이 보고서의 commit hash는
상위 agent handoff에 함께 기록합니다.

## Review fix round 1/5 (`561e16c` 이후)

Fresh review의 Critical 1건과 Important 3건을 엄격한 RED → GREEN으로 수정했습니다.

- 모든 refresh 세대가 authorization 단위 PostgreSQL transaction advisory sentinel을 공유합니다. rotation/reuse와
  account/company/client/consent mutation은 sentinel을 authorization id 정렬 순서로 먼저 획득하고, 그 다음
  refresh rows → authorization rows 순서로 잠급니다. 따라서 used-root reuse가 기다리는 동안 active successor가
  회전해 새 successor를 insert하더라도 reuse family revoke가 insert 이후 snapshot에서 실행되어 생존 row가 없습니다.
- access/refresh token metadata에 effective `authorized_scopes`를 V8에서 영속화했습니다. successor는 현재 refresh
  scope의 exact subset만 허용하며, `scope` 생략은 authorization-wide scope가 아니라 직전 refresh scope를 계승합니다.
  UserInfo의 persisted current-state snapshot도 해당 access-token exact scope를 사용합니다.
- `OAuthConsentService.remove(...)`가 consent 삭제 전에 같은 account+client의 authorization/access/refresh를 동일
  application transaction에서 폐기합니다. Spring SAS consent service도 이 경로를 사용합니다. 강제 rollback 시
  consent와 세 종류 grant metadata가 함께 원복됩니다.

### Review RED

Command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.used_root_reuse*" --tests "*OAuthRefreshAndRevocationIntegrationTest.downscoped*" --tests "*OAuthRefreshAndRevocationIntegrationTest.*consent_removal*" --tests "*OAuthRefreshAndRevocationIntegrationTest.consent_and_grant*" --tests "*OAuthRefreshAndRevocationIntegrationTest.concurrent_account_disable*" --console=plain`

- `BUILD FAILED in 35s`
- 8 tests, 4 failed: used-root vs active-successor 경합 뒤 successor 1개 생존, 다세대 downscope 생략 시 scope 재확장,
  application/SAS consent 삭제 뒤 grant가 ACTIVE로 잔존했습니다.

### Review concurrency GREEN

Command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.concurrent_refresh_reuse*" --tests "*OAuthRefreshAndRevocationIntegrationTest.used_root_reuse*" --tests "*OAuthRefreshAndRevocationIntegrationTest.concurrent_account_disable*" --console=plain`

- `BUILD SUCCESSFUL in 32s`
- 실제 PostgreSQL에서 동일 root 경쟁, used-root vs active-successor 경쟁, mutation-vs-refresh 경쟁 3건이 모두
  완료되었고 successor 생존 0건, 교착 0건을 확인했습니다.
- 첫 GREEN 시 scalar sentinel lookup 전 entity를 materialize해 두 동시 요청이 stale managed entity를 볼 수 있는
  기존 회귀 테스트 RED(`[200, 200]`)를 발견했습니다. scalar authorization-id lookup으로 persistence context 오염을
  제거한 뒤 기존 one-winner 계약(`[200, 400]`)까지 다시 GREEN으로 만들었습니다.

### Review 최종 검증

Fresh focused command:

`./gradlew.bat test --rerun-tasks --tests "*OAuthRefreshAndRevocationIntegrationTest" --tests "*CredentialAuthenticationServiceTest" --tests "*AuthenticationServiceTest" --tests "*AccountRevocationIntegrationTest" --tests "*OAuthClientServiceTest" --tests "*OAuthClientPersistenceIntegrationTest" --tests "*OidcUserInfoIntegrationTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*OAuthRefreshTokenTest" --tests "*ModuleBoundaryTest" --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 1m 42s`
- JUnit XML: 186 tests, 0 failures, 0 errors, 0 skipped

Fresh full backend command:

`./gradlew.bat test --rerun-tasks --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 4m 30s`
- JUnit XML: 424 tests, 0 failures, 0 errors, 0 skipped

## 구현 범위

- project-owned refresh rotation
  - SAS 기본 refresh provider를 제거하고 `OAuthRefreshTokenAuthenticationProvider`를 token endpoint에
    연결했습니다. Task 8의 RS256 customizer와 동일한 `OAuth2TokenGenerator`를 사용합니다.
  - raw refresh token은 protocol adapter 메모리에서만 사용하고 DB lookup/persistence에는 SHA-256 hash만
    사용합니다. raw access/refresh token이나 client secret을 DB 또는 로그에 기록하지 않습니다.
  - hash lookup, refresh row lock, used/revoked/expiry 판단, parent authorization/client/company/account/user/consent
    current-state 검사, successor와 access-token metadata insert, 기존 token의 used/successor 연결을 한
    `REQUIRES_NEW` transaction에서 수행합니다.
  - used token 재사용은 family bulk revoke 후 `REUSED` value를 반환합니다. protocol layer가 transaction
    종료 뒤 `invalid_grant`를 만들기 때문에 family 철회가 예외 rollback으로 사라지지 않습니다.
  - 동시 재사용은 실제 PostgreSQL row lock으로 선형화됩니다. 정확히 한 요청만 token response를 받으며,
    뒤 요청은 먼저 mint된 successor를 포함한 family 전체를 철회합니다.
  - successor는 최초 family의 UUID와 exact absolute `expires_at`을 유지합니다. 최초 family TTL은 7일이고
    exact expiry에서는 만료입니다. access token은 configured 5분 TTL이며 authorization expiry를 넘기지 않습니다.
  - account ACTIVE, active login lock 없음, must-change=false, user/company/client ACTIVE, authorization/refresh ACTIVE,
    client secret active/non-expired, exact scope와 current consent를 교환 시점의 locked row에서 다시 검사합니다.
    login lock은 `now == lockedUntil`에서 해제된 것으로 판단합니다.
- Task 7 private persisted provenance 재검증
  - refresh provider는 `SpringOAuth2AuthorizationService.save(...)`를 호출하지 않습니다. project repository의
    typed aggregate와 hash-only token metadata만 저장합니다.
  - integration spy가 refresh rotation 동안 SAS authorization service save 호출이 0회임을 고정합니다.
- shared application revocation port
  - identity package의 `OAuthGrantRevocationPort`를 OAuth application service가 구현합니다. identity/HR/client
    mutation은 이 port에만 의존하며 application event나 cyclic dependency를 사용하지 않습니다.
  - account/company/client scope마다 refresh rows를 id 순서로, authorization rows를 id 순서로 잠근 뒤
    access/refresh/authorization을 bulk revoke합니다. refresh/code 교환이 사용하는
    refresh → authorization → client → company → account → user 순서와 맞춥니다.
  - 잘못된 비밀번호 시도는 account row 전에 `lockAccountScope`만 획득하고, 실제 lock threshold에서만
    기존 HR refresh와 OAuth grant를 같은 transaction에서 철회합니다.
- mutation 연결
  - temporary-password reset, password change, login lock, account disable
  - user `LOCKED`/`RESIGNED`, `COMPANY_ADMIN` assignment/revocation
  - company `INACTIVE`
  - OAuth client `DISABLED`, confidential secret rotation, 명시적 secret revocation
  - 모든 경로에서 기존 HR refresh 철회를 유지하고 OAuth authorization/access/refresh 철회를 같은 application
    transaction에 포함했습니다. validation/version 실패 시 선행 OAuth bulk update도 함께 rollback됩니다.
- already-issued access token
  - self-contained RS256 access token의 서명/5분 cryptographic lifetime은 바꾸지 않았습니다.
  - mutation이 access-token metadata와 parent authorization을 즉시 폐기하므로 Task 10 UserInfo current-state gate가
    이미 발급된 bearer를 즉시 generic 401로 거부합니다.

## 엄격 TDD RED → GREEN 증거

모든 command는 `backend`에서 실행했습니다.

### 1. 기본 SAS refresh 동작 RED → atomic rotation GREEN

Command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest"`

RED:

- `BUILD FAILED in 24s`
- 기존 SAS refresh provider가 private persisted authorization을 재구성한 뒤 `JwtGenerator`에서 NPE가 발생했습니다.
- project-owned single-use rotation, reuse family revoke와 successor persistence가 없었습니다.

GREEN after custom provider/repository transaction:

- 같은 command가 `BUILD SUCCESSFUL in 23s`
- 최초 교환 성공, 기존 token 재사용 `invalid_grant`, successor 재사용 `invalid_grant`, family 2개 row 모두 revoked,
  successor link/used metadata와 hash-only persistence를 확인했습니다.

### 2. consent와 absolute expiry

Command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.refresh_rechecks_current_consent_policy_and_exact_family_expiry"`

RED:

- issuance 뒤 trusted client를 consent-required로 바꿔도 refresh가 HTTP 200이었습니다.

GREEN:

- current consent를 같은 locked exchange에서 검사한 뒤 focused class가 `BUILD SUCCESSFUL in 32s`였습니다.
- 최초 refresh family가 7일이고 successor expiry가 exact 동일하며 exact expiry에서는 successor가 생기지 않습니다.

### 3. password mutation hooks

Temporary-password reset command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.temporary_password_reset*"`

- RED: `BUILD FAILED in 29s`; authorization이 `ACTIVE`로 남았습니다.
- GREEN: `BUILD SUCCESSFUL in 32s`; authorization과 refresh가 같은 use case에서 revoked입니다.

Password change command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.password_change*"`

- RED: `BUILD FAILED in 30s`; OAuth grant가 남았습니다.
- GREEN: `BUILD SUCCESSFUL in 31s`; current password는 write lock 밖 snapshot에서 검증한 뒤 OAuth scope를 먼저
  잠그고 locked password hash를 다시 확인하며 HR/OAuth 양쪽을 철회합니다.

### 4. login lock event 제거와 직접 철회

Integration RED command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.login_lock*"`

- `BUILD FAILED in 32s`; 기존 application event listener는 HR refresh만 처리해 OAuth authorization이
  `ACTIVE`로 남았습니다.

Direct-port unit RED command:

`./gradlew.bat test --tests "*CredentialAuthenticationServiceTest.locks_on_the_fifth*"`

- `compileTestJava FAILED`; direct HR/OAuth dependencies와 `lockAccountScope` 계약이 없었습니다.

GREEN command:

`./gradlew.bat test --tests "*CredentialAuthenticationServiceTest.locks_on_the_fifth*" --tests "*OAuthRefreshAndRevocationIntegrationTest.login_lock*"`

- `BUILD SUCCESSFUL in 35s`
- event type/listener를 제거하고 account row lock 전 OAuth scope lock, 다섯 번째 실패 시 direct HR/OAuth revoke를
  확인했습니다.

### 5. role/user/company/client hooks

Command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.company_admin_assignment*" --tests "*OAuthRefreshAndRevocationIntegrationTest.user_disable*" --tests "*OAuthRefreshAndRevocationIntegrationTest.client_disable*"`

RED:

- `BUILD FAILED in 31s`
- 3 mutation test 모두 authorization이 `ACTIVE`라 실패했습니다.

GREEN command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.company_admin_assignment*" --tests "*OAuthRefreshAndRevocationIntegrationTest.user_disable*" --tests "*OAuthRefreshAndRevocationIntegrationTest.client_disable*" --tests "*OAuthClientServiceTest"`

- `BUILD SUCCESSFUL in 36s`
- role assign/revoke, user disable/company deactivate, client disable/secret rotation의 grant revoke를 확인했습니다.

### 6. explicit secret revocation P6

Command:

`./gradlew.bat test --tests "*OAuthClientServiceTest.explicit_secret*"`

RED:

- `compileTestJava FAILED`; `OAuthClientService.revokeSecret(...)`가 없었습니다.

GREEN command:

`./gradlew.bat test --tests "*OAuthClientServiceTest" --tests "*OAuthRefreshAndRevocationIntegrationTest.client_disable*"`

- `BUILD SUCCESSFUL in 31s`
- active secret이 모두 revoked되고 client authorization/access/refresh도 같은 transaction에서 revoked입니다.

### 7. account disable과 immediate UserInfo rejection

Command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.account_disable*"`

RED:

- `compileTestJava FAILED`; account-disable application use case가 없었습니다.

GREEN command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.account_disable*" --tests "*OAuthRefreshAndRevocationIntegrationTest.company_admin_assignment*"`

- `BUILD SUCCESSFUL in 32s`
- account status와 HR/OAuth revoke를 확인하고, 역할 mutation 전에 발급된 access token의 UserInfo가 즉시 401임을
  확인했습니다.

### 8. exact lock boundary test 정밀도 조사

Fresh focused에서 boundary test가 HTTP 200 대신 400으로 한 번 RED였습니다.

Diagnostic command:

`./gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest.account_lock_expiry*" --console=plain`

- `BUILD FAILED in 31s`
- Java expected `...106700Z`, PostgreSQL stored `...107Z`로 TIMESTAMPTZ microsecond 반올림 차이를 확인했습니다.

DB 정밀도에 맞춰 boundary Instant를 microsecond로 truncate한 뒤 같은 command:

- `BUILD SUCCESSFUL in 30s`
- production 경계 정책은 변경하지 않았습니다.

## 최종 GREEN / 검증 증거

Fresh focused Task 7/9/10/11, identity/HR, persistence와 module boundary:

`./gradlew.bat test --rerun-tasks --tests "*OAuthRefreshAndRevocationIntegrationTest" --tests "*CredentialAuthenticationServiceTest" --tests "*AuthenticationServiceTest" --tests "*AccountRevocationIntegrationTest" --tests "*OAuthClientServiceTest" --tests "*OAuthClientPersistenceIntegrationTest" --tests "*OidcUserInfoIntegrationTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*ModuleBoundaryTest" --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 1m 41s`
- JUnit XML: 174 tests, 0 failures, 0 errors, 0 skipped

Fresh full backend:

`./gradlew.bat test --rerun-tasks --console=plain --no-daemon`

- `BUILD SUCCESSFUL in 4m 27s`
- JUnit XML: 418 tests, 0 failures, 0 errors, 0 skipped

Static verification:

- `git diff --check`: whitespace error 0건; Windows LF→CRLF checkout warning만 출력
- identity/HR/OAuth mutation application에서 `ApplicationEventPublisher`, `@EventListener`, `AccountLocked` 잔존 0건
- `progress.md`, plan, spec은 수정하지 않았습니다.

## self-review

- reuse family revoke는 예외가 아니라 transaction result value로 경계를 넘어가므로 commit 전
  `invalid_grant` throw가 없습니다. concurrent PostgreSQL test가 winner response 뒤 successor까지 revoked임을
  확인합니다.
- successor/access insert와 current token used/successor update는 하나의 independent transaction입니다.
  successor는 같은 authorization/family/expiresAt이어야 하고 access는 exact configured TTL 및 authorization
  lifetime 안이어야 합니다.
- client/identity/consent는 persisted current row를 lock한 뒤 검증합니다. request 시작 시 만들어진 SAS
  `RegisteredClient`만 신뢰하지 않고 internal id/client id/active secret hash를 locked client와 다시 비교합니다.
- mutation의 stable lock order는 refresh rows → authorization rows → client/company/account/user입니다.
  login failure처럼 revoke 여부를 account lock 뒤에야 알 수 있는 경로는 non-mutating `lockAccountScope`를 먼저
  호출해 역순 잠금을 피합니다.
- mutation port는 `MANDATORY` transaction이므로 철회만 독립 commit하지 않습니다. password/client/domain
  validation이나 optimistic version failure가 나면 mutation과 grant revoke가 함께 rollback됩니다.
- Task 7 private repository provenance를 유지하고 raw protocol token은 hash 변환 또는 HTTP response에만
  사용합니다. report, log와 DB에는 raw token/secret을 추가하지 않았습니다.
- 기존 HR refresh revoke 호출은 제거하지 않았고 identity/HR 회귀 및 full backend가 통과했습니다.

## 우려와 의도적 경계

- explicit secret revoke는 현재 application service use case로 제공됩니다. 별도 management HTTP endpoint/UI는
  Task 11 core 범위에 없으므로 추가하지 않았습니다.
- self-contained access JWT는 이미 발급된 뒤 암호학적으로 최대 5분 유효합니다. 즉시 차단은 Task 10 UserInfo의
  persisted access/authorization/current-state gate에서 적용됩니다.
- PostgreSQL은 `TIMESTAMPTZ`를 microsecond 정밀도로 저장합니다. exact boundary integration test는 DB에 실제
  저장되는 정밀도로 Clock을 맞춥니다.
