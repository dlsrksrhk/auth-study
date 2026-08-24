# Task 7 구현 보고서

## 상태

DONE

Task commit hash는 이 보고서를 포함한 별도 커밋 생성 후 상위 agent handoff에 기록합니다.

## 변경 파일

- Spring Authorization Server adapter/mapper:
  - `backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationMapper.java`
  - `backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationService.java`
  - `backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationConsentService.java`
- authorization-code atomic exchange의 최소 지원 확장:
  - `backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/AtomicAuthorizationCodeClientAuthenticationProvider.java`
  - `backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java`
- indexed lookup 지원:
  - `OAuthAuthorizationRepository`, `OAuthAuthorizationRepositoryAdapter`
  - authorization/code/refresh Spring Data repository
  - `V8__oauth_authorization_consent.sql`의 partial unique server-state-hash index
- 명시적 token TTL 및 Task 8 경계:
  - `SpringRegisteredClientRepository`
  - `AuthStudyApplication`의 Boot 임시 authorization-server JWK auto-configuration 제외
- 테스트:
  - `SpringOAuth2AuthorizationServiceTest`
  - `AuthorizationCodePkceIntegrationTest`
  - `SpringRegisteredClientRepositoryTest`
  - `SecurityChainIsolationIntegrationTest`

Task 8 production key ring, Task 9 login/consent 화면, Task 11 refresh rotation은 구현하지 않았습니다.

## 구현 요약

- Spring의 authorization/code/access/refresh 원문은 adapter 경계에서 즉시 SHA-256 lowercase hex로 변환하며, 영속 모델에는 hash와 allowlist metadata만 전달합니다. raw token column, Java native serialization, arbitrary map/default typing을 사용하지 않습니다.
- `save`, `remove`, `findById`, `findByToken`과 consent `save`, `remove`, `findById`를 project repository 위에 구현했습니다. 알려진 token type은 state/code/access/refresh 전용 index를 사용하고, unknown type은 repository scan 없이 `null`을 반환합니다. type 미지정 조회는 네 index만 조회하며 서로 다른 token type에 중복되면 모호성을 이유로 `null`을 반환합니다.
- revoked authorization, disabled client, removed consent는 Spring protocol 객체로 노출하지 않습니다.
- authorization request를 exact redirect URI, RP state, principal account ID, scopes, nonce, PKCE S256 challenge/method로 복원합니다. 저장 attribute는 `principalName`, `authorizationRequestUri`와 중첩된 명시적 authorization-request DTO만 허용하며 각 중첩 필드도 allowlist로 검사합니다.
- registered client token settings에 authorization code 60초, access token 5분, refresh token 7일을 명시했습니다. refresh reuse/rotation 정책은 건드리지 않았습니다.
- Spring Authorization Server 1.5.8은 public-client PKCE를 client authentication 단계에서 검증하여 기본 grant provider의 최종 `save`보다 앞서 실패합니다. 따라서 Task 6 atomic consume을 모든 확인된 code exchange 결과에 적용할 수 있도록 authorization-code request만 다루는 converter/provider를 가장 좁게 추가했습니다.
- atomic callback은 client ID, exact redirect URI, S256 verifier의 메모리 비교만 수행하며 외부 I/O가 없습니다. 확인된 protocol-invalid 조건은 `ExchangeValidation(false)` 값으로 반환하므로 code consume이 commit되고, 예상하지 못한 exception은 Task 6 `REQUIRES_NEW` transaction을 rollback할 수 있습니다.
- consume 성공 객체는 같은 HTTP request scope에만 임시 보관하여 기본 SAS authorization-code grant provider가 표준 token generation/response를 수행하게 합니다. 영속 저장 시에는 다시 hash만 기록하고 request attribute를 제거합니다.
- 실제 HTTP 테스트는 discovery에서 authorization/token endpoint를 얻고, numeric account ID의 authenticated MockMvc session principal을 사용해 authorize 후 code를 교환합니다. Task 9 화면을 우회하는 controller stub은 사용하지 않았습니다.
- production filter 활성화는 authorization service, consent service, OAuth JWKSource가 모두 있을 때만 가능합니다. Spring Boot가 서비스 bean 존재 후 임시 RSA JWK를 자동 생성하던 `OAuth2AuthorizationServerJwtAutoConfiguration`을 제외해, Task 8 전 일반 application context에서는 Task 1의 issuer-origin `/oauth2/token` 501 placeholder가 유지됩니다. RS256 JWK는 integration test nested configuration에만 있습니다.

## 엄격 TDD RED 증거

1. adapter contract RED
   - `./gradlew.bat test --tests "*SpringOAuth2AuthorizationServiceTest"`
   - 세 production adapter/mapper와 indexed repository methods 부재로 `compileTestJava` 16 errors가 발생했습니다.
2. 실제 filter-chain HTTP/atomic exchange RED
   - `./gradlew.bat test --tests "*AuthorizationCodePkceIntegrationTest"`
   - 6 tests 중 5개가 실패했습니다. replay가 다시 200이었고, 두 concurrent exchange가 `[200, 200]`이었으며, wrong/expired exchange 뒤 `used_at`이 비어 있었습니다. missing verifier는 요구한 `invalid_grant` 대신 client-auth 단계 `invalid_client`였습니다. plain method의 authorization `invalid_request`만 통과했습니다.
3. revoked visibility RED
   - revoked authorization 조회가 non-null이어서 신규 unit test가 실패했습니다. mapper에서 inactive/revoked authorization을 숨긴 뒤 GREEN이었습니다.
4. TTL RED
   - `SpringRegisteredClientRepositoryTest`에 code/access/refresh TTL assertion을 먼저 추가했고 properties constructor 부재로 compile failure가 발생했습니다. 명시적 `TokenSettings` 적용 후 GREEN이었습니다.
5. production no-JWK boundary RED
   - 최초 fresh full suite는 229 tests 중 `SecurityChainIsolationIntegrationTest` 1개가 실패했습니다. 일반 context `/oauth2/token`이 501 placeholder 대신 protocol filter의 400을 반환했습니다.
   - bean 진단 test는 `OAuth2AuthorizationServerJwtAutoConfiguration`이 만든 `jwkSource`를 검출해 실패했습니다. 해당 auto-configuration을 production에서 제외한 뒤 no-JWK assertion과 기존 501 assertion이 모두 GREEN이었습니다.

## GREEN / integration / concurrency 증거

- 최종 코드 기준 focused relevant suite:
  - `./gradlew.bat test --rerun-tasks --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*SecurityChainIsolationIntegrationTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest"`
  - BUILD SUCCESSFUL in 53s; XML 합계 7 suites, 51 tests, 0 failures, 0 errors, 0 skipped입니다.
- 실제 HTTP integration 6 cases가 모두 통과했습니다.
  - discovery → authenticated authorization session → exact callback code → correct S256 verifier exchange 1회 성공
  - replay `invalid_grant`
  - missing verifier `invalid_grant` 및 consume 유지
  - `plain` challenge method authorization `invalid_request`, code 미생성
  - wrong verifier `invalid_grant` 및 이후 correct verifier replay도 `invalid_grant`
  - 이미 만료된 code의 HTTP 교환 `invalid_grant` 및 consume 유지
- concurrent HTTP exchange는 같은 code/verifier로 두 요청을 동시에 시작하며 status가 정확히 200 하나, 400 하나이고 access-token row도 정확히 하나임을 검증했습니다. 초기 RED의 `[200, 200]`은 atomic extension 적용 뒤 재현되지 않았습니다.
- 기존 Task 5 confidential/public client auth와 BCrypt-only secret contract, Task 6 PostgreSQL persistence/atomic callback contract를 focused suite에 함께 포함해 GREEN을 확인했습니다.

## 완료 검증

- fresh full backend suite: `./gradlew.bat test --rerun-tasks`
  - BUILD SUCCESSFUL in 3m 2s
  - XML 합계 40 suites, 230 tests, 0 failures, 0 errors, 0 skipped
- `git diff --check`: 오류 없음
- production 금지 문자열 scan: `ObjectOutputStream`, `ObjectInputStream`, default typing API, `code_value`, `access_token_value`, `refresh_token_value` 0건
- 최종 GREEN test XML/HTML의 알려진 raw code/access/refresh fixture와 PKCE verifier scan: 0건
- production JWK source boundary: 일반 application context 0개, 실제 protocol integration의 RS256 key는 test-scope nested bean만 사용

## self-review / mutation reasoning

- mapper의 즉시 hash를 제거하거나 raw token을 domain에 전달하면 adapter unit test의 persisted SHA-256 assertions와 raw-value 부재 assertions가 실패합니다.
- 알려진 token type을 잘못된 repository로 보내거나 unknown type에서 조회하면 Mockito interaction assertions가 실패합니다. type 미지정 ambiguity 처리를 제거하면 cross-type collision test가 실패합니다.
- disabled client/revoked authorization/removed consent를 노출하면 visibility tests가 실패합니다.
- exact redirect 또는 S256 비교를 완화하면 wrong verifier, missing verifier, plain method 및 exact callback integration 중 하나 이상이 성공하여 실패합니다.
- atomic consume을 기본 provider의 최종 save에만 맡기면 실제 관찰한 replay 200 및 concurrent `[200, 200]` RED가 다시 발생합니다. 확인된 invalid를 exception으로 바꾸면 Task 6 rollback semantics 때문에 `used_at`/replay assertion이 실패합니다.
- code expiry를 60초가 아닌 Spring default로 되돌리면 registered-client TTL test와 exact-boundary integration이 실패합니다.
- Boot JWK auto-config 제외를 제거하면 no-JWK bean assertion과 Task 1의 issuer-origin token 501 assertion이 실패합니다.
- test JWK를 production으로 이동하거나 HS256 key를 추가하면 no-production-JWK boundary/architecture review가 실패하는 구조입니다.

## 우려 / 후속 주의

- Spring `principalName`은 현재 기존 OAuth subject ownership 모델에 맞춰 positive numeric account ID 문자열로 계약했습니다. Task 9 인증 principal을 연결할 때 이 형식을 명시적으로 유지하거나 별도 account-ID 추출 adapter를 도입해야 합니다.
- state의 indexed lookup과 ambiguity 방지를 위해 Task 6의 아직 배포 전 V8 migration에 partial unique index를 추가했습니다. 이미 V8이 적용된 환경이 생기면 신규 migration으로 분리해야 합니다.
- Task 8은 production `JWKSource<SecurityContext>`를 명시적으로 제공해야 합니다. Boot의 임시 authorization-server JWK auto-configuration은 의도적으로 제외되어 있습니다.
- request-scope consume cache는 atomic consume 뒤 같은 request의 기본 SAS grant provider에만 전달됩니다. 비동기 dispatch나 grant pipeline 재구성 시 request-boundary 보장을 다시 검증해야 합니다.
- Task 11 refresh rotation/reuse detection은 이번 구현 범위 밖입니다.

## Review fix round 1

### 상태

DONE

초기 Task 7 커밋 `e8f231e049b4380776e5cff20041344e3b1b5615` 위에 reviewer의 C1/C2, I1-I4와 minor 지적을 별도 fix commit으로 반영했습니다.

### 변경 및 지원 파일

- pending authorization / consent state:
  - `OAuthAuthorization`의 allowlisted `AuthorizationRequest` snapshot과 `serverStateHash`
  - `OAuthAuthorizationMapper`, `OAuthAuthorizationAttributesConverter`, authorization JPA entity/repository/service
  - 아직 배포되지 않은 `V8__oauth_authorization_consent.sql`의 `server_state_hash` 컬럼과 partial unique hash index
- atomic exchange fresh context:
  - `OAuthAuthorizationRepository.LockedCodeExchange`
  - `OAuthAuthorizationRepositoryAdapter`, authorization/client JPA repository의 pessimistic lock query
  - `AtomicAuthorizationCodeClientAuthenticationProvider`, `SpringOAuth2AuthorizationService`
- ownership / consent / claims:
  - mapper의 authoritative `AccountRepository` 조회
  - `OAuthConsent.replaceScopes`, `SpringOAuth2AuthorizationConsentService`
  - 실제 access-token claims의 `jti`/`aud` 필수 보존; hash/property 기반 합성 제거
- tests:
  - `SpringOAuth2AuthorizationServiceTest`
  - `OAuthAuthorizationPersistenceIntegrationTest`
  - `AuthorizationCodePkceIntegrationTest`
  - 신규 `AtomicAuthorizationCodeClientAuthenticationProviderTest`

### 리뷰 지적 해소

1. C1 pending consent round-trip
   - RP `state`와 SAS가 만든 consent `state`를 분리했습니다. RP state는 allowlisted request snapshot 안에만 있고, server state는 adapter 경계에서 즉시 SHA-256한 `server_state_hash`로만 저장/조회합니다.
   - code가 없는 pending authorization도 exact redirect, requested scopes, RP state, nonce, S256 challenge/method를 복원합니다. `findByToken(consentState, STATE)`에서만 호출자가 제시한 raw server state를 복원 Spring 객체에 일시적으로 주입합니다.
   - 실제 authorization-server filter chain의 기본 consent 응답을 통해 server state를 얻고, 같은 authenticated session으로 승인 POST 후 callback code와 token을 받는 흐름을 검증했습니다. Task 9 production UI/controller는 추가하지 않았습니다.
2. C2 fresh atomic context
   - Task 6 `REQUIRES_NEW` callback의 고정 lock 순서는 code → parent authorization → current client입니다. callback은 잠긴 최신 authorization/client만 사용해 active status, ownership, current registered redirect, client authentication snapshot, code/request ownership, exact redirect와 S256를 비교합니다.
   - callback에는 clock read와 메모리 비교만 있으며 repository/외부 I/O가 없습니다. 확인된 invalid는 `ExchangeValidation(false, ...)` 값이어서 code consume이 commit되고, system exception은 rollback 가능합니다.
   - 실제 PostgreSQL code row lock에 HTTP exchange가 대기 중임을 `pg_stat_activity`로 확인한 다음 authorization revoke 또는 client disable을 먼저 commit하고 lock을 해제했습니다. revoke는 정확히 HTTP 400 `invalid_grant`, disable은 client-auth semantics에 따라 HTTP 401 `invalid_client`이며 두 경우 모두 `used_at`은 기록되고 access-token row는 0입니다.
   - 동일 code의 두 동시 HTTP 교환은 계속 200 하나/400 `invalid_grant` 하나, access-token row 하나만 허용합니다.
3. I1 authoritative company ownership
   - client company를 account company로 다시 전달하던 tautology를 제거했습니다. numeric principal의 active account를 identity repository에서 읽고 account company와 client company를 비교하며, cross-company/null-company/disabled account는 DB 호출 전에 동일한 generic ownership error로 거부합니다.
4. I2 consent snapshot replacement
   - 기존 consent save는 union이 아니라 전달된 scopes snapshot으로 교체합니다. unit과 실제 PostgreSQL child-row 삭제/축소를 모두 검증했습니다.
5. I3 state uniqueness
   - client-controlled RP state의 전역 unique index를 제거했습니다. lookup용 server consent state만 distinct hash column/index를 사용합니다.
6. I4 protocol evidence
   - confidential client가 BCrypt-only project secret repository와 HTTP Basic을 거쳐 같은 production authorization service/custom atomic provider로 code를 교환하고 refresh token까지 받는 실제 filter-chain test를 추가했습니다.
   - converter/provider가 `refresh_token`, `client_credentials` grant를 모두 `null`로 넘기고 repository/password encoder와 상호작용하지 않음을 검증했습니다.
   - 실제 DB에서 code/access/refresh 값은 각각 SHA-256 hash와 정확히 일치하고 raw와 다르며, verifier는 S256 challenge와 다르고 authorization JSON에도 raw code/access/verifier가 없음을 검증했습니다. client secret도 BCrypt hash만 존재합니다.
7. minor claims behavior
   - access token에 없는 `jti` 또는 `aud`를 합성하지 않습니다. SAS/Task 8이 실제 claims metadata를 제공한 경우만 보존하고, 누락 시 명시적으로 저장을 거부합니다.

### 엄격 TDD RED / GREEN 증거

- C1 unit contract를 먼저 추가한 실행은 `serverStateHash`, nested request, `findByServerStateHash` API 부재로 `compileTestJava` RED였습니다. 최소 domain/mapper/repository 구현 뒤 pending unit과 실제 consent-required filter flow가 GREEN이었습니다.
- C2 persistence contract를 먼저 추가한 실행은 fresh locked exchange 및 parent/client lock API 부재 6곳으로 `compileTestJava` RED였습니다. stable lock order와 callback context 구현 뒤 PostgreSQL callback test, revoke/disable HTTP race와 기존 two-exchange race가 GREEN이었습니다.
- I1 unit contract는 mapper constructor/account dependency 부재로 compile RED였고, authoritative account lookup 후 GREEN이었습니다.
- I2 narrowing unit은 예상 `[openid]` 대신 기존 union `[openid, profile]`을 받아 assertion RED였으며 snapshot replace 뒤 unit/DB integration 모두 GREEN이었습니다.
- synthetic `jti`/`aud` 제거 test는 누락 claims가 저장되어 RED였고 명시적 null-safe rejection 뒤 GREEN이었습니다.
- schema 이름 변경 뒤 supporting persistence run은 direct-test SQL이 제거된 `state` 컬럼을 사용하여 ownership 3건이 `BadSqlGrammarException` RED였습니다. direct fixture를 `server_state_hash = null` 계약으로 고친 뒤 persistence suite 전체가 GREEN이었습니다.

최종 코드 기준 focused command:

`./gradlew.bat test --rerun-tasks --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AtomicAuthorizationCodeClientAuthenticationProviderTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*SecurityChainIsolationIntegrationTest"`

- BUILD SUCCESSFUL in 46s
- XML 합계 8 suites, 63 tests, 0 failures, 0 errors, 0 skipped

fresh full backend command `./gradlew.bat test --rerun-tasks`:

- BUILD SUCCESSFUL in 2m 50s
- XML 합계 41 suites, 242 tests, 0 failures, 0 errors, 0 skipped

`git diff --check`는 오류가 없습니다. production 금지 문자열(`ObjectOutputStream`, `ObjectInputStream`, default typing API, raw token value column명)은 0건이며, 최종 test XML/HTML의 알려진 verifier/confidential-secret/Java serialization fixture scan도 0건입니다.

### 정확한 boundary 표현과 self-review

- HTTP expiry test는 DB expiry를 과거로 옮긴 뒤 표준 `invalid_grant`와 consume을 증명하며 exact instant test라고 주장하지 않습니다. exact boundary는 `OAuthAuthorizationPersistenceIntegrationTest.authorization_code_is_expired_and_consumed_at_the_exact_expiry_boundary`에서 `now == expiresAt`일 때 `EXPIRED`와 `usedAt == expiresAt`을 검증합니다.
- nested request 필드를 하나라도 저장/복원하지 않으면 pending unit 또는 실제 consent approval/callback이 실패합니다. server state hashing/index lookup을 제거하면 raw/hash DB assertion과 state lookup interaction assertion이 실패합니다.
- account source를 다시 client로 대체하면 cross-company unit이 실패하고, consent union을 복원하면 unit/DB narrowing 두 test가 실패합니다.
- authorization/client fresh lock 또는 active/redirect/S256 비교를 제거하면 deterministic revoke/disable race나 wrong-verifier/exact-redirect 흐름이 token을 발급해 실패합니다. lock 순서를 바꾸면 persistence callback test의 documented repository contract와 동시성 review를 위반합니다.
- Basic path를 built-in-only 또는 in-memory service로 바꾸면 hash-only secret/used-code/project DB row assertions이 함께 실패합니다. authorization-code 외 grant를 가로채면 no-interaction parameterized test가 실패합니다.
- `jti`/`aud` 합성을 복원하면 missing-claims unit이 실패합니다.

### 남은 우려 / 범위 경계

- V8은 feature branch에서 아직 배포되지 않았다는 전제라 round 1에서 직접 바로잡았습니다. 어떤 환경이든 기존 V8 checksum을 이미 기록했다면 이 변경을 배포하지 말고 후속 migration으로 옮겨야 합니다. Task 8의 V9 이름과 충돌하는 migration은 만들지 않았습니다.
- production protocol filter는 authorization service + consent service + production JWKSource가 모두 있을 때만 활성화됩니다. Task 8 전 일반 app context의 issuer-origin token POST 501 boundary와 test-scope RS256 JWK만 사용하는 경계를 focused/full suite에서 보존했습니다.
- numeric account-id principal 계약은 Task 9 연결 시 유지하거나 명시적 principal adapter로 교체해야 합니다. Task 9 UI, Task 8 key ring, Task 11 refresh rotation은 이번 fix 범위 밖입니다.
- BASE-wide Origin 정책은 Task 12 범위라 변경하지 않았으며, 이 테스트는 issuer Origin이 실제 BFF interop을 대표한다고 주장하지 않습니다.

## Review fix round 2

### 상태와 변경 파일

DONE

- final token persistence boundary:
  - `OAuthAuthorizationRepository`, `OAuthAuthorizationRepositoryAdapter`
  - `OAuthAuthorizationMapper`, `SpringOAuth2AuthorizationService`
  - `AtomicAuthorizationCodeClientAuthenticationProvider`
- authoritative identity locking:
  - `UserRepository`, `UserJpaRepository`, `UserRepositoryAdapter`의 `findByIdForUpdate`
- strict allowlist JSON:
  - `OAuthAuthorizationAttributesConverter`
- tests:
  - `AuthorizationCodePkceIntegrationTest`
  - `OAuthAuthorizationPersistenceIntegrationTest`

### Critical finalization boundary

SAS 1.5.8의 authorization-code provider는 access/refresh/ID token을 생성한 다음 `OAuth2AuthorizationService.save`를 호출합니다. round 1의 consume transaction은 그보다 먼저 끝나므로, 그 사이 revoke/disable이 commit되면 unlocked final save가 최신 상태에 token row를 붙일 수 있었습니다.

authorization-code request-scope cache가 있고 issued access token이 포함된 최종 save는 이제 일반 mapper read-then-save 경로를 사용하지 않습니다. raw access/refresh는 이 adapter 경계에서 즉시 SHA-256 domain metadata로 바뀌며, 별도 `REQUIRES_NEW` finalization이 다음 순서로 잠급니다.

`code → parent authorization → current client → company → account → user`

finalization은 다음을 같은 transaction에서 다시 확인합니다.

- code hash, consumed 상태와 parent authorization ID
- candidate authorization/client/token ownership
- current authorization ACTIVE/미만료와 current client ACTIVE
- confidential client이면 직전에 인증한 BCrypt secret hash가 여전히 active/미만료인지 여부
- current exact registered redirect, code/request redirect와 S256 challenge/method
- current client/request/authorized scopes 관계
- Company/Account/User가 모두 ACTIVE이고 서로 같은 authoritative ownership chain인지 여부
- 기존 access/refresh row가 아직 없는지 여부

유효할 때만 현재 잠긴 aggregate의 parent 상태를 수정하지 않고 access/refresh metadata row만 save+flush합니다. 따라서 stale `ACTIVE` parent를 다시 쓰거나 `REVOKED` parent를 부활시키지 않습니다. refresh flush가 실패하면 같은 transaction의 access insert도 rollback됩니다. invalid finalization은 explicit `INVALID` 값이며 Spring service가 generic OAuth 400 `invalid_grant`로 바꿉니다. DB/system exception은 protocol-invalid로 숨기지 않고 transaction rollback이 가능합니다.

잠금은 HTTP response까지 유지하지 않고 token metadata commit 지점에서만 linearize합니다. finalization이 먼저 잠금을 얻으면 뒤의 identity mutation이 기다렸다가 이후 순서가 되고, mutation이 먼저 commit되면 finalization이 `invalid_grant`로 끝납니다. 현재 identity mutation의 suffix 순서인 company → account → user와 맞췄습니다. 미래 Task 12 identity-to-OAuth revocation은 identity lock을 보유한 채 OAuth lock을 역순으로 잡지 말고 identity transaction commit 뒤 별도 transaction/event로 수행해야 한다는 규칙을 repository contract에 기록했습니다.

### 실제 post-validation race와 identity 오류

production sleep/hook은 추가하지 않았습니다. test-scope `@MockitoSpyBean`이 token이 생성된 final `save` 진입에서만 latch로 멈추므로 post-validation/pre-final-save window를 정확히 만듭니다. 그 사이 실제 PostgreSQL update를 commit한 뒤 final save를 해제합니다.

- authorization revoke: 400 `invalid_grant`, consumed code, access 0, refresh 0, parent `REVOKED` 유지
- client disable: 400 `invalid_grant`, consumed code, access 0, refresh 0
- account disable: 400 `invalid_grant`, consumed code, access 0, refresh 0
- user resignation: 400 `invalid_grant`, consumed code, access 0, refresh 0
- company inactive: 400 `invalid_grant`, consumed code, access 0, refresh 0

account가 atomic consume 전에 이미 DISABLED인 별도 HTTP test도 정확히 400 `invalid_grant`, consumed code, access/refresh 0을 검증합니다. account/user/company lock과 상태 read는 repository가 callback 전에 수행하므로 Task 6 callback 자체는 계속 clock read와 메모리 비교만 수행합니다.

### Minor JSON node type validation

root의 `principalName`, `authorizationRequestUri`와 nested request의 redirect/challenge/method는 textual node만 허용합니다. RP state/nonce는 null 또는 textual만 허용하고, requested scopes는 array이며 각 element가 textual이어야 합니다. allowlisted field 이름만 맞춘 numeric/boolean/object/array 값을 Jackson이 String으로 coercion하기 전에 모두 거부합니다.

### 엄격 TDD와 mutation 증거

1. post-validation revoke HTTP RED
   - test-scope barrier 뒤 revoke를 먼저 commit했을 때 기존 구현은 기대한 `ExchangeResult[400, invalid_grant]` 대신 `ExchangeResult[200, null]`과 token을 반환했습니다.
2. persistence finalization contract RED
   - test를 먼저 추가한 실행은 `CodeFinalization`, `CodeFinalizationResult`, `finalizeAuthorizationCodeExchange`가 없어 `compileTestJava` 3 errors였습니다.
3. disabled account RED/mutation
   - account-active 검사를 제거한 regression 실행에서 pre-validation과 post-validation test 모두 기대 400 대신 200이었습니다. 검사를 복원한 뒤 둘 다 GREEN입니다.
4. client status mutation
   - finalization의 current-client ACTIVE 검사를 제거하면 post-validation client-disable test가 기대 400 대신 200으로 실패하며, 복원 뒤 GREEN입니다.
5. User/Company RED
   - authoritative User/Company validation을 제외한 상태에서 두 post-validation tests가 모두 기대 400 대신 200이었습니다. company/user pessimistic re-read를 추가한 뒤 GREEN입니다.
6. JSON RED
   - wrong-node-type 9-case parameterized test 중 numeric principal, boolean authorization URI, numeric redirect, numeric scope element, boolean nonce 5건이 Jackson coercion으로 저장되어 실패했습니다. explicit node validation 뒤 9건 모두 GREEN입니다.

### GREEN / full evidence

focused command:

`./gradlew.bat test --rerun-tasks --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AtomicAuthorizationCodeClientAuthenticationProviderTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*SecurityChainIsolationIntegrationTest" --tests "*ModuleBoundaryTest"`

- BUILD SUCCESSFUL in 52s
- XML 합계 8 suites, 79 tests, 0 failures, 0 errors, 0 skipped
- 실제 authorization-code filter-chain suite 16 cases와 PostgreSQL persistence suite 30 cases 포함

fresh full backend `./gradlew.bat test --rerun-tasks`:

- BUILD SUCCESSFUL in 2m 55s
- XML 합계 41 suites, 258 tests, 0 failures, 0 errors, 0 skipped

production 금지 문자열(`ObjectOutputStream`, `ObjectInputStream`, default typing API, raw token value column명)은 0건입니다. 최종 test XML/HTML의 알려진 verifier, confidential/race secret, Java serialization fixture scan도 0건이고 `git diff --check` 오류도 없습니다.

### self-review / 범위 경계

- finalization에서 authorization/client/account/user/company 상태 검사를 하나씩 제거하면 해당 deterministic race가 200으로 바뀝니다. parent를 일반 `save`로 되돌리면 revoke test의 parent/token assertions이 실패합니다.
- code/authorization/client/request ownership 또는 exact redirect/S256 비교를 제거하면 persistence finalization contract나 기존 wrong-verifier/exact-redirect tests가 방어하지 못하므로 finalization에서 consume 단계와 독립적으로 다시 확인합니다.
- finalization에 raw token을 전달하거나 mapper hash를 제거하면 기존 실제 DB hash equality/raw inequality tests와 production scan 계약을 위반합니다.
- converter scalar/element type 검사를 제거하면 9-case test 중 coercible cases가 즉시 실패합니다.
- finalization보다 먼저 commit된 mutation은 이번 tests가 보장합니다. finalization이 먼저 commit된 뒤 Account/client lifecycle mutation이 기존 active rows까지 revoke하는 orchestration은 기존 Task 6 repository revoke contract가 담당하며, 모든 실제 lifecycle event 연결은 Task 12 범위입니다.
- test-only barrier는 Spring bean을 바꾸거나 token generator를 production에 노출하지 않으며, `@MockitoSpyBean` test context 안에만 존재합니다.
- Task 8 production JWK/test-only RS256 경계, Task 1 issuer-origin 501 placeholder, Task 5 Basic/public auth, C1 consent round-trip, other-grant pass-through를 focused/full suite에서 그대로 보존했습니다.

## Review fix round 3

### 상태와 변경 파일

DONE

- typed exchange identity: `OAuthAuthorizationCodeExchangeBinding`
- finalization contract/locking: `OAuthAuthorizationRepository`, `OAuthAuthorizationRepositoryAdapter`
- consume producer and candidate mapper: `AtomicAuthorizationCodeClientAuthenticationProvider`,
  `OAuthAuthorizationMapper`
- fail-closed routing/cache lifecycle: `SpringOAuth2AuthorizationService`
- contract, real-service, and PostgreSQL tests: `SpringOAuth2AuthorizationServiceTest`,
  `OAuthAuthorizationPersistenceIntegrationTest`

### C1: candidate-based fail-closed routing

Authorization-code token save는 cache 존재 여부로 분류하지 않습니다. candidate 자체가 original
`authorization_code` grant와 issued access/refresh token shape를 가지며 persisted-source provenance가 없을 때
finalization candidate입니다. 이 경로는 verified request-scope consume cache가 반드시 있어야 하고, 없거나
stale/malformed/reused/mismatched이면 표준 400 `invalid_grant`이며 일반 `save`로 내려가지 않습니다.

SAS 1.5.8 provider bytecode를 다시 확인한 결과 두 특성이 함께 존재합니다.

- 성공한 authorization-code provider는 최종 `save` 직전에 code token을 invalidated 처리합니다.
- refresh provider는 `OAuth2Authorization.from(existing)`으로 기존 authorization을 복사하므로 stored
  `authorizationGrantType`은 original `authorization_code` 그대로입니다.

따라서 code invalidated 여부만으로 refresh를 구분하면 실제 code final save가 fail-open되고, grant type만으로
구분하면 실제 refresh를 code finalizer가 가로챕니다. project service가 DB에서 복원한 Spring authorization에만
private typed `PERSISTED` provenance를 in-memory attribute로 붙이고, SAS가 이를 candidate에 복사하도록 했습니다.
이 값은 allowlisted mapper에 포함되지 않아 DB/JSON에 저장되지 않습니다. consumed-code cache에서 반환하는
authorization은 이 marker가 없으므로 invalidated code를 가진 실제 final candidate도 올바르게 finalizer로 갑니다.
marker가 없는 임의/malformed candidate는 invalidated code를 스스로 표시해도 cache 필수 검사를 우회하지 못합니다.

cache는 저장 전에 reconstructed Spring object와 typed binding의 exact equality를 확인합니다. replacement 검증을
시작할 때 이전 cache를 먼저 제거하며, `save`는 normal/final success와 모든 protocol/system failure에서 `finally`로
cache를 제거합니다. 실제 persisted refresh-shaped candidate와 explicit non-code grant는 cache가 있어도 normal path를
사용하고 cache를 지웁니다.

### C2: consumed, candidate, locked DB의 exact typed binding

`OAuthAuthorizationCodeExchangeBinding`은 arbitrary map이나 serialization이 아닌 immutable allowlisted record입니다.
다음 값을 담습니다.

- consumed code SHA-256 hash, issued/expires instants
- authorization ID, internal registered-client ID, principal name, grant type, authorized scopes
- authorization URI, public client ID, exact redirect URI, requested scopes, RP state, nonce,
  PKCE challenge와 method

atomic consume callback이 locked code/authorization/current client에서 이 binding을 순수 메모리 연산으로 만들며,
callback에는 외부 I/O를 추가하지 않았습니다. mapper는 final candidate에서 같은 typed binding을 만들고 code/access/
refresh raw value는 즉시 hash metadata로 바꿉니다. service가 consumed == candidate를 먼저 확인하고, final
`REQUIRES_NEW` transaction은 기존 `code → authorization → client → company → account → user` 순서로 다시 잠근 뒤
locked DB snapshot에서 binding을 새로 capture하여 다음을 모두 요구합니다.

`consumed binding == candidate binding == locked DB binding`

access-token scopes도 locked authorized scopes와 exact equality여야 하며 access/refresh metadata의 authorization
ownership도 다시 확인합니다. round 2의 confidential BCrypt secret/current client/company/account/user checks,
exact redirect/S256 checks, token-only insert/flush, stale parent non-overwrite는 그대로 유지됩니다.

### strict TDD / mutation 증거

1. C1 initial RED
   - cache 없는 code token save, single-use cache 재사용, refresh-shaped+cache routing tests를 먼저 추가했습니다.
   - 기존 구현에서 20 tests 중 3 failures였습니다: missing cache는 일반 save, reused cache는 일반 save,
     refresh는 cache 존재만으로 code finalizer에 들어갔습니다.
2. candidate invalidation bypass RED
   - marker가 없는 candidate가 code를 invalidated 표시해 cache requirement를 우회하지 못해야 하는 test는
     기존 invalidated-based 분기에서 24 tests 중 1 failure였습니다.
3. failed cache replacement RED
   - valid cache 뒤 malformed replacement가 실패했을 때 이전 cache까지 없어져야 하는 test는 3 실행 중
     1 failure였습니다. replacement 검증 전에 clear하도록 바꾼 뒤 GREEN입니다.
4. C2 API RED
   - exact typed binding tests를 먼저 추가한 `compileTestJava`는
     `OAuthAuthorizationCodeExchangeBinding` 부재로 29 compile errors였습니다.
5. locked-binding mutation
   - DB binding equality 한 줄을 임시 제거한 실행은 18 tests 중 13 failures였습니다. code times, principal,
     grant, authorized scopes, authorization URI/client ID/redirect/request scopes/RP state/nonce/challenge/method
     mismatch가 token을 발급해 assertion을 실패시켰습니다. 비교를 즉시 복원했습니다.
   - 첫 mutation 실행은 test fixture에 registered redirect row가 없어 false-green이었습니다. fixture에 exact
     callback row와 positive exact-match finalization test를 추가한 뒤 mutation을 다시 실행해 위 RED를 확인했고,
     복원 후 focused/full GREEN을 재검증했습니다.

### GREEN / 최종 검증

focused command:

`./gradlew.bat test --rerun-tasks --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AtomicAuthorizationCodeClientAuthenticationProviderTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthAuthorizationPersistenceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*SecurityChainIsolationIntegrationTest" --tests "*ModuleBoundaryTest"`

- BUILD SUCCESSFUL in 51s
- 8 suites, 107 tests, 0 failures, 0 errors, 0 skipped
- service routing/cache 23 tests, PostgreSQL persistence/binding 50 tests, 실제 HTTP filter-chain/race 16 tests 포함

fresh full backend `./gradlew.bat test --rerun-tasks`:

- BUILD SUCCESSFUL in 2m 50s
- 41 suites, 286 tests, 0 failures, 0 errors, 0 skipped
- production serialization/default-typing/raw-value-column 금지 scan 0건
- 최종 XML/HTML raw code/access/refresh/verifier/confidential/race fixture scan 0건
- `git diff --check` 오류 0건

### self-review / 남은 경계

- cache mandatory 분기를 제거하면 missing/reused tests가 일반 save를 관찰하고 실패합니다. final save의
  consumed/candidate equality를 제거하면 spoof unit의 no-finalizer assertion이 실패합니다.
- locked DB equality를 제거하면 위 13개 mutation이 실제 token insert를 관찰합니다. access scope 또는 token
  ownership check는 각각 독립 parameterized case가 방어합니다.
- persisted provenance는 SAS 1.5.8 `from(existing)` attribute copy 계약을 사용하며 unit refresh characterization와
  실제 authorization-code HTTP suite가 양쪽 routing을 검증합니다. Task 11 refresh rotation을 구현할 때 이
  provenance와 project finalization 경계를 함께 재검토해야 합니다.
- Task 12 lifecycle wiring은 확장하지 않았고 round 2의 stable lock-order 규칙을 유지합니다. Task 8 JWK/test-only
  RS256 및 pre-Task8 501 경계도 유지됩니다.
