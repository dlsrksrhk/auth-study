# Task 8 구현 보고서

## 상태

DONE

BASE는 `d0cd8ebe4eef9c7157744731414e84feb882cab0`이고 최초 Task 8 구현 커밋은
`acfb8c2707e8a88ba79a29f6e4a661d5e698a14b`입니다. 리뷰 수정 round 1 hash는 이 보고서를
포함하는 별도 커밋이며 상위 agent handoff에 기록합니다.

## 구현 범위

- `V9__oauth_protocol_key_event.sql`
  - `oauth_signing_key`에 RS256-only algorithm constraint, `ACTIVE` / `VERIFICATION_ONLY`
    lifecycle constraint, kid unique, 정확히 하나의 ACTIVE만 허용하는 partial unique index,
    verification retention index를 추가했습니다.
  - `oauth_protocol_event`에 비민감 protocol metadata만 넣을 수 있는 production table과
    occurred/correlation/client/subject/account/company/type-outcome 조회 index를 추가했습니다.
    event writer와 lifecycle wiring은 Task 12 범위로 남겼습니다.
- signing-key domain / persistence
  - Spring/JPA import가 없는 `OAuthSigningKey`와 `OAuthSigningKeyRepository` port를 추가했습니다.
  - encrypted private bytes는 defensive copy하며 ACTIVE에는 retiredAt이 없고
    VERIFICATION_ONLY에는 retiredAt이 반드시 있는 불변식을 domain과 DB가 함께 검사합니다.
  - adapter는 PostgreSQL transaction advisory lock과 partial unique index를 함께 사용합니다.
    bootstrap은 동일 winner를 반환하고, rotation은 기존 ACTIVE를 retire하고 새 ACTIVE를 넣는
    한 transaction이므로 concurrent bootstrap/rotation에도 commit된 상태는 ACTIVE 하나입니다.
- private-key wrapping
  - `OAuthPrivateKeyCipher`는 AES-256-GCM, 매 encrypt의 random 96-bit nonce, 128-bit tag를 사용합니다.
  - envelope는 `v1.<wrapping-key-id>.<nonce>.<ciphertext>`이고, versioned context, wrapping key id,
    signing kid, RS256 algorithm, public JWK를 length-delimited AAD로 묶어 row/key substitution을 막습니다.
  - Base64 key는 정확히 32 bytes여야 하며 tamper, wrong key, wrong kid/public JWK, malformed envelope는
    모두 동일한 generic decrypt 오류가 됩니다.
  - dev/test YAML에는 서로 다른 committed LOCAL-ONLY wrapping key만 두며 RSA private JWK는 두지 않았습니다.
- project JWK / decoder
  - application bootstrap은 RSA-2048 private JWK를 생성해 즉시 AES-GCM 암호화한 bytes만 저장합니다.
    `public_jwk`는 public parameters만 저장합니다.
  - dynamic `JWKSource`는 ACTIVE private+public signing key를 첫 번째로 제공하고, 마지막 ID/Access
    token lifetime 안에 있는 VERIFICATION_ONLY key는 public form으로만 제공합니다.
  - JWKS endpoint의 Nimbus serialization이 private parameters를 제거하는지 실제 HTTP로 검증했습니다.
  - `oauthJwtDecoder`는 project JWKSource, issuer validator와 exact RS256 header validator를 사용합니다.
    HR `hrJwtEncoder` / `hrJwtDecoder`는 explicit qualifier와 HR chain wiring으로 분리했고 OAuth code는
    `AppSecurityProperties` 또는 HS256 key를 참조하지 않습니다.
- OIDC / claims
  - explicit issuer, authorize/token/JWKS/UserInfo/logout endpoint settings와 OIDC Discovery를 활성화했습니다.
    JWK/properties/decoder가 없는 context는 기존 guarded placeholder 경계를 유지합니다.
  - `OAuthTokenCustomizer`가 active kid와 RS256 header를 고정합니다.
  - ID Token claim map은 `iss/sub/aud/exp/iat/auth_time/nonce`만, Access Token은
    `iss/sub/aud/client_id/scope/jti/iat/exp`만 남깁니다.
  - `sub`는 authorization의 account와 같은 `OAuthSubjectService`의 opaque UUID source를 사용하며
    ID/access/UserInfo contract source를 공유합니다. account Long id는 token subject로 사용하지 않습니다.
  - ID audience는 client id, access audience는 `auth-study-userinfo`, scope는 정렬된 space-delimited string입니다.
    ID/access 모두 HR role/company/department/position/title claim을 포함하지 않습니다.
  - code 60s, ID 5m, access 5m, refresh absolute 7d 설정은 유지했습니다.

Task 9 login/consent UI, Task 10 UserInfo payload, Task 11 refresh rotation, Task 12 event writer는 구현하지 않았습니다.

## 엄격 TDD 증거

### 최초 RED

Production code 전에 다음 4개 test-first 파일을 작성했습니다.

- `OAuthSigningKeyTest`
- `OAuthPrivateKeyCipherTest`
- `OAuthSigningKeyPersistenceIntegrationTest`
- `OidcDiscoveryAndTokenContractIntegrationTest`

Command:

`./gradlew.bat test --tests "*OidcDiscoveryAndTokenContractIntegrationTest"`

Result:

- `compileTestJava FAILED`
- `OAuthSigningKey`, `OAuthSigningKeyRepository`, `OAuthPrivateKeyCipher` 부재로 41 compile errors
- 따라서 기존 임시 JWK/endpoint가 새 DB key ring 및 공개 token 계약을 만족하지 못하는 실제 RED였습니다.

### 이후 RED와 최소 수정

1. 첫 context 실행은 multiple constructor가 있는 configuration-properties record에서
   `NoSuchMethodException: OAuthSecurityProperties.<init>()`로 실패했습니다. canonical constructor를
   `@ConstructorBinding`으로 명시했습니다.
2. 실제 authorize→token 실행은 ID `auth_time`에 Java `Instant`를 custom claim으로 넣었을 때 Nimbus shaded
   Gson의 `InaccessibleObjectException`으로 실패했습니다. OIDC NumericDate로 serialize되는 `Date`만 넣었습니다.
3. Task 1/5/7 focused regression은 pre-Task8 test-only JWK와 production JWK가 함께 등록되어
   `NoUniqueBeanDefinitionException`, 그리고 활성화된 `/oauth2/token`을 501로 기대해 52 tests 중 21 failures였습니다.
   임시 JWK만 제거해 기존 tests도 실제 project key ring을 사용하게 했고 issuer-origin exact request는 표준
   protocol 400을 기대하도록 전환했습니다. Client auth/PKCE service와 in-memory collaborator 범위는 바꾸지 않았습니다.

## GREEN / 검증 증거

Task 8 focused tests:

`./gradlew.bat test --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeyTest" --tests "*OAuthPrivateKeyCipherTest"`

- `BUILD SUCCESSFUL in 24s`
- migration columns/indexes, at-rest ciphertext, public-only JWK, HR secret 비재사용, tamper/wrong-key,
  fresh nonce, concurrent 16-call bootstrap single winner, rotation/verification expiry를 포함합니다.

실제 OIDC contract:

`./gradlew.bat test --tests "*OidcDiscoveryAndTokenContractIntegrationTest"`

- `BUILD SUCCESSFUL in 23s`
- 실제 Discovery → authenticated authorize → PKCE token exchange
- discovery issuer/endpoints/RS256 support, JWKS active+verification-only와 private-field 부재
- ID/access signature, active kid, exact allowlist claims, issuer/audience/time/nonce/auth_time/client_id/scope/jti,
  opaque subject equality와 HR claim 부재
- valid project decoder, wrong issuer와 RS512 rejection

Task 1/5/7 및 HR focused regression:

`./gradlew.bat test --rerun-tasks --tests "*OidcDiscoveryAndTokenContractIntegrationTest" --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeyTest" --tests "*OAuthPrivateKeyCipherTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*SecurityChainIsolationIntegrationTest" --tests "*AuthenticationIntegrationTest" --tests "*ModuleBoundaryTest"`

- `BUILD SUCCESSFUL in 1m 3s`

Fresh full backend:

`./gradlew.bat test --rerun-tasks`

- `BUILD SUCCESSFUL in 3m 2s`
- JUnit XML: 45 suites, 297 tests, 0 failures, 0 errors, 0 skipped

Static verification:

- `git diff --check`: 오류 0건; Windows LF→CRLF checkout warning만 출력
- `oauth.domain` Spring/JPA import scan: 0건
- OAuth production의 `AppSecurityProperties`, HS256, HR encoder/decoder coupling scan: 0건
- resources/main의 PEM/private-key/private-JWK marker scan: 0건
- V9 protocol-event raw password/secret/code/token/verifier/cookie/request-body column scan: 0건
- committed dev/test wrapping key equality: false

## self-review

- active partial unique index를 제거하면 direct second-active insert test가 실패합니다. advisory lock 또는
  bootstrap winner reuse를 제거하면 16-call concurrency test가 여러 result/active row를 관찰합니다.
- AES nonce 재사용, AAD에서 kid/public JWK 제거, wrapping key 재사용 또는 tag 검증 우회를 하면 fresh-nonce,
  wrong-context, HR-secret, tamper tests 중 하나 이상이 실패합니다.
- private JWK를 그대로 저장하거나 public JWK에 private parameters를 남기면 at-rest/JWKS assertions와 scan이 실패합니다.
- verification-only key를 private form으로 반환하거나 retention cutoff를 제거하면 public-only/expiry test가 실패합니다.
- token allowlist에서 clear를 제거하거나 HR claim을 넣으면 exact claim-name assertions가 실패합니다.
  active kid, algorithm, issuer/audience/sub/times/nonce/auth_time/client_id/scope/jti 중 하나를 바꾸어도 실제 signed-token
  contract가 실패합니다.
- HR decoder/encoder qualifier를 제거하면 bean ambiguity 또는 HR authentication regression이 실패하며,
  OAuth decoder를 기본 SAS family decoder로 되돌리면 wrong issuer/RS512 tests가 실패합니다.

## 우려와 의도적 경계

- committed wrapping keys는 로컬 학습/dev/test 전용입니다. 운영 환경에는 외부 secret manager/HSM과 별도
  key rotation/backup 정책이 필요합니다.
- verification retention은 현재 고정된 ID/access 최대 TTL(5분)에서 계산합니다. 과거에 더 긴 TTL로 발급한 뒤
  설정을 줄이는 운영 migration은 별도의 retained-until metadata/migration이 필요합니다.
- protocol event는 production migration만 추가했고 원문 credential을 받을 writer API는 만들지 않았습니다.
  Task 12가 비민감 allowlist writer와 lifecycle wiring을 추가해야 합니다.
- UserInfo endpoint payload는 Task 10 범위라 구현하지 않았습니다. 이번 Task는 ID/access가 기존 opaque
  `OAuthSubjectService`를 사용하도록 고정해 이후 UserInfo `sub`와 같은 source를 쓰는 경계만 제공합니다.

---

## 리뷰 수정 round 1 (2026-08-24)

이 절은 최초 보고서의 signing source/lifecycle 설명을 대체합니다. 리뷰에서 확인된 Important 4건과
범위 안의 Minor를 별도 수정 커밋으로 처리했습니다.

### 수정 결과

- issuance/rotation 선형화
  - `OAuthTokenCustomizer`가 ACTIVE kid를 따로 읽는 경로를 제거했습니다.
  - signing-only `OAuthSigningKeySnapshotSource`가 단 한 번의 `requireActive()` 결과에서 kid와 정확히
    일치하는 private RSA JWK를 검증해 불변 snapshot으로 반환합니다.
  - project `OAuthSigningJwtEncoder`가 그 snapshot의 kid로 JOSE header를 덮어쓰고, 같은 snapshot의
    private key 하나만 든 local Nimbus encoder로 서명합니다. 따라서 rotation이 customizer 이후와 실제
    encode 사이에 완료되어도 old ACTIVE 시점 또는 new ACTIVE 시점 중 하나로 선형화되며 kid/private
    mismatch나 임의의 retired-key 선택 경로가 없습니다.
- private-key 격리
  - application `JWKSource<SecurityContext>`는 ACTIVE와 retention 안의 VERIFICATION_ONLY를 모두
    public RSA JWK로만 제공합니다. JWKS endpoint와 project decoder에는 이 public source만 주입됩니다.
  - private JWK는 package-private signing snapshot 경계 안에서만 복호화됩니다. 복호화 byte array는
    parse 성공/실패 모두 `finally`에서 zero-fill하고, bootstrap generation의 plaintext byte array도
    encrypt 직후 zero-fill합니다. cipher encrypt가 별도 plaintext copy를 만들던 코드도 제거했습니다.
  - decoder는 broad SAS helper 대신 Nimbus `JWSVerificationKeySelector(RS256, publicSource)`를 직접
    사용해 signature verification 전에 RSA/RS256 후보만 선택하며 issuer validator도 유지합니다.
- exactly-one ACTIVE/fail-closed lifecycle
  - repository port의 arbitrary `save`와 optional `findActive`를 제거하고 `bootstrapIfAbsent`, `rotate`,
    `requireActive`만 남겼습니다.
  - `requireActive`는 `maxResults(1)` 없이 전체 ACTIVE 결과 수가 정확히 1인지 검사합니다. 0 또는 통제된
    corruption fixture의 2 ACTIVE 모두 generic exact-one failure가 됩니다.
  - bootstrap/rotation은 PostgreSQL advisory transaction lock으로 직렬화되고 partial unique index가
    max-one을 보장합니다. rotation은 기존 key retire와 새 ACTIVE insert가 같은 transaction이며 candidate
    generation 실패 시 기존 ACTIVE retirement가 rollback됩니다.
  - ACTIVE가 없거나 private/public material이 손상되면 signing encoder가 예외를 전파하므로 kid 없는
    token으로 fallback하지 않습니다. public JWKS/decoder source도 ACTIVE 0건을 숨기지 않습니다.
- PostgreSQL/HTTP 계약 증거
  - bootstrap-vs-rotation 동시 실행 후 exact-one ACTIVE와 candidate 비사용을 검증합니다.
  - 실제 MockMvc authorize→token filter에서 signing `requireActive`가 old key를 반환한 직후 latch로
    rotation을 완료시킵니다. token endpoint는 200이며 access/ID 모두 각 header kid의 회전 후 public
    JWKS key로 검증되고 project decoder도 통과합니다. 적어도 하나는 old kid로 서명되어 retention을
    실제로 검증합니다.
  - `openid` 미요청 code exchange는 access token만 반환하고 ID token을 반환하지 않습니다.
  - public PKCE client와 BCrypt secret/HTTP Basic confidential client 모두 실제 ID token allowlist,
    opaque subject, audience, nonce, TTL, RS256/kid/signature 계약을 검증합니다.
  - code 발급 후 ACTIVE row를 제거한 실제 token HTTP path가 root exact-one error로 fail closed하고,
    fixture는 `finally`에서 lifecycle bootstrap으로 복원됩니다.
- V9 Minor
  - metadata constraint는 `(metadata IS NULL OR jsonb_typeof(metadata) = 'object')`이고 JSON literal
    `null` insert가 PostgreSQL에서 거절되는 것을 검증합니다.
  - `(authorization_id, occurred_at DESC)` index를 추가했습니다.
  - protocol event의 account/company/authorization FK는 `ON DELETE SET NULL`로 history를 보존합니다.
    `client_id`와 opaque `subject`는 사건 당시 외부 식별자를 보존해야 하고 향후 client/account 삭제에
    event row가 종속되면 안 되므로 의도적으로 FK를 두지 않았습니다. Task 12 writer도 이 선택을 따라야 합니다.
  - 기존 default-dev 동작은 바꾸지 않았고 dev wrapping key의 `LOCAL-ONLY` 주석과 test 전용 별도 key를
    유지했습니다.

### round 1 TDD RED → GREEN

최초 RED:

`./gradlew.bat test --tests "*OAuthSigningKeySourceTest" --tests "*OAuthSigningKeyPersistenceIntegrationTest"`

- `compileTestJava FAILED`
- `requireActive`, `OAuthSigningKeySnapshotSource`, `OAuthSigningKeySnapshot`, public-only source API 부재로
  18 compile errors였습니다.
- 최소 lifecycle/source 구현 뒤 기존 OIDC test가 public source에서 private key를 선택하려다
  `NoSuchElementException`으로 RED가 되었고, decoder 음성 계약을 project encoder를 통해 서명하도록
  바꾸어 public selector에서 private material을 추출하는 테스트 경로도 제거했습니다.

Focused GREEN:

`./gradlew.bat test --rerun-tasks --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeySourceTest" --tests "*OAuthSigningKeyTest" --tests "*OAuthPrivateKeyCipherTest" --tests "*OidcDiscoveryAndTokenContractIntegrationTest"`

- `BUILD SUCCESSFUL in 42s`

Task 1/5/7/HR 및 Task 8 fresh 회귀:

`./gradlew.bat test --rerun-tasks --tests "*OidcDiscoveryAndTokenContractIntegrationTest" --tests "*OAuthSigningKeyPersistenceIntegrationTest" --tests "*OAuthSigningKeySourceTest" --tests "*OAuthSigningKeyTest" --tests "*OAuthPrivateKeyCipherTest" --tests "*AuthorizationCodePkceIntegrationTest" --tests "*OAuthClientAuthenticationIntegrationTest" --tests "*SpringRegisteredClientRepositoryTest" --tests "*SecurityChainIsolationIntegrationTest" --tests "*AuthenticationIntegrationTest" --tests "*ModuleBoundaryTest"`

- `BUILD SUCCESSFUL in 1m 10s`
- 64 tests, 0 failures

Fresh full backend:

`./gradlew.bat test --rerun-tasks`

- `BUILD SUCCESSFUL in 3m 8s`
- JUnit XML: 46 suites, 309 tests, 0 failures, 0 errors, 0 skipped

회귀 중 기존 AES tamper test가 envelope의 마지막 Base64 문자를 XOR해 padding에 쓰이지 않는 bit만
바뀌는 경우 ciphertext가 실제로 변하지 않는 비결정성을 한 번 드러냈습니다. decoded ciphertext의 첫
byte를 변조하고 다시 envelope를 만드는 결정적 test로 수정했고, 단독 AES test, 64-test 회귀, full suite를
모두 처음부터 다시 실행해 위 GREEN 결과를 얻었습니다.

Static verification:

- `git diff --check`: 오류 0건(LF→CRLF checkout warning만 존재)
- `oauth.domain` Spring/JPA import: 0건
- OAuth production의 `AppSecurityProperties`/HS256/HR encoder-decoder coupling: 0건
- main/test resources private-key/private-JWK marker: 0건
- committed dev/test wrapping key equality: false

### round 1 self-review와 남은 우려

- header kid와 private key의 소유권을 encoder 한 곳으로 모았기 때문에 customizer/encoder 사이 race를
  구조적으로 제거했습니다. stale kid를 전달해도 encoder가 exact snapshot kid로 덮어쓰고 같은 public
  key로 signature가 검증되는 unit test가 이 경계를 고정합니다.
- public source test는 반환된 모든 JWK가 `isPrivate() == false`임을 검사하고, 실제 HTTP JWKS JSON도
  RSA private fields가 없음을 검사합니다. acceptance test는 public/general selector에서 private JWK를
  추출하지 않습니다.
- direct DB corruption test는 partial unique index를 통제 하에 잠시 제거해 second ACTIVE를 만든 뒤
  exact-one failure를 확인하고 `finally`에서 row 삭제와 동일 index 재생성을 수행합니다.
- retained old public key의 기간은 여전히 현재 ID/access 최대 TTL인 5분입니다. 과거에 더 긴 TTL로 발급한
  뒤 설정을 축소하는 운영 migration에는 최초 보고서와 같이 별도 retained-until metadata가 필요합니다.
- plaintext Java `String`은 Nimbus JSON parser API 제약상 즉시 zero-fill할 수 없지만, 원본 decrypted byte
  array는 성공/실패 모두 즉시 wipe됩니다. 운영에서는 외부 KMS/HSM 기반 signer로 private material의 JVM
  노출 자체를 없애는 것이 다음 보안 강화 지점입니다.
- Task 9 UI/consent, Task 10 UserInfo payload, Task 11 refresh rotation, Task 12 event writer는 수정하지 않았습니다.
