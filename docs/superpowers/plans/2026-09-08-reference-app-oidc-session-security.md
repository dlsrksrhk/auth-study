# Reference App OIDC Session Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Task 4의 OIDC 로그인과 BFF 세션 보안을 실제 HTTP 기반 테스트로 구현·검증합니다.

**Architecture:** Spring Security의 OIDC login과 HttpSession을 사용하고, 공개 주소·Origin·callback 경계를 설정값으로 고정합니다. 표준 프로토콜 구현을 재사용하며 state 사전 검증, session-only token 저장과 CSRF 헤더 정책만 좁게 확장합니다. 로컬 사용자 연결은 Task 5입니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Security 6.5.11, Gradle 9.5.1, JUnit 5, embedded Tomcat, JDK HttpServer, Nimbus JOSE JWT, PostgreSQL Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-08-reference-app-oidc-session-security-design.md` (사용자 승인 완료)

## Global Constraints

- 개발 issuer `http://idp.localhost:8080`, BFF `http://rp.localhost:8180`, SPA `http://rp.localhost:3100`.
- callback `/login/oauth2/code/reference-app`은 고정 BFF origin에 붙이며 `{baseUrl}`을 사용하지 않습니다. Forwarded/X-Forwarded-*는 신뢰하지 않습니다.
- `client_secret_basic`, `authorization_code`, scopes `openid profile email hr.company hr.organization hr.roles`, PKCE S256.
- `RP_SESSION`: HttpOnly, host-only, Path=/, SameSite=Lax, idle 30분, cookie-only tracking, Secure 기본 true와 dev/test HTTP false.
- token, pending request, SecurityContext는 세션 메모리 안에만 보관합니다. DB·disk·사용자별 전역 token cache 금지.
- `GET /bff/csrf`는 `csrfHeaderName`과 `csrfToken`만 반환합니다. 헤더 `X-CSRF-TOKEN`만 허용하며 session repository와 XOR token 처리를 사용합니다.
- unsafe BFF 요청은 exact SPA Origin과 유효한 CSRF token을 모두 요구합니다. TRACE는 거절합니다.
- 콜백 실패는 세션과 cookie를 정리하고 고정 SPA `/login-error?code=oidc_login_failed`로 이동합니다. 성공은 SPA `/`로 이동합니다.
- state는 code 교환 전에 constant-time 비교하며 누락·중복·불일치를 거절합니다. exp/iat 필수, clock skew 60초. nonce 누락·불일치를 거절합니다.
- Task 5–10의 사용자 API, 앱 권한 부여, token refresh/logout, SPA는 구현하지 않습니다. IdP 코드·DB·key를 공유하지 않습니다.
- 테스트는 실제 security chain/code exchange를 실행합니다. `oauth2Login()` 주입만으로 프로토콜 검증을 대체하지 않습니다.
- 작업 디렉터리 `C:/dev/git/auth-study/.worktrees/reference-app-oidc-session`; 기존 `frontend/.idea/`를 건드리거나 스테이징하지 않습니다.

## Task map

로그인 요청·콜백·세션·CSRF는 같은 SecurityFilterChain과 session lifecycle을 공유하므로 하나의 통합 구현 작업으로 검토합니다. 내부 단계마다 실패 테스트→구현→검증을 반복합니다. 별도 구현 에이전트를 동시에 투입하지 않습니다.

### Task 1: OIDC 로그인과 BFF 세션 보안

**Files:**
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/ReferenceSecurityProperties.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/PkceAuthorizationRequestResolver.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/OidcCallbackGuard.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/SessionAuthorizationRequestRepository.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/OidcLoginFailureHandler.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/BffOriginGuard.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/HeaderOnlyCsrfTokenRequestHandler.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/BffLoginController.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/CsrfController.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/security/MockOidcIssuer.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/security/OAuth2ClientConfigurationIntegrationTest.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/security/BffSessionSecurityIntegrationTest.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/security/ReferenceSecurityPropertiesTest.java`
- Modify: `reference-app/backend/src/main/resources/application.yaml`
- Modify: `reference-app/backend/src/main/resources/application-dev.yaml`
- Modify: `reference-app/backend/src/test/resources/application-test.yaml`
- Create: `reference-app/backend/README.md` (구현 결과에 맞춘 실행·검증 절차)

**Interfaces:**
- Consumes: Boot OAuth2 client registration properties, `ClientRegistrationRepository`, standard OIDC provider/decoder and authorization request repository.
- Produces: validated `ReferenceSecurityProperties(URI bffOrigin, URI spaOrigin)` with `callbackUri()`, `successUri()`, `failureUri()`; `SecurityFilterChain`; GET login/CSRF routes.
- Produces: `OidcLoginFailureHandler.onAuthenticationFailure(request, response, exception)` as the single cleanup path used by callback guard and OIDC failure.
- Produces: standard `OAuth2AuthenticationToken` only; no local AppUser principal or application-role mapping.

- [ ] **Step 1: Add real HTTP test issuer and failing authorization/CSRF tests.**

Use JDK `HttpServer` on loopback ephemeral port with Discovery, JWKS, token and UserInfo paths. Sign ID Tokens with generated Nimbus RSA keys. Keep the test server/configuration lifecycle in test sources. Capture token form and Authorization header to prove PKCE verifier and Basic authentication. Invalid fixtures vary only one field from a valid token. Use embedded BFF on a random port and a test property supplier resolving that port before callback construction; alternatively reserve a loopback port for the test server and release immediately before startup. Keep same-host browser session isolation separate from the IdP fixture's token requests.

Example assertions (the helper should use `java.net.http.HttpClient` with redirects disabled):

```java
assertThat(login.statusCode()).isEqualTo(302);
assertThat(login.headers().firstValue("Location")).contains(bffOrigin + "/oauth2/authorization/reference-app");
assertThat(authorizationParameters.get("code_challenge_method")).isEqualTo("S256");
assertThat(authorizationParameters.get("redirect_uri")).isEqualTo(bffOrigin + "/login/oauth2/code/reference-app");
assertThat(csrfJson.fieldNames()).toIterable().containsExactlyInAnyOrder("csrfHeaderName", "csrfToken");
```

Tests use the real application configuration with datasource/JPA/Flyway excluded only in this HTTP fixture if no persistence is needed. Existing full application tests retain Testcontainers. Do not add production test switches.

- [ ] **Step 2: Run RED and record concrete failures.**

```powershell
cd reference-app/backend
.\gradlew.bat test --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest'
```

Expected: configured login/CSRF routes and cookie policy do not exist yet, giving status/redirect assertion failures. Resolve test harness startup mistakes before interpreting failure as RED.

- [ ] **Step 3: Add validated configuration, authorization request and CSRF policies.**

Use a configuration properties record under `reference.security` for the two origins; reject invalid scheme/host/user-info/path/query/fragment/wildcard values in its constructor. Derive routes with literal fixed paths and do not use incoming host to build redirects. Use Spring Boot session settings rather than another cookie property wrapper.

```yaml
server:
  forward-headers-strategy: none
  servlet:
    session:
      timeout: 30m
      tracking-modes: cookie
      persistent: false
      cookie:
        name: RP_SESSION
        path: /
        http-only: true
        same-site: lax
        secure: true
```

Client provider issuer and registration live in dev properties. Use the existing plan's client ID `rp_local_7WmB7sW9dNxQ5mF3kC2vA8yJ` and secret `ReferenceAppLocalSecret-DoNotReuse` as local defaults with environment overrides. Redirect uses `${reference.security.bff-origin}/login/oauth2/code/reference-app`. Set dev/test cookie secure=false.

Explicitly use `HttpSessionOAuth2AuthorizedClientRepository` so Boot cannot select a principal-keyed global authorized-client service as the token lifetime owner. Store SecurityContext in HttpSession and retain session fixation protection. Configure the standard resolver with `OAuth2AuthorizationRequestCustomizers.withPkce()`.

```java
var csrfRepository = new HttpSessionCsrfTokenRepository();
csrfRepository.setHeaderName("X-CSRF-TOKEN");
// HeaderOnlyCsrfTokenRequestHandler delegates exposure and XOR decoding to
// XorCsrfTokenRequestAttributeHandler, but resolves null when the header is absent.
```

Origin guard requires one exact Origin on unsafe `/bff/**`. Headers containing lists, multiple entries, null, different scheme/host/port fail with 403. No CORS allowlist is opened. GET login and CSRF are public; `/bff/session` GET is reserved public; other BFF routes authenticated with API 401. Disable form login, Basic and default logout; deny other routes except the internal error dispatch. Deny TRACE.

- [ ] **Step 4: Run GREEN for authorization, cookie and CSRF behavior.**

Run the same focused command. Verify real `/bff/csrf` response→header→test mutation, not Spring test `.with(csrf())`. Preserve XOR masking, no-store and header-only semantics. Authenticated test-only mutation endpoints must never ship in main sources.

- [ ] **Step 5: Add failing callback, cleanup and token-validation tests.**

Run a complete authorization→callback code exchange with real signed tokens and assert session fixation rotation, server-side authorized client and no token in HTTP responses. Test state missing/mismatch/duplicate before token HTTP requests, callback authority mismatch even with forged forwarded headers, missing/incorrect nonce, bad issuer/audience/signature/kid, missing exp/iat, expired/future iat, callback replay, IdP error and token endpoint failures. Assert old cookie does not authorize further access after failure and cleanup does not create a fresh session.

```java
assertThat(failed.headers().firstValue("Location")).contains(spaOrigin + "/login-error?code=oidc_login_failed");
assertThat(issuer.tokenRequestCount()).isEqualTo(0); // invalid state fixture only
assertThat(protectedRequestWithOldCookie.statusCode()).isEqualTo(401);
```

Run the focused suite and retain output proving the absent behavior fails before adding callback-specific code. For upstream protections that already pass, document them as characterization coverage rather than manufacturing a failure.

- [ ] **Step 6: Implement callback guard and common cleanup; keep standard validators.**

Guard only the registration's callback path. Compare expected authority/path before code exchange, require single state and valid response shape, read pending request without relying on a state-based lookup that would hide a mismatch. The resolved standard repository is final and has no public peek; implement a narrow `SessionAuthorizationRequestRepository` with a single session-held request and a peek accessor, preserving standard lifetime conventions rather than mirroring state into another attribute. Compare UTF-8 state bytes with `MessageDigest.isEqual` after rejecting absent values. Invoke common failure handler and return on error.

Failure handler clears SecurityContext, invalidates an existing session, expires the host-only RP_SESSION cookie at `/`, and redirects to the configured generic failure URI. Do not save the exception in a new session. Success handler ignores saved requests and redirects only to `successUri()`.

Inspect `OidcIdTokenDecoderFactory` and `OidcIdTokenValidator` from resolved 6.5.11. Preserve standard issuer/audience/azp/signature/nonce checks. Add only missing exp/iat/skew validation using `OAuth2TokenValidator<Jwt>` and the OIDC decoder factory's validator factory. A token must contain exp/iat and satisfy 60-second skew. Do not require auth_time when max_age was not requested.

- [ ] **Step 7: Run focused and full regression; document actual usage.**

```powershell
.\gradlew.bat test --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest' --tests '*ReferenceSecurityPropertiesTest'
.\gradlew.bat test
git diff --check
```

Count tests/failures/errors/skips from fresh XML. README documents IdP startup/client registration prerequisites, fixed dev origins, BFF command, CSRF contract, intermediate Task 4 OIDC principal and absent Task 5 APIs. State whether browser E2E was run. No secrets or OAuth tokens in logs or reports.

- [ ] **Step 8: Commit only this task's files and produce a review report.**

Stage `reference-app/backend/src/main/java/com/sweet/referenceapp/security`, the three listed configuration files, `reference-app/backend/src/test/java/com/sweet/referenceapp/security`, and `reference-app/backend/README.md`. Check the staged diff before committing.

```powershell
git diff --cached --check
git commit -m "feat: configure reference app oidc login and session security"
```

Report RED/GREEN commands and outputs, final test totals, implementation commits, any remaining concern and mapping to all 11 spec verification criteria. Controller performs independent review while rerunning required verification if fixes change the code. No merge or push is included.
