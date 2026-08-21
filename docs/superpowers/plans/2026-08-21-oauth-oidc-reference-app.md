# Independent OIDC Reference Application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** IdP와 코드·세션·DB를 공유하지 않는 별도 Spring Boot BFF, React SPA, PostgreSQL 애플리케이션을 구축해 Authorization Code + PKCE, Discovery, JWKS, ID Token, UserInfo, refresh, logout과 로컬 사용자 생명주기를 실제 브라우저에서 검증합니다.

**Architecture:** reference-app/backend는 Spring Security OAuth2 Client인 confidential BFF입니다. 브라우저에는 HttpOnly RP_SESSION만 주고 OAuth token은 서버 메모리 session에 보관합니다. callback에서 검증된 ID Token과 UserInfo의 (iss, sub)를 비교한 뒤 전용 PostgreSQL의 app_user를 JIT 생성·갱신합니다. 최초 COMPANY_ADMIN 한 명만 transaction/row lock으로 APP_ADMIN이 되며 이후 APP_USER/APP_ADMIN과 ACTIVE/DISABLED는 이 앱이 독립적으로 관리합니다. reference-app/frontend는 Vite dev proxy로 /bff/**만 BFF에 전달합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Security OAuth2 Client, Spring Data JPA, Flyway, PostgreSQL 17, Testcontainers, React 19, Vite, TypeScript, Vitest, Testing Library, Playwright

**Spec:** docs/superpowers/specs/2026-08-21-oauth-oidc-idp-design.md

**Prerequisites:**
- docs/superpowers/plans/2026-08-21-oauth-oidc-idp-core.md
- docs/superpowers/plans/2026-08-21-oauth-oidc-admin.md

## Global Constraints

- reference app는 IdP datasource, entity, Java package, session cookie, signing key와 repository를 import하거나 공유하지 않습니다.
- issuer는 http://idp.localhost:8080, SPA는 http://rp.localhost:3100, BFF는 http://rp.localhost:8180입니다.
- callback exact URI는 http://rp.localhost:8180/login/oauth2/code/reference-app입니다.
- RP_SESSION은 host-only, HttpOnly, SameSite=Lax, dev Secure=false, idle 30분입니다.
- OAuth access/id/refresh token은 BFF server-side memory session에만 있고 browser response, localStorage, sessionStorage, JavaScript state와 app DB에 저장하지 않습니다.
- BFF restart는 모든 RP session을 로그아웃시켜도 됩니다. distributed session은 범위 밖입니다.
- app_user의 identity key는 (issuer, subject) unique이며 IdP accountId/userId를 외래키로 저장하지 않습니다.
- HR role은 JIT snapshot일 뿐 APP_ADMIN을 지속적으로 동기화하지 않습니다. bootstrap 완료 뒤 앱 역할은 독립적입니다.
- 마지막 ACTIVE APP_ADMIN을 disable하거나 APP_ADMIN에서 내리는 동작은 금지합니다.
- frontend/.idea는 스테이징하지 않습니다.
- 각 작업은 실패 테스트 → 실패 확인 → 최소 구현 → 통과 확인 → 명시적 파일만 커밋 순서입니다.

---

## File and Responsibility Map

    reference-app/backend/
      build.gradle.kts settings.gradle.kts
      src/main/java/com/sweet/referenceapp/
        ReferenceApplication.java
        security/        OAuth2 client, RP session, current app principal
        user/domain/     AppUser, AppRole, AppUserStatus, ports
        user/application/JIT, bootstrap, profile, admin lifecycle
        user/infrastructure/JPA adapters
        user/presentation /bff APIs
      src/main/resources/
        application.yaml application-dev.yaml
        db/migration/V1__app_user.sql
    reference-app/frontend/
      src/features/session/
      src/features/profile/
      src/features/admin/
      vite.config.ts
    infrastructure/docker-compose.yml
      postgres + reference-postgres
    e2e/
      oauth-oidc-reference-flow.spec.ts

---

### Task 1: 독립 backend Gradle 프로젝트, 전용 PostgreSQL과 smoke test

**Files:**
- Create: reference-app/backend/settings.gradle.kts
- Create: reference-app/backend/build.gradle.kts
- Create: reference-app/backend/gradlew
- Create: reference-app/backend/gradlew.bat
- Create: reference-app/backend/gradle/wrapper/gradle-wrapper.jar
- Create: reference-app/backend/gradle/wrapper/gradle-wrapper.properties
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/ReferenceApplication.java
- Create: reference-app/backend/src/main/resources/application.yaml
- Create: reference-app/backend/src/main/resources/application-dev.yaml
- Create: reference-app/backend/src/test/resources/application-test.yaml
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/support/PostgresContainerConfiguration.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/ReferenceApplicationTest.java
- Modify: infrastructure/docker-compose.yml

**Interfaces:**
- Consumes: no source dependency on backend project
- Produces: port 8180 BFF, dedicated reference_app PostgreSQL on host 55433, Testcontainers test context

- [ ] **Step 1: independent context 실패 테스트 작성**

    @SpringBootTest
    @Import(PostgresContainerConfiguration.class)
    @ActiveProfiles("test")
    class ReferenceApplicationTest {
        @Test void contextLoads() {}
    }

- [ ] **Step 2: 프로젝트 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test

Expected: FAIL because the independent Gradle project does not exist. Gradle wrapper는 root backend의 Gradle 8.x와 같은 버전으로 생성합니다.

- [ ] **Step 3: 프로젝트와 의존성 구현**

build.gradle.kts의 Spring Boot와 dependency-management plugin version은 기존 backend와 같은 3.5.15/1.1.7을 사용하고 다음 dependency를 둡니다. Java package는 com.sweet.referenceapp이며 com.sweet.authstudy를 dependency나 import하지 않습니다.

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

application-dev.yaml의 datasource는 jdbc:postgresql://localhost:55433/reference_app, username/password reference_app입니다. server.port=8180, forward-headers-strategy=framework를 설정합니다.

- [ ] **Step 4: Docker Compose에 전용 DB 추가**

    reference-postgres:
      image: postgres:17-alpine
      environment:
        POSTGRES_DB: reference_app
        POSTGRES_USER: reference_app
        POSTGRES_PASSWORD: reference_app
      ports:
        - "55433:5432"
      healthcheck:
        test: ["CMD-SHELL", "pg_isready -U reference_app -d reference_app"]
        interval: 5s
        timeout: 3s
        retries: 10
      volumes:
        - reference-app-postgres:/var/lib/postgresql/data

- [ ] **Step 5: context와 compose config 검증**

Run: cd reference-app/backend; .\gradlew.bat test

Expected: PASS against its own Testcontainer.

Run: docker compose -f infrastructure/docker-compose.yml config

Expected: PASS with host ports 5432 and 55433.

- [ ] **Step 6: 커밋**

    git add reference-app/backend infrastructure/docker-compose.yml
    git commit -m "feat: scaffold independent oidc reference backend"

---

### Task 2: app_user 스키마, JIT identity와 claim snapshot

**Files:**
- Create: reference-app/backend/src/main/resources/db/migration/V1__app_user.sql
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUser.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUserStatus.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppRole.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUserRepository.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/ExternalIdentityProfile.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserView.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserProvisioningService.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppUserJpaEntity.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppUserJpaRepository.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppUserRepositoryAdapter.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserProvisioningIntegrationTest.java

**Interfaces:**
- Consumes: validated issuer, ID Token sub, UserInfo profile/email/HR claims
- Produces: unique (issuer, subject) local AppUser and latest external snapshot

- [ ] **Step 1: JIT와 identity collision 실패 테스트 작성**

같은 iss/sub 재로그인은 같은 row를 갱신하고, 같은 sub라도 issuer가 다르면 다른 row이며, 같은 issuer라도 sub가 다르면 다른 row여야 합니다. UserInfo sub가 ID Token sub와 다르면 provisioning이 호출되지 않아야 합니다.

- [ ] **Step 2: migration/model 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*AppUserProvisioningIntegrationTest"

Expected: FAIL because app_user does not exist.

- [ ] **Step 3: V1 schema 구현**

app_user는 id UUID, issuer varchar, subject varchar, email, display_name, company_snapshot jsonb, organization_snapshot jsonb, hr_roles_snapshot jsonb, status, created_at, updated_at, last_login_at, version을 갖고 (issuer, subject) unique입니다. app_user_role은 app_user_id/role 복합 PK와 FK cascade를 갖습니다. app_bootstrap_state는 singleton key, bootstrapped_user_id, bootstrapped_at, version을 갖습니다.

- [ ] **Step 4: domain과 JIT service 구현**

    public record ExternalIdentityProfile(
            URI issuer,
            String subject,
            String email,
            String displayName,
            Map<String, Object> company,
            Map<String, Object> organization,
            Set<String> hrRoles) {}

새 사용자는 ACTIVE와 APP_USER로 생성합니다. 기존 사용자는 local status/roles를 보존하고 외부 snapshot과 lastLoginAt만 갱신합니다. unique race는 재조회 후 update로 수렴합니다.

- [ ] **Step 5: 통합·architecture 테스트 통과**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*AppUserProvisioningIntegrationTest"

Expected: PASS; DB에는 IdP numeric account/user/company id와 OAuth token이 없습니다.

- [ ] **Step 6: 커밋**

    git add reference-app/backend/src/main/resources/db/migration/V1__app_user.sql reference-app/backend/src/main/java/com/sweet/referenceapp/user reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserProvisioningIntegrationTest.java
    git commit -m "feat: provision local users from oidc identities"

---

### Task 3: transaction-safe 최초 APP_ADMIN bootstrap

**Files:**
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppBootstrapState.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppBootstrapStateRepository.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppAdminBootstrapService.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppBootstrapStateJpaEntity.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppBootstrapStateJpaRepository.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppBootstrapStateRepositoryAdapter.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppAdminBootstrapConcurrencyIntegrationTest.java

**Interfaces:**
- Consumes: provisioned AppUser와 현재 UserInfo hrRoles snapshot
- Produces: 전체 앱에서 정확히 한 명의 최초 APP_ADMIN, immutable bootstrap completion

- [ ] **Step 1: 동시 bootstrap 실패 테스트 작성**

두 COMPANY_ADMIN identity가 barrier 뒤 동시에 첫 로그인하도록 executor를 사용합니다. 완료 뒤 APP_ADMIN은 정확히 한 명이고 app_bootstrap_state.bootstrapped_user_id가 그 user를 가리켜야 합니다. 첫 로그인에 COMPANY_ADMIN이 없으면 state가 미완료이고 뒤의 COMPANY_ADMIN이 bootstrap되어야 합니다.

- [ ] **Step 2: service 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*AppAdminBootstrapConcurrencyIntegrationTest"

Expected: FAIL at compilation.

- [ ] **Step 3: singleton row lock 구현**

AppAdminBootstrapService는 REQUIRES_NEW가 아니라 JIT와 같은 transaction에서 singleton row를 SELECT FOR UPDATE합니다. state가 비어 있고 current hrRoles에 COMPANY_ADMIN이 있을 때만 current AppUser에 APP_ADMIN을 추가하고 bootstrappedUserId/At을 기록합니다. lock 순서는 bootstrap_state → app_user로 고정합니다.

- [ ] **Step 4: 역할 비동기화 테스트 추가**

bootstrap 후 UserInfo에서 COMPANY_ADMIN이 사라져도 APP_ADMIN은 유지되고, 다른 COMPANY_ADMIN이 로그인해도 자동 APP_ADMIN이 되지 않는지 검증합니다.

- [ ] **Step 5: concurrency 테스트 통과**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*AppAdminBootstrapConcurrencyIntegrationTest"

Expected: PASS repeatedly.

- [ ] **Step 6: 커밋**

    git add reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppBootstrapState.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppBootstrapStateRepository.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppAdminBootstrapService.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppBootstrapStateJpaEntity.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppBootstrapStateJpaRepository.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppBootstrapStateRepositoryAdapter.java reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppAdminBootstrapConcurrencyIntegrationTest.java
    git commit -m "feat: bootstrap first local app administrator"

---

### Task 4: Spring OAuth2 Client, PKCE와 RP session security

**Files:**
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/ReferenceSecurityProperties.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/PkceAuthorizationRequestResolver.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/RpSessionProperties.java
- Modify: reference-app/backend/src/main/resources/application-dev.yaml
- Modify: reference-app/backend/src/test/resources/application-test.yaml
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/security/OAuth2ClientConfigurationIntegrationTest.java

**Interfaces:**
- Consumes: issuer discovery, confidential client_id/secret, redirect URI
- Produces: /oauth2/authorization/reference-app, callback, PKCE S256, HttpOnly RP_SESSION

- [ ] **Step 1: generated authorization request 실패 테스트 작성**

302 Location을 parse해 response_type=code, scope, state, nonce, code_challenge, code_challenge_method=S256, redirect_uri exact를 검증합니다. location의 authorize endpoint는 discovery에서 얻고 hard-code하지 않습니다.

- [ ] **Step 2: configuration 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*OAuth2ClientConfigurationIntegrationTest"

Expected: FAIL with 404 or missing ClientRegistration.

- [ ] **Step 3: issuer 기반 client registration 구현**

application-dev.yaml:

    spring:
      security:
        oauth2:
          client:
            provider:
              auth-study:
                issuer-uri: http://idp.localhost:8080
            registration:
              reference-app:
                provider: auth-study
                client-id: rp_local_7WmB7sW9dNxQ5mF3kC2vA8yJ
                client-secret: ReferenceAppLocalSecret-DoNotReuse
                client-authentication-method: client_secret_basic
                authorization-grant-type: authorization_code
                redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
                scope: openid,profile,email,hr.company,hr.organization,hr.roles

PkceAuthorizationRequestResolver는 DefaultOAuth2AuthorizationRequestResolver에 OAuth2AuthorizationRequestCustomizers.withPkce()를 적용해 confidential client에도 S256을 강제합니다.

- [ ] **Step 4: BFF security와 cookie 구현**

/bff/session과 /bff/login은 permitAll, callback/oauth2 authorization은 Spring handler, /bff/** 나머지는 authenticated입니다. CSRF는 cookie token을 JavaScript에 노출하는 대신 same-origin Origin 검사와 /bff mutation용 서버 렌더 meta token을 사용하지 않도록 double-submit이 아닌 BFF용 X-CSRF 헤더 endpoint를 제공합니다. RP_SESSION은 HttpOnly, SameSite=Lax, Secure=false, host-only, idle 30분이며 URL rewriting을 끕니다.

- [ ] **Step 5: security 테스트 통과**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*OAuth2ClientConfigurationIntegrationTest"

Expected: PASS including session fixation rotation, invalid state rejection, missing nonce rejection and callback exact host.

- [ ] **Step 6: 커밋**

    git add reference-app/backend/src/main/java/com/sweet/referenceapp/security reference-app/backend/src/main/resources/application-dev.yaml reference-app/backend/src/test/resources/application-test.yaml reference-app/backend/src/test/java/com/sweet/referenceapp/security/OAuth2ClientConfigurationIntegrationTest.java
    git commit -m "feat: configure oidc bff session login"

---

### Task 5: callback UserInfo 검증, local principal과 session API

**Files:**
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OidcLoginProvisioningSuccessHandler.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OidcUserInfoClient.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/CurrentAppUser.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/AppUserAuthentication.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/RpOAuthSession.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/SessionController.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/ProfileController.java
- Modify: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/security/OidcLoginProvisioningIntegrationTest.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/user/SessionApiIntegrationTest.java

**Interfaces:**
- Consumes: verified OidcIdToken, Access Token, UserInfo endpoint response
- Produces: local AppUser authentication, GET /bff/session, GET /bff/profile

- [ ] **Step 1: sub mismatch와 token leakage 실패 테스트 작성**

mock issuer의 signed ID Token sub=subject-a와 UserInfo sub=subject-b이면 callback은 /login-error?code=subject_mismatch로 이동하고 app_user를 만들지 않아야 합니다. /bff/session과 /bff/profile JSON을 직렬화했을 때 access_token, refresh_token, id_token, client_secret key가 없어야 합니다.

- [ ] **Step 2: handler 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*OidcLoginProvisioningIntegrationTest" --tests "*SessionApiIntegrationTest"

Expected: FAIL.

- [ ] **Step 3: UserInfo client와 exact subject 검증 구현**

UserInfo endpoint는 provider metadata에서 얻고 Bearer Access Token으로 호출합니다. ID Token signature/issuer/audience/nonce는 Spring OIDC login이 먼저 검증합니다. success handler는 ID Token iss/sub와 UserInfo sub를 constant-time이 아니라 exact string equality로 비교하고 일치한 뒤만 ExternalIdentityProfile을 구성합니다.

- [ ] **Step 4: provisioning/bootstrap과 local principal 교체**

같은 transaction에서 JIT와 bootstrap을 실행합니다. DISABLED local user이면 session을 만들지 않고 /login-error?code=local_user_disabled로 이동합니다. 성공하면 SecurityContext principal을 AppUserAuthentication으로 교체하고, registrationId·issuer·ID Token·Access Token·Refresh Token·각 만료 시각을 담은 package-private RpOAuthSession을 HttpSession에 저장합니다. controller response에는 이 객체를 직렬화하지 않습니다.

- [ ] **Step 5: session/profile API 구현 및 통과**

GET /bff/session은 authenticated, user{id,displayName,email,status,roles}, csrfHeaderName/csrfToken만 반환합니다. unauthenticated면 200과 authenticated=false를 반환합니다. GET /bff/profile은 external snapshot과 local roles를 반환합니다.

Run: cd reference-app/backend; .\gradlew.bat test --tests "*OidcLoginProvisioningIntegrationTest" --tests "*SessionApiIntegrationTest"

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add reference-app/backend/src/main/java/com/sweet/referenceapp/security/OidcLoginProvisioningSuccessHandler.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/OidcUserInfoClient.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/CurrentAppUser.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/AppUserAuthentication.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/RpOAuthSession.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/SessionController.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/ProfileController.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java reference-app/backend/src/test/java/com/sweet/referenceapp/security/OidcLoginProvisioningIntegrationTest.java reference-app/backend/src/test/java/com/sweet/referenceapp/user/SessionApiIntegrationTest.java
    git commit -m "feat: provision bff sessions from oidc userinfo"

---

### Task 6: token refresh single-flight, 앱 logout과 전체 OIDC logout

**Files:**
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuthSessionTokenService.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuthSessionRefreshCoordinator.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/security/ReferenceLogoutController.java
- Modify: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/security/OAuthSessionRefreshConcurrencyTest.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/security/ReferenceLogoutIntegrationTest.java

**Interfaces:**
- Consumes: server-side OAuth2AuthorizedClient와 IdP token/revocation/end-session metadata
- Produces: session별 single-flight refresh, local app logout, full OIDC logout

- [ ] **Step 1: concurrent expiry와 두 logout 실패 테스트 작성**

같은 RP_SESSION으로 동시에 두 profile request가 Access Token 만료 경계에 도달하면 token endpoint 호출은 한 번이고 두 요청은 같은 successor를 사용해야 합니다. POST /bff/logout은 IdP session을 남기며 POST /bff/logout/identity-provider는 end_session_endpoint로 이동해야 합니다.

- [ ] **Step 2: coordinator 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*OAuthSessionRefreshConcurrencyTest" --tests "*ReferenceLogoutIntegrationTest"

Expected: FAIL.

- [ ] **Step 3: session-scoped refresh lock 구현**

OAuthSessionRefreshCoordinator는 session id별 CompletableFuture map을 사용하고 refresh 완료/실패 뒤 finally에서 entry를 제거합니다. Access Token 만료 전 30초 이내면 refresh하고 성공 뒤 UserInfo를 다시 불러 local snapshot과 현재 IdP 상태를 검증합니다. invalid_grant/invalid_token이면 RP session을 invalidate합니다.

- [ ] **Step 4: 앱 logout 구현**

POST /bff/logout은 refresh token이 있으면 revocation endpoint를 호출한 뒤 결과와 무관하게 local OAuth2AuthorizedClient와 RP_SESSION을 제거하고 204를 반환합니다. IdP session cookie에는 접근하지 않습니다.

- [ ] **Step 5: full logout 구현과 테스트 통과**

POST /bff/logout/identity-provider는 id_token_hint와 등록된 post_logout_redirect_uri=http://rp.localhost:3100/logged-out를 server-side에서 구성해 IdP end_session_endpoint로 303 이동하고 local session도 제거합니다. redirect target을 request body에서 받지 않습니다.

Run: cd reference-app/backend; .\gradlew.bat test --tests "*OAuthSessionRefreshConcurrencyTest" --tests "*ReferenceLogoutIntegrationTest"

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuthSessionTokenService.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuthSessionRefreshCoordinator.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/ReferenceLogoutController.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java reference-app/backend/src/test/java/com/sweet/referenceapp/security/OAuthSessionRefreshConcurrencyTest.java reference-app/backend/src/test/java/com/sweet/referenceapp/security/ReferenceLogoutIntegrationTest.java
    git commit -m "feat: refresh and end oidc bff sessions"

---

### Task 7: local 사용자 관리 API와 마지막 관리자 보호

**Files:**
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminService.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminCommands.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminController.java
- Create: reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminRequests.java
- Modify: reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminIntegrationTest.java
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminConcurrencyIntegrationTest.java

**Interfaces:**
- Consumes: authenticated local APP_ADMIN, target user UUID, version
- Produces: paginated user list/detail, ACTIVE/DISABLED, APP_USER/APP_ADMIN mutation

**Endpoint contract:**

    GET  /bff/admin/users?page=0&size=20&status=ACTIVE&role=APP_ADMIN
    GET  /bff/admin/users/{userId}
    PUT  /bff/admin/users/{userId}/status
    PUT  /bff/admin/users/{userId}/roles

- [ ] **Step 1: local authorization과 last-admin 실패 테스트 작성**

APP_USER는 모든 /bff/admin/**에 403이어야 합니다. 자기 자신을 포함해 마지막 ACTIVE APP_ADMIN을 disable하거나 roles에서 APP_ADMIN을 제거하면 409 LAST_ACTIVE_ADMIN_REQUIRED입니다. 두 admin을 동시에 disable/demote해 active admin 0명이 되는 race도 막아야 합니다.

- [ ] **Step 2: service/controller 부재 실패 확인**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*AppUserAdminIntegrationTest" --tests "*AppUserAdminConcurrencyIntegrationTest"

Expected: FAIL.

- [ ] **Step 3: row-lock mutation 구현**

role/status mutation은 active APP_ADMIN rows를 stable id order로 SELECT FOR UPDATE하고 결과 상태를 계산한 뒤 최소 한 명을 보장합니다. target version을 비교해 stale request는 409 OPTIMISTIC_LOCK_CONFLICT로 반환합니다. DISABLED 사용자의 기존 RP session은 다음 request에서 DB status recheck로 invalidate합니다.

- [ ] **Step 4: controller와 security 구현**

AppUserAuthentication의 local APP_ADMIN authority만 사용하고 HR roles snapshot으로 API 접근을 허용하지 않습니다. response에는 issuer와 opaque subject를 표시할 수 있지만 OAuth token과 IdP internal id는 없습니다.

- [ ] **Step 5: 통합·동시성 테스트 통과**

Run: cd reference-app/backend; .\gradlew.bat test --tests "*AppUserAdminIntegrationTest" --tests "*AppUserAdminConcurrencyIntegrationTest"

Expected: PASS with exactly one concurrent destructive mutation rejected.

- [ ] **Step 6: 커밋**

    git add reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminService.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminCommands.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminController.java reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminRequests.java reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminIntegrationTest.java reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminConcurrencyIntegrationTest.java
    git commit -m "feat: manage independent app users"

---

### Task 8: Vite SPA scaffold, BFF session과 개인 화면

**Files:**
- Create: reference-app/frontend/package.json
- Create: reference-app/frontend/tsconfig.json
- Create: reference-app/frontend/vite.config.ts
- Create: reference-app/frontend/index.html
- Create: reference-app/frontend/src/main.tsx
- Create: reference-app/frontend/src/app.tsx
- Create: reference-app/frontend/src/styles.css
- Create: reference-app/frontend/src/features/session/bff-client.ts
- Create: reference-app/frontend/src/features/session/session-provider.tsx
- Create: reference-app/frontend/src/features/session/session-provider.test.tsx
- Create: reference-app/frontend/src/features/profile/profile-page.tsx
- Create: reference-app/frontend/src/features/profile/profile-page.test.tsx

**Interfaces:**
- Consumes: /bff/session, /bff/profile, /oauth2/authorization/reference-app
- Produces: login, session restore, profile/HR snapshot, 앱 logout/전체 logout

- [ ] **Step 1: browser token 금지와 session 상태 실패 테스트 작성**

unauthenticated session은 로그인 button, authenticated session은 profile을 표시합니다. fetch는 credentials=include와 session response의 X-CSRF header를 mutation에 사용합니다. localStorage/sessionStorage/document.cookie에 token을 쓰지 않고 API response type에 OAuth token field가 없는지 검증합니다.

- [ ] **Step 2: frontend 부재 실패 확인**

Run: cd reference-app/frontend; npm test

Expected: FAIL because the Vite project does not exist.

- [ ] **Step 3: Vite config와 BFF client 구현**

    export default defineConfig({
      server: {
        host: "rp.localhost",
        port: 3100,
        proxy: {
          "/bff": { target: "http://rp.localhost:8180", changeOrigin: false },
          "/oauth2": { target: "http://rp.localhost:8180", changeOrigin: false },
        },
      },
    });

bffFetch는 JSON/Problem Details만 처리하고 401이면 session을 unauthenticated로 바꿉니다. 로그인은 window.location.assign("http://rp.localhost:8180/oauth2/authorization/reference-app")로 BFF에 직접 이동합니다.

- [ ] **Step 4: session/profile UI 구현**

profile은 displayName/email, app roles/status, 회사, 조직/직위, HR roles snapshot과 bootstrap 안내를 구분해 표시합니다. APP_ADMIN badge는 local role임을 설명하고 COMPANY_ADMIN은 IdP snapshot임을 설명합니다. 앱 logout과 전체 로그아웃을 별도 버튼으로 둡니다.

- [ ] **Step 5: unit/lint/build 통과**

Run: cd reference-app/frontend; npm test; npm run lint; npm run build

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add reference-app/frontend
    git commit -m "feat: add oidc reference spa profile"

---

### Task 9: 최소 사용자 관리 SPA

**Files:**
- Create: reference-app/frontend/src/features/admin/app-user-api.ts
- Create: reference-app/frontend/src/features/admin/app-user-table.tsx
- Create: reference-app/frontend/src/features/admin/app-user-detail.tsx
- Create: reference-app/frontend/src/features/admin/app-user-table.test.tsx
- Create: reference-app/frontend/src/features/admin/app-user-detail.test.tsx
- Modify: reference-app/frontend/src/app.tsx

**Interfaces:**
- Consumes: /bff/admin/users APIs와 local APP_ADMIN role
- Produces: user list/detail, activate/disable, APP_USER/APP_ADMIN 편집

- [ ] **Step 1: APP_ADMIN role guard와 last-admin 오류 실패 테스트 작성**

APP_USER session에서는 admin navigation과 admin route content가 없어야 하며 URL 직접 접근 시 forbidden 화면이어야 합니다. 409 LAST_ACTIVE_ADMIN_REQUIRED는 generic error가 아니라 마지막 관리자 보호 설명으로 표시합니다.

- [ ] **Step 2: component 부재 실패 확인**

Run: cd reference-app/frontend; npm test -- app-user-table.test.tsx app-user-detail.test.tsx

Expected: FAIL.

- [ ] **Step 3: typed API와 table 구현**

table은 displayName, email, status, local roles, lastLoginAt을 보여주고 status/role filter를 제공합니다. issuer/sub는 detail의 외부 identity section에서만 보여줍니다. API path userId는 encodeURIComponent합니다.

- [ ] **Step 4: detail mutation 구현**

status와 roles는 각각 version을 포함해 전송합니다. APP_USER는 항상 유지하고 APP_ADMIN만 추가/제거할 수 있습니다. disable과 demote에는 confirm dialog를 사용하고 성공 뒤 session current user가 영향받으면 /bff/session을 재조회합니다.

- [ ] **Step 5: tests/lint/build 통과**

Run: cd reference-app/frontend; npm test -- app-user-table.test.tsx app-user-detail.test.tsx; npm run lint; npm run build

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add reference-app/frontend/src/features/admin reference-app/frontend/src/app.tsx
    git commit -m "feat: manage reference application users"

---

### Task 10: 실제 Chromium 상호운용성, public client와 문서

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthE2eFixtureSeeder.java
- Modify: backend/src/main/resources/application-dev.yaml
- Create: reference-app/backend/src/test/java/com/sweet/referenceapp/security/ExternalJwtResourceServerInteroperabilityTest.java
- Create: reference-app/playwright.config.ts
- Create: reference-app/e2e/oauth-oidc-reference-flow.spec.ts
- Create: docs/oauth-oidc-study-guide.md
- Modify: README.md

**Interfaces:**
- Consumes: 실제 IdP 8080, HR SPA 3000, reference BFF 8180, reference SPA 3100, 두 PostgreSQL
- Produces: 실제 browser redirect 증거, confidential BFF와 public PKCE 상호운용 검증, 재현 가능한 학습 문서

- [ ] **Step 1: deterministic local-only E2E fixture 실패 테스트 작성**

OAuthE2eFixtureSeeder는 dev profile과 app.oauth.e2e-fixture.enabled=true에서만 실행하며 기존 OIDCDEMO fixture가 없을 때 회사 OIDCDEMO(oidc-demo.local), ACTIVE 사용자 OIDCADMIN/admin@oidc-demo.local/OidcDemo1234!, COMPANY_ADMIN account, confidential client rp_local_7WmB7sW9dNxQ5mF3kC2vA8yJ를 idempotent하게 만듭니다. raw client secret ReferenceAppLocalSecret-DoNotReuse는 application-dev.yaml에서 읽어 BCrypt 저장하되 로그/API/event에 출력하지 않습니다. redirect는 http://rp.localhost:8180/login/oauth2/code/reference-app, post logout URI는 http://rp.localhost:3100/logged-out만 사용합니다.

- [ ] **Step 2: E2E가 fixture와 앱 부재로 실패하는지 확인**

Run: cd reference-app; npx playwright test e2e/oauth-oidc-reference-flow.spec.ts

Expected: FAIL before the multi-server setup and fixture exist.

- [ ] **Step 3: four-server Playwright orchestration 구현**

playwright.config.ts의 webServer는 backend 8080, HR Next 3000, reference backend 8180, reference Vite 3100을 각각 시작합니다. baseURL은 http://rp.localhost:3100이고 browser context는 clean storage로 시작합니다. Docker Compose의 두 DB health를 global setup에서 확인합니다.

- [ ] **Step 4: 실제 사용자 흐름 작성**

시나리오는 다음을 정확히 수행합니다.

1. reference SPA에서 로그인합니다.
2. idp.localhost 로그인 화면에서 OIDCDEMO COMPANY_ADMIN 자격 증명을 입력합니다.
3. client display name, 요청 scope와 설명이 있는 동의 화면에서 승인합니다.
4. rp.localhost callback 뒤 APP_USER와 정확히 한 명의 APP_ADMIN bootstrap을 확인합니다.
5. profile에서 email/company/organization/HR roles를 확인하되 browser storage와 network JSON response에 OAuth token이 없는지 확인합니다.
6. 새 scope 요청은 재동의, 기존 scope subset은 동의 생략을 검증합니다.
7. 앱 logout 뒤 IdP session 때문에 재로그인이 password 없이 진행되고, 전체 logout 뒤에는 password가 다시 필요함을 확인합니다.
8. local APP_ADMIN이 두 번째 local user를 disable/activate하고 role을 변경합니다.

- [ ] **Step 5: public client와 Resource Server contract 검증**

ExternalJwtResourceServerInteroperabilityTest는 discovery의 jwks_uri로 NimbusJwtDecoder를 구성하고 실제 Access Token의 RS256 signature, issuer, audience=auth-study-userinfo, 5분 expiry를 검증합니다. 별도 integration fixture public client public_local_B3qF8nR2xV6mK9sT4wY7는 redirect http://rp.localhost:3100/public-callback을 등록하고 secret 없이 PKCE S256로 Code를 교환합니다. secret을 제시하거나 verifier를 빼면 실패하는지 확인합니다. 이는 SPA가 browser에 access token을 보관하라는 의미가 아니며 프로토콜 상호운용 test fixture에만 둡니다.

- [ ] **Step 6: 학습 문서 작성**

docs/oauth-oidc-study-guide.md에 Authorization Code sequence, state/nonce/PKCE 역할, Discovery/JWKS/RS256 검증, ID Token과 Access Token과 UserInfo 차이, scope/consent/claim matrix, public/confidential/BFF 비교, issuer/tenant 차이, Spring extension point와 test path, refresh rotation/reuse, 두 logout, 운영과 local-only 차이, introspection/pairwise sub/DPoP/federation/SAML 후속 범위를 넣습니다. secret/token 예시는 구조만 보이는 축약값을 씁니다.

- [ ] **Step 7: 전체 검증**

Run: cd backend; .\gradlew.bat clean test

Expected: PASS.

Run: cd frontend; npm test; npm run lint; npm run build

Expected: PASS.

Run: cd reference-app/backend; .\gradlew.bat clean test

Expected: PASS.

Run: cd reference-app/frontend; npm test; npm run lint; npm run build

Expected: PASS.

Run: cd reference-app; npx playwright test

Expected: PASS in Chromium with the complete IdP/BFF/SPA flow.

- [ ] **Step 8: 민감정보와 독립성 검사**

Run: rg -n -i "access_token|refresh_token|id_token|client_secret|code_verifier" reference-app --glob "!**/node_modules/**" --glob "!**/build/**"

Expected: only type names, configuration keys and redaction tests; no captured raw values.

Run: rg -n "com\.sweet\.authstudy|jdbc:postgresql://localhost:5432/auth_study" reference-app/backend/src

Expected: no matches.

- [ ] **Step 9: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OAuthE2eFixtureSeeder.java backend/src/main/resources/application-dev.yaml reference-app/backend/src/test/java/com/sweet/referenceapp/security/ExternalJwtResourceServerInteroperabilityTest.java reference-app/playwright.config.ts reference-app/e2e/oauth-oidc-reference-flow.spec.ts docs/oauth-oidc-study-guide.md README.md
    git commit -m "test: verify oidc interoperability end to end"

---

## Reference Application Completion Gate

- [ ] reference-app/backend는 IdP code/package/DB를 dependency로 사용하지 않습니다.
- [ ] browser에는 RP_SESSION만 있고 OAuth token은 서버 메모리에만 있습니다.
- [ ] ID Token iss/sub와 UserInfo sub가 일치한 뒤만 JIT provisioning이 실행됩니다.
- [ ] 동시 최초 로그인에서도 APP_ADMIN bootstrap은 정확히 한 명입니다.
- [ ] bootstrap 이후 APP roles와 HR roles snapshot이 독립적으로 변합니다.
- [ ] 마지막 ACTIVE APP_ADMIN 보호가 transaction과 concurrency test로 고정됩니다.
- [ ] 앱 logout과 전체 OIDC logout의 차이를 Chromium에서 확인합니다.
- [ ] confidential BFF와 public PKCE fixture 모두 실제 Discovery/JWKS/Token endpoint와 상호운용됩니다.
- [ ] 네 애플리케이션의 test/lint/build와 전체 Playwright가 통과합니다.
- [ ] docs/oauth-oidc-study-guide.md만으로 local redirect와 token 흐름을 재현할 수 있습니다.
