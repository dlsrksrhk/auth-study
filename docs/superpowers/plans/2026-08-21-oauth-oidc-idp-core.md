# OAuth/OIDC IdP Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 Spring Boot 백엔드 안에 Spring Authorization Server 기반 OAuth 2.0/OIDC IdP 코어를 추가하고, 기존 HR JWT 인증과 완전히 분리된 client·subject·consent·authorization·token·RS256 키·프로토콜 이력을 구현합니다.

**Architecture:** com.sweet.authstudy.oauth 기능 패키지는 domain/application/infrastructure/presentation 계층을 따릅니다. Spring Authorization Server는 표준 endpoint와 protocol object 변환을 담당하고, 프로젝트 코드는 회사 소유 client, 동의, opaque subject, scope별 UserInfo claim, refresh family와 폐기 정책을 소유합니다. IdP 브라우저 흐름은 전용 서버 세션과 서버 렌더링 화면을 사용하고 기존 /api/v1 체인은 stateless HR JWT를 유지합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Authorization Server 1.5.x, Spring Security, Thymeleaf, Spring Data JPA, Flyway, PostgreSQL 17, Nimbus JOSE JWT, JUnit 5, MockMvc, Testcontainers

**Spec:** docs/superpowers/specs/2026-08-21-oauth-oidc-idp-design.md

## Global Constraints

- issuer는 http://idp.localhost:8080 하나이며 tenant를 hostname이나 요청 파라미터로 결정하지 않습니다.
- HR API의 HS256 Access Token, Refresh Token 저장소, claim, 수명과 OAuth/OIDC의 RS256 key ring, authorization, token, claim, 수명을 공유하지 않습니다.
- OAuth client는 회사 소유입니다. COMPANY_ADMIN은 자기 회사 client만 관리하고 SYSTEM_ADMIN만 전체 조회와 TRUSTED_FIRST_PARTY 설정을 할 수 있습니다.
- client_id, account별 sub, secret 원문, code 원문, refresh token 원문은 opaque 값입니다. secret은 BCrypt, code/access/refresh 원문은 SHA-256 hash만 저장합니다.
- Authorization Code와 public/confidential client 모두 PKCE S256을 요구합니다. redirect URI는 exact match이며 개발 환경의 http는 *.localhost만 허용합니다.
- Access Token과 ID Token은 RS256이며 kid를 포함합니다. HR 조직·역할 claim은 ID/Access Token에 넣지 않고 UserInfo에만 제공합니다.
- Code 60초, ID/Access Token 5분, refresh family 7일, IdP idle session 30분/absolute 8시간입니다.
- 원문 secret·code·token·PKCE verifier를 DB, audit, protocol event와 애플리케이션 로그에 기록하지 않습니다.
- 각 작업은 실패 테스트 → 실패 확인 → 최소 구현 → 통과 확인 → 명시적 파일만 커밋 순서입니다.
- frontend/.idea는 사용자 소유의 기존 untracked 파일이므로 스테이징하지 않습니다.

---

## File and Responsibility Map

    backend/src/main/java/com/sweet/authstudy/oauth/
      domain/          client, subject, consent, authorization, refresh-family 불변식과 repository port
      application/     관리 command, authorization orchestration, claim 조회, 폐기 use case
      infrastructure/  JPA adapter, Spring Authorization Server adapter, RS256/JWK, hash/secret 구현
      presentation/    protocol filter 연결, login/password/consent/error 화면
    backend/src/main/java/com/sweet/authstudy/identity/application/
      CredentialAuthenticationService.java
      CredentialAuthenticationResult.java
      OAuthGrantRevocationPort.java
    backend/src/main/java/com/sweet/authstudy/authorization/
      SecurityConfig.java                  기존 HR stateless 체인
      AuthorizationServerSecurityConfig.java 새 IdP 세션 체인
    backend/src/main/resources/db/migration/
      V7__oauth_client_subject.sql
      V8__oauth_authorization_consent.sql
      V9__oauth_protocol_key_event.sql
    backend/src/main/resources/templates/idp/
      login.html password.html consent.html error.html

계층 의존 방향은 presentation → application → domain이고 infrastructure가 domain/application port를 구현합니다. oauth.domain은 Spring, JPA, Spring Authorization Server 타입을 import하지 않습니다.

---

### Task 1: 의존성, 설정 계약과 분리된 SecurityFilterChain 골격

**Files:**
- Modify: backend/build.gradle.kts
- Modify: backend/src/main/resources/application.yaml
- Modify: backend/src/main/resources/application-dev.yaml
- Modify: backend/src/test/resources/application-test.yaml
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthSecurityProperties.java
- Create: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/SecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/authorization/SecurityChainIsolationIntegrationTest.java

**Interfaces:**
- Consumes: AppSecurityProperties와 기존 /api/v1 SecurityFilterChain
- Produces: OAuthSecurityProperties, @Order(1) IdP chain, @Order(2) HR API chain

- [ ] **Step 1: endpoint별 state policy를 고정하는 실패 테스트 작성**

    @Test
    void idpLoginCreatesOnlyIdpSessionWhileApiRemainsStateless() throws Exception {
        mockMvc.perform(get("/idp/login"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("IDP_AUTH_SESSION"));
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("IDP_AUTH_SESSION"));
    }

- [ ] **Step 2: 테스트가 404 또는 누락 dependency로 실패하는지 확인**

Run: cd backend; .\gradlew.bat test --tests "*SecurityChainIsolationIntegrationTest"

Expected: FAIL because Authorization Server, Thymeleaf와 /idp/login chain이 아직 없습니다.

- [ ] **Step 3: 의존성과 typed properties 추가**

build.gradle.kts에 다음을 추가합니다.

    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

OAuthSecurityProperties는 다음 계약을 정확히 제공합니다.

    @ConfigurationProperties("app.oauth")
    public record OAuthSecurityProperties(
            URI issuer,
            Duration authorizationCodeTtl,
            Duration accessTokenTtl,
            Duration idTokenTtl,
            Duration refreshTokenTtl,
            Duration sessionIdleTimeout,
            Duration sessionAbsoluteTimeout,
            String sessionCookieName,
            String userInfoAudience) {}

dev/test 값은 issuer=http://idp.localhost:8080, code=60s, access/id=5m, refresh=7d, idle=30m, absolute=8h, cookie=IDP_AUTH_SESSION, audience=auth-study-userinfo로 둡니다.

- [ ] **Step 4: 두 filter chain의 matcher와 상태 정책 구현**

AuthorizationServerSecurityConfig는 @Order(1)로 /.well-known/**, /oauth2/**, /userinfo, /connect/logout, /idp/**를 소유하고 IF_REQUIRED session, CSRF, origin 검사와 form entry point /idp/login을 적용합니다. SecurityConfig의 기존 체인은 @Order(2), /api/v1/** matcher와 STATELESS를 유지합니다. 아직 protocol adapter가 없는 endpoint는 명시적인 501 handler로 연결해 accidental permitAll을 피합니다.

- [ ] **Step 5: 격리 테스트와 기존 보안 테스트 통과 확인**

Run: cd backend; .\gradlew.bat test --tests "*SecurityChainIsolationIntegrationTest" --tests "*SecurityConfig*"

Expected: PASS; /api/v1은 IDP_AUTH_SESSION을 만들거나 읽지 않습니다.

- [ ] **Step 6: 커밋**

    git add backend/build.gradle.kts backend/src/main/resources/application.yaml backend/src/main/resources/application-dev.yaml backend/src/test/resources/application-test.yaml backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthSecurityProperties.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/main/java/com/sweet/authstudy/authorization/SecurityConfig.java backend/src/test/java/com/sweet/authstudy/authorization/SecurityChainIsolationIntegrationTest.java
    git commit -m "feat: split authorization server security chain"

---

### Task 2: 토큰 발급과 분리된 자격 증명 인증 use case

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/identity/application/CredentialAuthenticationResult.java
- Create: backend/src/main/java/com/sweet/authstudy/identity/application/CredentialAuthenticationService.java
- Modify: backend/src/main/java/com/sweet/authstudy/identity/application/AuthenticationService.java
- Create: backend/src/test/java/com/sweet/authstudy/identity/application/CredentialAuthenticationServiceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/identity/application/AuthenticationServiceTest.java

**Interfaces:**
- Consumes: AccountRepository, CompanyRepository, UserRepository, PasswordEncoder, login lock policy
- Produces: authenticate(email,password) → accountId, companyId, userId, roles, mustChangePassword, authenticatedAt; JWT나 refresh token은 반환하지 않음

- [ ] **Step 1: 성공·잠금·dummy comparison 계약의 실패 테스트 작성**

    CredentialAuthenticationResult result = service.authenticate(
            new CredentialAuthenticationService.Command("admin@acme.local", "Valid1234!"));
    assertThat(result.accountId()).isEqualTo(accountId);
    assertThat(result.companyId()).isEqualTo(companyId);
    assertThat(result.roles()).contains(AccountRole.COMPANY_ADMIN);
    verifyNoInteractions(jwtTokenService, refreshTokenRepository);

잘못된 비밀번호, 잠긴 계정, 비활성 Company/User와 mustChangePassword 결과도 별도 테스트로 고정합니다.

- [ ] **Step 2: 새 type이 없어 컴파일 실패하는지 확인**

Run: cd backend; .\gradlew.bat test --tests "*CredentialAuthenticationServiceTest"

Expected: FAIL at compilation because the service and result do not exist.

- [ ] **Step 3: 기존 password 검증과 lock 갱신을 새 service로 이동**

    public record CredentialAuthenticationResult(
            long accountId,
            Long companyId,
            Long userId,
            Set<AccountRole> roles,
            boolean mustChangePassword,
            Instant authenticatedAt) {}

CredentialAuthenticationService는 현재 AuthenticationService의 resolveLoginSnapshot, dummy BCrypt 비교, row lock 재검증, failed-attempt 갱신과 상태 검증을 소유합니다. AuthenticationService.login은 이 결과를 받아 기존 HR JWT와 refresh token만 발급합니다.

- [ ] **Step 4: identity 회귀 테스트 통과 확인**

Run: cd backend; .\gradlew.bat test --tests "*CredentialAuthenticationServiceTest" --tests "*AuthenticationServiceTest"

Expected: PASS and existing HR login response/cookie behavior remains unchanged.

- [ ] **Step 5: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/identity/application/CredentialAuthenticationResult.java backend/src/main/java/com/sweet/authstudy/identity/application/CredentialAuthenticationService.java backend/src/main/java/com/sweet/authstudy/identity/application/AuthenticationService.java backend/src/test/java/com/sweet/authstudy/identity/application/CredentialAuthenticationServiceTest.java backend/src/test/java/com/sweet/authstudy/identity/application/AuthenticationServiceTest.java
    git commit -m "refactor: separate credential authentication from jwt issuance"

---

### Task 3: subject와 회사 소유 OAuth client 영속 모델

**Files:**
- Create: backend/src/main/resources/db/migration/V7__oauth_client_subject.sql
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthSubject.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthClient.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthClientStatus.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthClientTrust.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthClientRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthSubjectRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientJpaEntity.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientJpaRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientRepositoryAdapter.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSubjectJpaEntity.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSubjectJpaRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSubjectRepositoryAdapter.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientPersistenceIntegrationTest.java

**Interfaces:**
- Consumes: company.id와 account.id 외래키
- Produces: client aggregate와 account별 공개 opaque subject UUID

- [ ] **Step 1: migration 제약과 round-trip 실패 테스트 작성**

테스트는 client_id unique, oauth_subject.account_id unique, redirect (client_id, uri) unique, scope (client_id, scope) unique와 client secret 원문 부재를 information_schema 및 repository round-trip으로 확인합니다.

- [ ] **Step 2: migration 미존재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthClientPersistenceIntegrationTest"

Expected: FAIL because oauth_client and oauth_subject tables do not exist.

- [ ] **Step 3: V7과 domain aggregate 구현**

V7은 oauth_subject, oauth_client, oauth_client_secret, oauth_client_redirect_uri, oauth_client_scope를 생성합니다. OAuthClient는 id, companyId, clientId, displayName, status, trust, publicClient, version, redirectUris, postLogoutRedirectUris, scopes를 갖고 다음 검증을 생성 시 수행합니다.

    private static void validateRedirect(URI uri) {
        if (uri.getFragment() != null || uri.getUserInfo() != null) throw invalidRedirect();
        boolean localHttp = "http".equals(uri.getScheme())
                && uri.getHost() != null && uri.getHost().endsWith(".localhost");
        if (!"https".equals(uri.getScheme()) && !localHttp) throw invalidRedirect();
    }

허용 scope는 openid, profile, email, hr.company, hr.organization, hr.roles로 제한하고 openid를 필수로 둡니다.

- [ ] **Step 4: JPA adapter와 subject get-or-create 구현**

OAuthSubjectRepository는 findByAccountId와 save를 제공하고 application layer의 getOrCreate가 SecureRandom 기반 UUID를 한 번만 생성합니다. unique race 발생 시 재조회해 같은 sub를 반환합니다.

- [ ] **Step 5: persistence와 architecture 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthClientPersistenceIntegrationTest" --tests "*ArchitectureTest"

Expected: PASS; domain package에는 jakarta.persistence와 org.springframework import가 없습니다.

- [ ] **Step 6: 커밋**

    git add backend/src/main/resources/db/migration/V7__oauth_client_subject.sql backend/src/main/java/com/sweet/authstudy/oauth/domain backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientJpaEntity.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientJpaRepository.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientRepositoryAdapter.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSubjectJpaEntity.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSubjectJpaRepository.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSubjectRepositoryAdapter.java backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientPersistenceIntegrationTest.java
    git commit -m "feat: persist oauth clients and opaque subjects"

---

### Task 4: client 생성·수정·secret 회전 application service

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientCommands.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientView.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientSecretGenerator.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientService.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SecureOAuthClientSecretGenerator.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/application/OAuthClientServiceTest.java

**Interfaces:**
- Consumes: AuthenticatedAccount actor, companyCode, client form
- Produces: opaque client_id, public client 또는 BCrypt confidential secret, 원문 secret은 create/rotate 반환에서 정확히 한 번

- [ ] **Step 1: tenant·trust·one-time secret 실패 테스트 작성**

COMPANY_ADMIN은 자기 companyId client만 만들고 trust를 CONSENT_REQUIRED로만 설정할 수 있어야 합니다. SYSTEM_ADMIN은 client 로그인 주체가 될 수 없지만 모든 client를 조회하고 TRUSTED_FIRST_PARTY를 설정할 수 있어야 합니다. create/rotate 이후 repository에는 BCrypt hash만 남고 view 재조회에는 secret이 없어야 합니다.

- [ ] **Step 2: 서비스 미존재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthClientServiceTest"

Expected: FAIL at compilation.

- [ ] **Step 3: commands와 서비스 구현**

    public record CreateClient(
            String companyCode,
            String displayName,
            boolean publicClient,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            OAuthClientTrust trust) {}

    public record ClientSecretResult(OAuthClientView client, String oneTimeSecret) {}

client_id는 32 random bytes의 base64url, secret은 48 random bytes의 base64url입니다. confidential secret만 PasswordEncoder로 BCrypt하고 public client의 rotate는 INVALID_STATE입니다. secret row는 createdAt, expiresAt=null, revokedAt을 기록하며 rotate가 이전 active secret을 폐기합니다.

- [ ] **Step 4: 단위 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthClientServiceTest"

Expected: PASS including exact redirect, scope allowlist, actor boundary and secret non-retrievability.

- [ ] **Step 5: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientCommands.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientView.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientSecretGenerator.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientService.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SecureOAuthClientSecretGenerator.java backend/src/test/java/com/sweet/authstudy/oauth/application/OAuthClientServiceTest.java
    git commit -m "feat: manage company oauth clients"

---

### Task 5: Spring RegisteredClientRepository adapter와 client 인증

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringRegisteredClientRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientSecretPasswordEncoder.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/SpringRegisteredClientRepositoryTest.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OAuthClientAuthenticationIntegrationTest.java

**Interfaces:**
- Consumes: OAuthClientRepository aggregate
- Produces: RegisteredClientRepository.findById/findByClientId, auth method none 또는 client_secret_basic, authorization_code/refresh_token, PKCE required

- [ ] **Step 1: public/confidential mapping 실패 테스트 작성**

    RegisteredClient publicClient = repository.findByClientId("public-id");
    assertThat(publicClient.getClientAuthenticationMethods())
            .containsExactly(ClientAuthenticationMethod.NONE);
    assertThat(publicClient.getClientSettings().isRequireProofKey()).isTrue();

confidential client는 CLIENT_SECRET_BASIC, authorization_code와 refresh_token grant, exact redirect/post-logout URI, consent trust mapping을 확인합니다.

- [ ] **Step 2: adapter 미존재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*SpringRegisteredClientRepositoryTest"

Expected: FAIL at compilation.

- [ ] **Step 3: 양방향 mapping과 BCrypt matcher 구현**

Spring adapter의 save는 UnsupportedOperationException으로 막고 모든 관리 쓰기는 OAuthClientService로만 수행합니다. client secret encoder의 matches는 저장된 BCrypt hash를 검증하고 encode는 호출되지 않도록 합니다. 비활성 client는 조회 결과를 반환하지 않습니다.

- [ ] **Step 4: client 인증 통합 테스트**

token endpoint에 잘못된 secret은 invalid_client, public client의 secret 제시는 invalid_client, PKCE 없는 authorization request는 invalid_request를 반환하는지 검증합니다.

Run: cd backend; .\gradlew.bat test --tests "*SpringRegisteredClientRepositoryTest" --tests "*OAuthClientAuthenticationIntegrationTest"

Expected: PASS.

- [ ] **Step 5: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringRegisteredClientRepository.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthClientSecretPasswordEncoder.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/SpringRegisteredClientRepositoryTest.java backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OAuthClientAuthenticationIntegrationTest.java
    git commit -m "feat: adapt oauth clients to authorization server"

---

### Task 6: consent, authorization, code와 token 영속 모델

**Files:**
- Create: backend/src/main/resources/db/migration/V8__oauth_authorization_consent.sql
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthConsent.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthAuthorization.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthRefreshToken.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthConsentRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthAuthorizationRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationJpaEntity.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthConsentJpaEntity.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationRepositoryAdapter.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthConsentRepositoryAdapter.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationPersistenceIntegrationTest.java

**Interfaces:**
- Consumes: client, subject, granted scopes, nonce, PKCE challenge, token metadata
- Produces: oauth_consent/scope, oauth_authorization/code/access_token/refresh_token rows와 row-lock 기반 refresh family 상태

- [ ] **Step 1: 원문 비저장과 family 제약 실패 테스트 작성**

information_schema로 code_value, access_token_value, refresh_token_value 컬럼이 없음을 검증하고 code_hash/access_token_hash/refresh_token_hash가 unique임을 확인합니다. refresh family 재사용 시 family의 모든 active row가 revoked_at을 갖는 domain 테스트를 추가합니다.

- [ ] **Step 2: migration 부재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthAuthorizationPersistenceIntegrationTest"

Expected: FAIL because authorization tables do not exist.

- [ ] **Step 3: V8 schema와 aggregate 구현**

oauth_authorization은 id, registered_client_id, subject, principal_account_id, authorization_grant_type, authorized_scopes, attributes JSON, state, created_at, revoked_at을 둡니다. code/access/refresh 테이블은 SHA-256 hash, issued_at, expires_at, used_at/revoked_at과 필요한 metadata만 저장합니다. JSON serializer는 allowlist DTO만 사용하며 Java native serialization을 사용하지 않습니다.

- [ ] **Step 4: row lock repository와 concurrency 테스트 구현**

OAuthAuthorizationRepository는 findByCodeHashForUpdate, findRefreshByHashForUpdate, revokeFamily, revokeByAccountId, revokeByClientId를 제공합니다. 동시에 같은 refresh token을 사용하는 두 transaction 중 하나만 successor를 만들고 다른 하나는 reuse로 family 전체를 폐기해야 합니다.

- [ ] **Step 5: 통합 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthAuthorizationPersistenceIntegrationTest"

Expected: PASS, including no raw protocol secret columns.

- [ ] **Step 6: 커밋**

    git add backend/src/main/resources/db/migration/V8__oauth_authorization_consent.sql backend/src/main/java/com/sweet/authstudy/oauth/domain backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationJpaEntity.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthConsentJpaEntity.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationRepositoryAdapter.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthConsentRepositoryAdapter.java backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationPersistenceIntegrationTest.java
    git commit -m "feat: persist oauth grants consent and token metadata"

---

### Task 7: Spring authorization·consent service adapter와 Code/PKCE 교환

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationService.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationConsentService.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationMapper.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationServiceTest.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/acceptance/AuthorizationCodePkceIntegrationTest.java

**Interfaces:**
- Consumes: Spring OAuth2Authorization/OAuth2AuthorizationConsent
- Produces: project-owned persistence adapters and one-time 60초 Code with S256 validation

- [ ] **Step 1: serialization과 lookup 계약 실패 테스트 작성**

authorization id, code hash, access hash, refresh hash, principal name과 authorized scopes로 find가 동일 object contract를 재구성하는지 테스트합니다. code 원문은 mapper 입력 직후 hash되고 entity나 test log에 노출되지 않아야 합니다.

- [ ] **Step 2: adapter 미존재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*SpringOAuth2AuthorizationServiceTest"

Expected: FAIL at compilation.

- [ ] **Step 3: 두 Spring service adapter 구현**

OAuth2AuthorizationService.save/remove/findById/findByToken과 OAuth2AuthorizationConsentService.save/remove/findById를 repository port에 연결합니다. TokenType 조회는 hash 후 indexed column을 사용하고 알 수 없는 token type은 empty를 반환합니다.

- [ ] **Step 4: 실제 authorize→token PKCE 통합 테스트 작성 및 통과**

테스트는 discovery로 endpoint를 얻고 S256 challenge를 사용해 로그인된 IdP session으로 Code를 받은 뒤 정확한 verifier로 한 번만 교환합니다. 누락 verifier, plain method, 틀린 verifier, code 재사용, 60초 만료를 각각 invalid_grant/invalid_request로 고정합니다.

Run: cd backend; .\gradlew.bat test --tests "*SpringOAuth2AuthorizationServiceTest" --tests "*AuthorizationCodePkceIntegrationTest"

Expected: PASS.

- [ ] **Step 5: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationService.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationConsentService.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthAuthorizationMapper.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/infrastructure/SpringOAuth2AuthorizationServiceTest.java backend/src/test/java/com/sweet/authstudy/oauth/acceptance/AuthorizationCodePkceIntegrationTest.java
    git commit -m "feat: exchange authorization codes with pkce"

---

### Task 8: RS256 key ring, Discovery, JWKS와 token claim 계약

**Files:**
- Create: backend/src/main/resources/db/migration/V9__oauth_protocol_key_event.sql
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthSigningKey.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthSigningKeyRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSigningKeyJpaEntity.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSigningKeyRepositoryAdapter.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthJwkSourceConfiguration.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthTokenCustomizer.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OidcDiscoveryAndTokenContractIntegrationTest.java

**Interfaces:**
- Consumes: active signing key와 verification-only keys, OAuthSecurityProperties issuer/audience
- Produces: JWKSource<SecurityContext>, JwtDecoder, Discovery/JWKS, RS256 ID/Access Token

- [ ] **Step 1: 공개 계약 실패 테스트 작성**

    assertThat(discovery.get("issuer")).isEqualTo("http://idp.localhost:8080");
    assertThat(idHeader.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(idClaims.getSubject()).isEqualTo(userInfoSubject);
    assertThat(accessClaims.getAudience()).containsExactly("auth-study-userinfo");
    assertThat(accessClaims.getClaim("roles")).isNull();

ID Token의 iss/sub/aud/exp/iat/auth_time/nonce, Access Token의 iss/sub/aud/client_id/scope/jti/iat/exp, 두 header의 kid를 고정합니다.

- [ ] **Step 2: 현재 HS256 또는 endpoint 부재로 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OidcDiscoveryAndTokenContractIntegrationTest"

Expected: FAIL because OAuth RS256 key ring and discovery metadata are absent.

- [ ] **Step 3: V9 key table과 dev key bootstrap 구현**

V9은 oauth_signing_key와 oauth_protocol_event 테이블을 함께 생성합니다. oauth_signing_key는 kid, algorithm=RS256, encrypted_private_material, public_jwk, status(ACTIVE/VERIFICATION_ONLY), activated_at, retired_at을 둡니다. oauth_protocol_event는 occurred_at, correlation_id, event_type, outcome, client_id, subject, account_id, company_id, error_code, metadata jsonb와 조회 index를 가지며 원문 protocol credential 컬럼은 두지 않습니다. 로컬 학습 환경에서는 committed dev wrapping key로 private JWK를 AES-GCM 암호화합니다. active key는 정확히 하나이며 이전 key는 JWKS에 verification-only로 남습니다.

- [ ] **Step 4: JWKSource와 claim customizer 구현**

OAuthTokenCustomizer는 token type별 allowlist로 claim을 작성합니다. ID Token에는 HR claim을 넣지 않고 access audience는 auth-study-userinfo로 강제합니다. Spring Authorization Server settings에 issuer와 endpoint를 명시하고 OIDC를 활성화합니다.

- [ ] **Step 5: Discovery/JWKS/token contract 통과**

Run: cd backend; .\gradlew.bat test --tests "*OidcDiscoveryAndTokenContractIntegrationTest"

Expected: PASS; JWKS public key로 두 token의 signature를 검증할 수 있습니다.

- [ ] **Step 6: 커밋**

    git add backend/src/main/resources/db/migration/V9__oauth_protocol_key_event.sql backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthSigningKey.java backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthSigningKeyRepository.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSigningKeyJpaEntity.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthSigningKeyRepositoryAdapter.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthJwkSourceConfiguration.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthTokenCustomizer.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OidcDiscoveryAndTokenContractIntegrationTest.java
    git commit -m "feat: issue rs256 oidc tokens"

---

### Task 9: IdP 로그인·비밀번호 변경·동의 서버 화면

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/IdpLoginController.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/IdpConsentController.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/IdpSessionAuthentication.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthConsentService.java
- Create: backend/src/main/resources/templates/idp/login.html
- Create: backend/src/main/resources/templates/idp/password.html
- Create: backend/src/main/resources/templates/idp/consent.html
- Create: backend/src/main/resources/templates/idp/error.html
- Create: backend/src/main/resources/static/idp/idp.css
- Create: backend/src/test/java/com/sweet/authstudy/oauth/presentation/IdpBrowserFlowIntegrationTest.java

**Interfaces:**
- Consumes: CredentialAuthenticationService, pending OAuth2AuthorizationRequest, OAuthConsentService
- Produces: 서버 세션 principal, request 복원, consent approve/deny, 사람이 읽을 수 있는 scope 설명

- [ ] **Step 1: request 보존과 동의 결정 실패 테스트 작성**

로그인 전 authorize request의 client_id, exact redirect URI, state, nonce, scopes, code_challenge를 서버 세션에 보존하고 로그인 성공 뒤 원래 request로 복귀해야 합니다. 비밀번호 변경 필요 계정은 /idp/password 외 다른 authorize 진행이 금지됩니다. consent 화면에는 display name과 다음 설명을 노출합니다: 기본 식별, 프로필, 이메일, 회사, 조직/직위, HR 역할.

- [ ] **Step 2: 화면 부재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*IdpBrowserFlowIntegrationTest"

Expected: FAIL with 404 or missing template.

- [ ] **Step 3: login session과 absolute timeout 구현**

IdpSessionAuthentication은 accountId/companyId/userId/roles/sub/authenticatedAt만 저장합니다. 세션 cookie는 host-only IDP_AUTH_SESSION, HttpOnly, SameSite=Lax이며 dev Secure=false입니다. 각 요청에서 lastAccess와 authenticatedAt을 검사해 idle 30분 또는 absolute 8시간이면 invalidate합니다.

- [ ] **Step 4: consent 재사용·증분·거부 구현**

CONSENT_REQUIRED client에서 기존 approved scopes가 요청 scope의 superset이면 화면을 생략합니다. 새 scope가 있으면 전체와 신규 항목을 구분해 표시합니다. approve는 정확한 request/client/sub에 묶어 저장하고 deny는 기존 consent를 변경하지 않은 채 access_denied로 exact redirect 합니다. TRUSTED_FIRST_PARTY만 화면을 생략합니다.

- [ ] **Step 5: CSRF/origin과 browser flow 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*IdpBrowserFlowIntegrationTest"

Expected: PASS including CSRF rejection, invalid origin rejection, request fixation protection and session id rotation after login.

- [ ] **Step 6: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/presentation backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthConsentService.java backend/src/main/resources/templates/idp backend/src/main/resources/static/idp/idp.css backend/src/test/java/com/sweet/authstudy/oauth/presentation/IdpBrowserFlowIntegrationTest.java
    git commit -m "feat: add idp login and consent pages"

---

### Task 10: scope별 UserInfo와 현재 HR 상태 재검증

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthUserInfoClaimSource.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthUserInfoService.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthUserInfoView.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/HrOAuthUserInfoClaimSource.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OidcUserInfoMapper.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/application/OAuthUserInfoServiceTest.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OidcUserInfoIntegrationTest.java

**Interfaces:**
- Consumes: principal accountId, granted scopes, Account/Company/User/Department/Position/roles snapshot
- Produces: sub와 scope별 standard/namespaced UserInfo claim

- [ ] **Step 1: exact claim matrix 실패 테스트 작성**

openid만 있으면 sub만, profile은 name/preferred_username, email은 email/email_verified=false를 추가합니다. HR scope는 다음 key만 추가합니다.

    https://auth-study.local/claims/company
    https://auth-study.local/claims/organization
    https://auth-study.local/claims/roles

ID Token sub와 UserInfo sub 불일치가 절대 발생하지 않는지 확인하고 미승인 scope claim은 null인지 검증합니다.

- [ ] **Step 2: 서비스 부재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthUserInfoServiceTest"

Expected: FAIL at compilation.

- [ ] **Step 3: claim source와 state gate 구현**

HrOAuthUserInfoClaimSource는 account ACTIVE, company ACTIVE, user ACTIVE, client ACTIVE를 매 호출 재조회합니다. 하나라도 불허이면 invalid_token으로 거부합니다. SYSTEM_ADMIN account는 회사 client authorization 주체가 될 수 없으므로 UserInfo에도 도달하지 않습니다.

- [ ] **Step 4: OIDC endpoint mapper 연결**

OidcUserInfoMapper는 Map.of가 null을 허용하지 않는 문제를 피하도록 mutable map에 허용된 non-null claim만 넣습니다. organization은 primary/secondary memberships와 position을 stable JSON shape으로 직렬화하고 내부 numeric id는 노출하지 않습니다.

- [ ] **Step 5: 단위·통합 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthUserInfoServiceTest" --tests "*OidcUserInfoIntegrationTest"

Expected: PASS; ID/Access Token에는 동일 HR claims가 없습니다.

- [ ] **Step 6: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthUserInfoClaimSource.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthUserInfoService.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthUserInfoView.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/HrOAuthUserInfoClaimSource.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OidcUserInfoMapper.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/application/OAuthUserInfoServiceTest.java backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OidcUserInfoIntegrationTest.java
    git commit -m "feat: serve scoped oidc userinfo claims"

---

### Task 11: refresh rotation, revocation과 HR 상태 변경 연결

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/identity/application/OAuthGrantRevocationPort.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthGrantRevocationService.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthRefreshTokenAuthenticationProvider.java
- Modify: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientService.java
- Modify: backend/src/main/java/com/sweet/authstudy/identity/application/AccountService.java
- Modify: backend/src/main/java/com/sweet/authstudy/identity/application/AuthenticationService.java
- Modify: backend/src/main/java/com/sweet/authstudy/identity/application/CredentialAuthenticationService.java
- Modify: backend/src/main/java/com/sweet/authstudy/hr/user/application/UserService.java
- Modify: backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyService.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OAuthRefreshAndRevocationIntegrationTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/identity/application/AccountServiceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/identity/application/AuthenticationServiceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/identity/application/CredentialAuthenticationServiceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/oauth/application/OAuthClientServiceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/hr/user/application/UserServiceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/hr/company/application/CompanyServiceTest.java

**Interfaces:**
- Consumes: refresh token hash, account/company/client 상태 변경
- Produces: single-use rotation, reuse 시 family 폐기, account/company/client authorization과 refresh 폐기

- [ ] **Step 1: refresh replay와 상태 변경 실패 테스트 작성**

같은 refresh token을 순차/동시에 두 번 쓰면 첫 교환만 성공하고 재사용 감지 뒤 successor까지 family 전체가 invalid_grant여야 합니다. 비밀번호 reset/change, login lock, account/user disable, COMPANY_ADMIN role 변경, company deactivate, client disable은 해당 OAuth authorization과 refresh를 폐기해야 합니다.

- [ ] **Step 2: 현재 Spring 기본 동작이 family reuse를 충족하지 않아 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest"

Expected: FAIL because project-owned family reuse and HR mutation hooks are absent.

- [ ] **Step 3: refresh provider와 application revocation 구현**

OAuthRefreshTokenAuthenticationProvider는 hash lookup과 row lock을 한 transaction에서 수행합니다. used_at이 있으면 revokeFamily 후 invalid_grant, 유효하면 used_at 기록과 successor insert를 atomic하게 수행합니다. OAuthGrantRevocationPort는 revokeAccount, revokeCompany, revokeClient 메서드를 제공하고 identity/hr application은 이 port만 의존합니다.

- [ ] **Step 4: mutation service 연결**

AccountService의 resetTemporaryPassword, assign/revokeCompanyAdmin, revokeAllRefreshTokens 계열, AuthenticationService.changePassword, CredentialAuthenticationService의 login lock, UserService의 status 변경, CompanyService의 deactivate, OAuthClientService.disable이 기존 HR refresh와 OAuth grant를 같은 application transaction 안에서 폐기하도록 연결합니다. 순환 의존이 생기면 event가 아니라 shared application port를 사용합니다.

- [ ] **Step 5: 회귀·통합 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthRefreshAndRevocationIntegrationTest" --tests "*AccountServiceTest" --tests "*AuthenticationServiceTest" --tests "*CredentialAuthenticationServiceTest" --tests "*OAuthClientServiceTest" --tests "*UserServiceTest" --tests "*CompanyServiceTest"

Expected: PASS. 이미 발급된 self-contained Access Token의 최대 5분 암호학적 유효성은 문서화하고 UserInfo는 즉시 거부합니다.

- [ ] **Step 6: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/identity/application/OAuthGrantRevocationPort.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthGrantRevocationService.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthRefreshTokenAuthenticationProvider.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthClientService.java backend/src/main/java/com/sweet/authstudy/identity/application/AccountService.java backend/src/main/java/com/sweet/authstudy/identity/application/AuthenticationService.java backend/src/main/java/com/sweet/authstudy/identity/application/CredentialAuthenticationService.java backend/src/main/java/com/sweet/authstudy/hr/user/application/UserService.java backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyService.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OAuthRefreshAndRevocationIntegrationTest.java backend/src/test/java/com/sweet/authstudy/identity/application/AccountServiceTest.java backend/src/test/java/com/sweet/authstudy/identity/application/AuthenticationServiceTest.java backend/src/test/java/com/sweet/authstudy/identity/application/CredentialAuthenticationServiceTest.java backend/src/test/java/com/sweet/authstudy/oauth/application/OAuthClientServiceTest.java backend/src/test/java/com/sweet/authstudy/hr/user/application/UserServiceTest.java backend/src/test/java/com/sweet/authstudy/hr/company/application/CompanyServiceTest.java
    git commit -m "feat: rotate and revoke oauth refresh grants"

---

### Task 12: protocol events, 오류 경계, logout와 코어 acceptance

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthProtocolEvent.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthProtocolEventRepository.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthProtocolEventService.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthProtocolEventJpaEntity.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthProtocolEventRepositoryAdapter.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolErrorHandler.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OidcLogoutSuccessHandler.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolCorsConfigurationSource.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OAuthProtocolSecurityAcceptanceTest.java
- Modify: backend/src/test/java/com/sweet/authstudy/architecture/ArchitectureTest.java

**Interfaces:**
- Consumes: authorize/login/consent/code/token/refresh/revoke/userinfo/logout 결과
- Produces: sanitised event metadata, 표준 OAuth/OIDC 오류, app logout와 RP-initiated logout

- [ ] **Step 1: event sanitization과 redirect error 실패 테스트 작성**

event type은 AUTHORIZATION_REQUESTED, LOGIN_SUCCEEDED, LOGIN_FAILED, CONSENT_APPROVED, CONSENT_DENIED, CODE_ISSUED, TOKEN_ISSUED, TOKEN_REFRESHED, TOKEN_REUSE_DETECTED, TOKEN_REVOKED, USERINFO_SERVED, USERINFO_REJECTED, LOGOUT_COMPLETED를 허용합니다. metadata JSON에 code, token, secret, verifier, password key가 들어가면 저장을 거부합니다. 검증된 redirect URI 전 오류만 client redirect를 사용하고 그 전 오류는 /idp/error에서 표시합니다.

- [ ] **Step 2: event adapter와 handler 부재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthProtocolSecurityAcceptanceTest"

Expected: FAIL.

- [ ] **Step 3: protocol event와 오류 handler 구현**

event에는 occurredAt, correlationId, eventType, outcome, clientId(non-secret opaque id), subject, accountId, companyId, errorCode와 allowlisted metadata만 둡니다. token/userinfo/revocation endpoint는 invalid_client, invalid_grant, invalid_scope, invalid_token을 RFC 형태로 반환하며 서버 내부 message/stack을 노출하지 않습니다.

- [ ] **Step 4: 두 logout 의미 구현**

앱 logout용 revocation은 해당 RP refresh family만 폐기하고 IdP session은 유지합니다. /connect/logout은 검증된 id_token_hint의 client와 등록된 exact post_logout_redirect_uri를 확인한 뒤 IdP session을 invalidate합니다. 미등록 URI에는 redirect하지 않습니다.

- [ ] **Step 5: public client origin만 허용하는 protocol CORS 구현**

OAuthProtocolCorsConfigurationSource는 active public client의 redirect URI에서 scheme/host/port origin을 계산합니다. 요청 Origin이 이 exact set에 있을 때만 /.well-known/**, /oauth2/jwks와 /oauth2/token의 preflight/actual response에 그 단일 Origin을 반환합니다. wildcard, credentials 허용, confidential-only client origin은 사용하지 않습니다. Authorization Code·redirect URI·PKCE 검증은 CORS와 별도로 그대로 적용합니다.

- [ ] **Step 6: 전체 backend 검증**

Run: cd backend; .\gradlew.bat test

Expected: PASS. acceptance test는 discovery, exact redirect, PKCE, consent, code one-time, RS256, UserInfo scope, refresh reuse, state revocation, logout과 민감정보 비기록을 모두 포함합니다.

- [ ] **Step 7: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthProtocolEvent.java backend/src/main/java/com/sweet/authstudy/oauth/domain/OAuthProtocolEventRepository.java backend/src/main/java/com/sweet/authstudy/oauth/application/OAuthProtocolEventService.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthProtocolEventJpaEntity.java backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthProtocolEventRepositoryAdapter.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolErrorHandler.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OidcLogoutSuccessHandler.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolCorsConfigurationSource.java backend/src/main/java/com/sweet/authstudy/authorization/AuthorizationServerSecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/acceptance/OAuthProtocolSecurityAcceptanceTest.java backend/src/test/java/com/sweet/authstudy/architecture/ArchitectureTest.java
    git commit -m "feat: harden oauth protocol lifecycle"

---

## Core Completion Gate

- [ ] cd backend; .\gradlew.bat clean test가 통과합니다.
- [ ] Discovery issuer와 모든 endpoint URL이 idp.localhost:8080 기준입니다.
- [ ] HR HS256 token과 OAuth RS256 token의 decoder, key, claims, TTL, persistence가 교차 참조되지 않습니다.
- [ ] DB와 로그에서 raw secret/code/access/refresh/verifier/password를 검색해 결과가 없습니다.
- [ ] public/confidential client 모두 authorization_code + PKCE S256을 요구합니다.
- [ ] UserInfo state recheck와 account/company/client 폐기 경로가 acceptance test로 고정됩니다.
- [ ] 이 계획 완료 후 OAuth 관리자 계획을 실행하고, 그 다음 독립 레퍼런스 앱 계획을 실행합니다.
