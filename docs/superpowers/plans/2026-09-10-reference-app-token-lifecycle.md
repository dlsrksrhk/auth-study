# Reference App Token Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 인증된 BFF 요청의 토큰 갱신과 세션 복구 없는 앱·IdP 로그아웃을 구현합니다.

**Architecture:** Spring Refresh Token 교환과 기존 HttpSession authorized-client 저장소를 재사용합니다. 세션에 결합된 조율 상태가 단일 갱신과 종료를 직렬화하고, 외부 사본 저장은 별도 트랜잭션으로 수행합니다. 전체 로그아웃은 60초 일회용 continuation과 IdP 로그아웃 전용 JWT 검증을 연결합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Security OAuth2 Client 6.5.11, Spring MVC/JPA, PostgreSQL, JUnit 5, Testcontainers.

**Spec:** [승인된 Task 6 설계](../specs/2026-09-10-reference-app-token-lifecycle-design.md)

## Global Constraints

- Access Token의 만료까지 남은 시간이 30초 이하이면 갱신합니다.
- 자동 재시도는 하지 않습니다.
- 네트워크 호출 동안 조율 잠금과 DB 트랜잭션을 잡지 않습니다.
- 로컬 roles·status·createdAt·lastLoginAt은 보존하고 updatedAt은 갱신합니다.
- 무효화된 세션을 새로 만들거나 이전 세션의 결과를 새 로그인 세션에 적용하지 않습니다.
- 초기 네트워크 기본값: 연결 2초, 응답 3초, 전체 갱신 작업 대기 상한 10초, revocation 호출당 전체 3초.
- 전달 정보: 256비트 난수 ticket, 최대 60초, 최대 항목 수 1,000개, 원자적 단일 소비.
- 일반 로그인·API JWT decoder의 만료 검증은 변경하지 않습니다.
- 일반 API·SPA 상태·스토리지에 OAuth 토큰을 넣지 않습니다. ID Token URL 노출은 전체 로그아웃 redirect에만 허용합니다.
- CSRF·Origin 검사, RP_SESSION 속성, 기존 Task 5 로컬 사용자 권한 검증을 유지합니다.
- 분산 세션·Redis·백그라운드 정기 갱신·DB 스키마 변경·SPA 구현은 범위 밖입니다.

## 실행 기반과 파일 표기

계획 작성 기준은 `2bd8ed8`입니다. 실행 시 `superpowers:using-git-worktrees`로 `codex/reference-app-token-lifecycle` 브랜치의 독립 worktree를 준비합니다. 사용자 `frontend/.idea/`는 작업에 포함하지 않습니다. 이 문서 작성은 구현 시작이나 push 승인이 아닙니다.

아래 파일 경로 접두어는 정확히 다음 디렉터리를 의미합니다. 각 Files 목록은 이 접두어와 파일명을 결합한 실제 경로입니다.

| 접두어 | 디렉터리 |
|---|---|
| R | `reference-app/backend/src/main/java/com/sweet/referenceapp` |
| RT | `reference-app/backend/src/test/java/com/sweet/referenceapp` |
| I | `backend/src/main/java/com/sweet/authstudy` |
| IT | `backend/src/test/java/com/sweet/authstudy` |

각 단계의 Gradle 명령은 명시된 backend 디렉터리에서 실행합니다. Task별 파일만 `git add -- <Files 목록의 실제 경로>`로 stage하고 staged diff를 확인한 뒤 적힌 메시지로 커밋합니다. 소스와 테스트에 관계없는 파일은 stage하지 않습니다.

## Task 1: 로그인 시각을 보존하는 외부 사본 갱신

**Files:** Modify `R/user/domain/AppUser.java`; Create `R/user/application/AppExternalSnapshotService.java`; Test `RT/user/AppUserTest.java`, Create `RT/user/AppExternalSnapshotIntegrationTest.java`.

**Interfaces:** 기존 `AppUserRepository.findByIdentityForUpdate(String,String)`와 `updateSnapshot(AppUser)`를 소비합니다. `AppUser.refreshSnapshot(ExternalUserSnapshot next, Instant now)` 및 `AppExternalSnapshotService.refresh(UUID userId, ExternalIdentityProfile profile): AppUserView`를 제공합니다.

- [x] 사본 변경이 로그인 시각·권한을 보존하는 실패 테스트를 작성합니다. 기존 AppUserTest에 다음 독립 사례를 추가합니다.

```java
var before = Instant.parse("2026-09-10T00:00:00Z");
var snapshot = new ExternalUserSnapshot("a@example.test", "A", null, null, Set.of());
var original = AppUser.create(UUID.randomUUID(), "https://idp.test", "sub", snapshot, before);
var next = original.refreshSnapshot(snapshot, before.plusSeconds(60));
assertThat(next.lastLoginAt()).isEqualTo(before);
assertThat(next.updatedAt()).isEqualTo(before.plusSeconds(60));
assertThat(next.roles()).isEqualTo(original.roles());
```

- [x] `./gradlew.bat test --tests '*AppUserTest' --console=plain`을 실행하여 새 메서드 부재로 실패하는지 확인합니다.
- [x] 도메인 메서드와 서비스의 최소 구현을 추가합니다. 서비스에는 `@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)`을 사용하고 Clock을 주입합니다.

```java
public AppUser refreshSnapshot(ExternalUserSnapshot next, Instant now) {
    return new AppUser(id, issuer, subject, next, status, roles,
            createdAt, now, lastLoginAt, version);
}
// AppExternalSnapshotService.refresh의 트랜잭션 본문
var current = repository.findByIdentityForUpdate(profile.issuer().toString(), profile.subject())
        .orElseThrow(() -> new IllegalStateException("Local user unavailable"));
if (!current.id().equals(userId) || current.status() != AppUserStatus.ACTIVE) {
    throw new IllegalStateException("Local user unavailable");
}
return AppUserView.from(repository.updateSnapshot(
        current.refreshSnapshot(profile.snapshot(), clock.instant())));
```

- [x] 실제 PostgreSQL fixture로 기존 사용자 갱신, 없는 사용자·UUID 불일치·DISABLED 거절, 실패 시 사본 불변을 검증합니다. 초기 사용자는 기존 AppLocalLoginService로 생성하고, DB에서 last_login_at·role·bootstrap_state를 전후 비교합니다. 사용자 수가 증가하지 않는 것도 확인합니다.
- [x] `./gradlew.bat test --tests '*AppUserTest' --tests '*AppExternalSnapshotIntegrationTest' --console=plain`을 실행하여 통과시킵니다.
- [x] `feat: refresh external user snapshot without recording a login`으로 커밋합니다.

## Task 2: 제한 시간이 있는 토큰 교환·UserInfo·폐기

**Files:** Create `R/security/OAuthTokenLifecycleProperties.java`, `R/security/OAuthTokenHttpConfiguration.java`, `R/security/OAuthSessionTokenService.java`, `R/security/RefreshCandidate.java`, `R/security/OAuthTokenRevoker.java`; Modify `reference-app/backend/src/main/resources/application.yaml`, `reference-app/backend/src/main/resources/application-dev.yaml`; Test Create `RT/security/OAuthSessionTokenServiceTest.java`, `RT/security/OAuthTokenRevokerTest.java`, `RT/security/OAuthTokenLifecyclePropertiesTest.java`; Modify `RT/security/MockOidcIssuer.java`.

**Interfaces:** `OAuthSessionTokenService.refresh(OAuth2AuthorizedClient current, AppOidcUser principal): RefreshCandidate`; `RefreshCandidate`는 `OAuth2AuthorizedClient client`, `ExternalIdentityProfile profile` accessor를 가진 final class이며 toString에 토큰을 출력하지 않습니다. `OAuthTokenRevoker.revoke(ClientRegistration registration, OAuth2RefreshToken token): void`는 오류를 외부로 전파하지 않는 1회 best-effort 호출입니다.

- [x] mock issuer에 refresh grant 분기, `/revoke`, 각각의 카운터·상태·latch를 추가합니다. 기존 authorization-code 응답은 보존하고 refresh 응답은 새 access/refresh만 반환합니다. 별도 서버 executor를 종료 시 닫고 reset에서 latch·counter를 초기화합니다.

```java
// grant_type == refresh_token 분기의 정상 응답
respond(exchange, 200, Map.of("access_token", "test-access-token-refreshed",
        "token_type", "Bearer", "expires_in", 300,
        "refresh_token", "test-refresh-token-refreshed", "scope", "openid profile email"));
```

- [x] token client에 저장소 접근이 없고 UserInfo 원본 sub를 검증한다는 실패 테스트를 작성합니다. 테스트는 mock issuer에 실제 HTTP로 요청하고 `grant_type=refresh_token`, Basic client 인증, UserInfo Bearer 헤더를 확인합니다. `invalid_grant`, 잘못된 JSON, 회전 토큰 누락, UserInfo sub 불일치 각각을 독립 사례로 만듭니다.
- [x] `./gradlew.bat test --tests '*OAuthSessionTokenServiceTest' --tests '*OAuthTokenRevokerTest' --console=plain`을 실행해 실패를 확인합니다.
- [x] 로컬 jar에서 확인한 `RestClientRefreshTokenTokenResponseClient`를 사용합니다. 자동 authorized-client manager는 사용하지 않아 검증 전 저장을 막습니다.

```java
var result = refreshClient.getTokenResponse(new OAuth2RefreshTokenGrantRequest(
        current.getClientRegistration(), current.getAccessToken(), current.getRefreshToken()));
var successor = new OAuth2AuthorizedClient(current.getClientRegistration(), current.getPrincipalName(),
        result.getAccessToken(), Objects.requireNonNull(result.getRefreshToken()));
```

`setRestClient`로 form/token response converter와 OAuth2ErrorResponseErrorHandler를 구성합니다. UserInfo는 같은 제한 시간 정책의 HTTP client로 원본 Map을 받아 `new OidcUserInfo(claims)`와 기존 mapper에 전달합니다. JSON 타입을 문자열로 강제 변환하지 않습니다. 새 refresh token 획득 이후의 검증 실패는 successor 폐기를 시도한 다음 고정 예외로 종료합니다.

- [x] revocation은 form `token`, `token_type_hint=refresh_token`과 client_secret_basic으로 고정 endpoint에 POST합니다. redirect는 따라가지 않습니다. 2xx만 성공으로 취급하고 실패 body는 출력하지 않습니다. form credential 인코딩은 OAuth Basic 규칙을 적용하고 예약 문자 secret 테스트를 넣습니다.
- [x] `reference.token-lifecycle` properties에 connect-timeout=2s, read-timeout=3s, refresh-timeout=10s, revocation-timeout=3s, handoff-ttl=60s, handoff-capacity=1000 및 고정 revocation-uri/end-session-uri를 둡니다. 개발 URI는 IdP `/oauth2/revoke`, `/connect/logout`입니다. URI의 userinfo·fragment·임의 redirect를 거절하고 테스트는 mock origin으로 모두 override합니다.
- [x] Apache HttpClient의 연결·응답·socket 제한을 Spring HttpComponentsClientHttpRequestFactory와 연결하고 자동 재시도·redirect를 명시적으로 끕니다. JDK transport의 drop/응답 body 시간 제한 회귀에서 확인된 문제로 구현 선택을 변경했습니다. revocation 전체 상한은 별도 bounded 작업 deadline으로 보장하며 timeout 후 재시도하지 않습니다. outbound HTTP redirect와 재시도 여부를 실제 요청 카운터로 검증합니다.
- [x] `./gradlew.bat test --tests '*OAuthSessionTokenServiceTest' --tests '*OAuthTokenRevokerTest' --tests '*OAuthTokenLifecyclePropertiesTest' --console=plain`을 실행합니다. 성공·오류·시간 초과마다 각 endpoint 호출 수가 1인지 확인합니다.
- [x] `feat: add bounded refresh and revocation protocol clients`로 커밋합니다.

## Task 3: 세션 단일 갱신과 종료 우선순위

**Files:** Create `R/security/OAuthSessionRefreshCoordinator.java`, `R/security/OAuthSessionLifecycleFilter.java`; Modify `R/security/CurrentAppUserFilter.java`, `R/security/RpSessionCleaner.java`, `R/security/OAuth2ClientSecurityConfig.java`; Test Create `RT/security/OAuthSessionRefreshCoordinatorTest.java`, `RT/security/OAuthSessionLifecycleFilterTest.java`; Modify `RT/security/OAuth2ClientConfigurationIntegrationTest.java`, `RT/security/ReferenceSecurityPropertiesTest.java`.

**Interfaces:** `OAuthSessionRefreshCoordinator.ensureFresh(HttpServletRequest request, HttpServletResponse response, OAuth2AuthenticationToken authentication): Optional<AppUserView>`는 갱신하지 않았으면 empty, 성공하면 새 로컬 사용자 view, 실패하면 고정 `SessionRefreshException`을 던집니다. 예외는 coordinator의 중첩 final class입니다. `static close(HttpSession session): void`는 기존 live 세션의 상태가 없으면 종료 sentinel을 기록하며, 새 HttpSession을 생성하지 않습니다. Task 2 token service와 Task 1 snapshot service를 소비합니다.

- [x] 동일 MockHttpSession을 사용하는 8개 요청과 token-service latch로 단일 호출·공유 결과 실패 테스트를 작성합니다. 테스트 executor는 try/finally에서 종료합니다. 다른 세션은 서로의 latch를 기다리지 않아야 합니다.
- [x] `./gradlew.bat test --tests '*OAuthSessionRefreshCoordinatorTest' --console=plain`을 실행해 실패를 확인합니다.
- [x] 세션 attribute에 `HttpSessionBindingListener` 구현 상태를 넣습니다. 상태는 closing, 한 개의 future, 작업 deadline만 유지합니다. attach는 같은 세션 mutex에서 한 번만 수행하고 `valueUnbound`는 closing과 대기 future 실패를 설정합니다. 전역 sessionId→token map을 만들지 않습니다.

```java
// 잠금 안: 소유권 획득 또는 기존 future 참여
if (state.closing) throw new SessionRefreshException();
// 소유자는 잠금 밖에서 refresh → snapshot.refresh를 실행합니다.
// 결과 게시는 종료와 동일 mutex 안에서 실행합니다.
if (state.closing || request.getSession(false) != originalSession) {
    throw new SessionRefreshException();
}
authorizedClients.saveAuthorizedClient(candidate.client(), authentication, request, response);
```

owner의 결과 게시와 request/response 접근은 원래 servlet 요청 스레드에서 실행합니다. 프로토콜 교환과 DB 저장은 최대 32개의 bounded worker에서 실행하여 전체 대기 상한을 보장합니다. worker가 후속 candidate의 폐기 책임을 갖고, 게시 승인을 받은 경우에만 책임을 해제합니다. 다른 스레드에 request/response를 넘겨 저장하지 않습니다. 공통 deadline에 도달하면 상태를 종료하고 future를 실패시킵니다. 실제 작업이 늦게 완료되면 successor를 폐기하고 저장하지 않습니다. future가 끝나면 token을 담은 candidate 참조를 해제합니다. snapshot 반환 직후에도 deadline을 검사합니다.

소유권을 얻은 뒤 저장소의 최신 authorized client를 다시 읽고 만료가 30초 넘게 남았다면 교환하지 않습니다. 이미 회전한 이전 토큰을 요청 진입 시점의 참조에서 꺼내 쓰지 않습니다. 조율 future는 AppUserView만 게시하며 OAuth 토큰은 작업 지역 변수와 기존 세션 저장소에만 둡니다. 갱신 실패 상태를 재사용 가능한 열림 상태로 되돌리지 않습니다.

- [x] `RpSessionCleaner.clear`의 무효화 이전에 `close(session)`을 호출해 disabled·callback 실패·로그아웃 정리도 동일 종료 경계를 거치게 합니다. session invalidation과 authorized-client 저장은 동일 mutex에서 처리합니다. 이미 무효화된 세션은 종료된 것으로 취급합니다.
- [x] 필터 순서를 CSRF/Origin → CurrentAppUserFilter → OAuthSessionLifecycleFilter → AuthorizationFilter로 명시합니다. 새 필터는 로그인·csrf·logout·continuation을 제외하고 REQUEST만 처리합니다. `CurrentAppUserFilter`는 continuation과 두 logout POST를 DB 조회에서 제외해 DB 장애 때문에 로그아웃이 차단되지 않도록 합니다. 로그아웃 POST 인증 요구 자체는 유지합니다.
- [x] 성공 후 현재 사용자 서비스를 다시 조회해 대기 동안의 최신 역할·상태를 검증하고, 새 request-local principal과 CurrentAppUser attribute를 구성합니다. 공유 session SecurityContext의 로컬 권한 사본을 갱신하지 않습니다. 외부 정보는 DB에서 읽으므로 원래 ID Token과 OIDC delegate를 유지할 수 있습니다.

```java
catch (OAuthSessionRefreshCoordinator.SessionRefreshException failure) {
    cleaner.clear(request, response);
    // /bff/session만 익명 컨트롤러로 진행; 나머지는 401로 종료합니다.
    if (!"/bff/session".equals(request.getServletPath())) {
        response.setStatus(401);
        return;
    }
}
```

- [x] 만료 31초/30초, authorized client 누락, refresh 실패, DB 실패, 무효화·deadline·로그아웃이 게시보다 먼저인 사례를 검증합니다. 후속 요청이 같은 refresh token으로 재시도하지 않는지도 확인합니다. 기존 DBless security test configuration에 새 dependency mock 또는 실제 protocol bean과 테스트 URI를 제공합니다.
- [x] `./gradlew.bat test --tests '*OAuthSessionRefreshCoordinatorTest' --tests '*OAuthSessionLifecycleFilterTest' --tests '*CurrentAppUserFilterTest' --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*ReferenceSecurityPropertiesTest' --console=plain`을 통과시킵니다.
- [x] `feat: coordinate refresh with session termination`으로 커밋합니다.

## Task 4: 앱 로그아웃과 일회용 전체 로그아웃

**Files:** Create `R/security/LogoutHandoffStore.java`, `R/security/ReferenceLogoutService.java`, `R/security/ReferenceLogoutController.java`; Modify `R/security/OAuth2ClientSecurityConfig.java`; Test Create `RT/security/LogoutHandoffStoreTest.java`, `RT/security/ReferenceLogoutIntegrationTest.java`; Modify `RT/security/LocalLoginHttpTestSupport.java`.

**Interfaces:** `LogoutHandoffStore.issue(String idToken, String clientId): String`은 ticket 반환, `consume(String ticket): Optional<URI>`는 한 번만 IdP Location 반환입니다. 저장소는 고정 endpoint·redirect properties를 주입받습니다. `ReferenceLogoutService.logout(HttpServletRequest,HttpServletResponse,OAuth2AuthenticationToken,boolean identityProvider): Optional<URI>`는 로컬 종료 후 full이면 continuation URI를 반환합니다. 전달 정보 실패는 중첩 `ContinuationUnavailableException`으로 구분합니다.

- [x] 고정 Clock 기반 저장소 테스트를 작성하고 `./gradlew.bat test --tests '*LogoutHandoffStoreTest' --console=plain`으로 실패를 확인합니다.

```java
String ticket = store.issue("signed-id-token", "reference-client");
assertThat(store.consume(ticket)).isPresent();
assertThat(store.consume(ticket)).isEmpty();
assertThat(store.consume("malformed")).isEmpty();
```

- [x] 32바이트 SecureRandom→URL-safe Base64 ticket을 구현합니다. issue/consume/expiry 정리는 같은 잠금 경계에서 만료 확인 후 삭제합니다. 60초 경계에서는 expired로 판정합니다. capacity 초과 시 기존 유효 ticket을 빼앗지 않고 실패합니다. 1초 주기의 청소 작업은 bean 종료 시 중단합니다. 저장 payload·URI를 toString/log로 출력하지 않습니다.
- [x] logout 서비스에서 원래 client·ID Token을 확보하고 종료 선점 → 로컬 정리 → 제한된 revoke 순서를 구현합니다. full handoff 확보 실패를 저장해두더라도 finally에서 로컬 정리와 폐기를 수행한 후 예외를 반환합니다. 보유 Refresh Token 폐기는 세 인자 RpSessionCleaner가 단독으로 소유하며 logout 서비스는 중복 폐기하지 않습니다. continueUrl은 요청 Host·Forwarded·query가 아닌 ReferenceSecurityProperties.bffOrigin의 절대 주소입니다.
- [x] 컨트롤러에 아래 계약을 추가합니다. 성공/실패 모두 no-store/no-referrer를 설정하며 anonymous 정책은 보안 체인에 맡깁니다.

```java
// POST /bff/logout
return ResponseEntity.noContent().build();
// POST /bff/logout/identity-provider
return ResponseEntity.ok(Map.of("continueUrl", continuation.toString()));
// 전달 정보 생성 실패
return ResponseEntity.status(503).body(Map.of("code", "logout_continuation_unavailable"));
// GET /bff/logout/continue/{ticket}
return store.consume(ticket)
        .map(uri -> ResponseEntity.status(303).location(uri).build())
        .orElseGet(() -> ResponseEntity.status(410).build());
```

- [x] GET continuation만 permitAll에 추가합니다. POST는 기존 CSRF/Origin과 authenticated를 유지합니다. Location 구성은 고정 end-session URI에 URI builder로 `id_token_hint`, `client_id`, 고정 SPA `/logged-out`만 인코딩합니다.
- [x] 실제 HTTP fixture에 테스트 전용 CSRF 조회 helper를 추가해 logout POST를 실행합니다. app 204·쿠키 만료·이전 세션401, full JSON 토큰 비노출·GET303·재사용410, capacity 실패503·세션 종료를 확인합니다. ID Token은 303 Location의 승인된 예외에서만 확인하고 기존 assertNoTokenLeak을 해당 응답에 무조건 적용하지 않습니다.
- [x] `./gradlew.bat test --tests '*LogoutHandoffStoreTest' --tests '*ReferenceLogoutIntegrationTest' --tests '*BffSessionSecurityIntegrationTest' --console=plain`을 통과시킵니다.
- [x] `feat: add app logout and one-time IdP logout continuation`으로 커밋합니다.

## Task 5: IdP 현재 세션에 결합된 만료 ID Token 허용

**Files:** Create `I/oauth/infrastructure/OidcLogoutTokenValidator.java`; Modify `I/oauth/infrastructure/OAuthJwkSourceConfiguration.java`, `I/authorization/AuthorizationServerSecurityConfig.java`; Test Create `IT/oauth/infrastructure/OidcLogoutTokenValidatorTest.java`; Modify `IT/oauth/acceptance/OAuthProtocolSecurityAcceptanceTest.java`.

**Interfaces:** `OidcLogoutTokenValidator(Clock clock)` implements `OAuth2TokenValidator<Jwt>`. 새 bean `@Bean("oauthLogoutJwtDecoder")`는 기존 JWKSource·RS256 서명 검증을 사용하고 `JwtIssuerValidator`와 이 validator를 연결합니다. `OidcLogoutSuccessHandler`에만 새 qualified decoder를 전달합니다. 기존 `oauthJwtDecoder`와 handler의 session-binding 검증은 보존합니다.

- [x] expired이지만 iat/exp 순서가 올바른 토큰 허용, 미래 iat/nbf·시간 claim 누락/오류·exp<=iat 거절 테스트를 작성합니다. 허용 clock skew는 60초로 명시하고 Clock으로 경계를 검증합니다.
- [x] IdP 디렉터리에서 `./gradlew.bat test --tests '*OidcLogoutTokenValidatorTest' --console=plain`으로 실패를 확인합니다.
- [x] exp 경과만 허용하는 검증기를 구현합니다. 새 decoder에도 Nimbus의 기본 claim timestamp 검증이 중복되어 expired를 먼저 거절하지 않도록 processor claim verifier를 명시하고, 서명 이후의 시간 검증을 이 validator가 전담하게 합니다. 서명 검증은 절대로 우회하지 않습니다.

```java
Instant now = clock.instant();
Instant iat = jwt.getIssuedAt();
Instant exp = jwt.getExpiresAt();
Instant nbf = jwt.getNotBefore();
boolean valid = iat != null && exp != null && exp.isAfter(iat)
        && !iat.isAfter(now.plusSeconds(60))
        && (nbf == null || !nbf.isAfter(now.plusSeconds(60)));
return valid ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
```

- [x] acceptance test의 기존 expired 거절 묶음을 수정합니다. 기존 묶음은 모든 사례에 잘못된 state도 전달하므로 각 거절 원인을 검증하지 못합니다. 정상 state로 client·audience·subject·URI·서명을 각각 독립 검증하고, 잘못된 state는 별도 사례로 남깁니다.
- [x] 정상 발급 ID Token의 sub/aud/sid/auth_time을 그대로 복사하고 iat/exp만 과거로 바꿔 실제 테스트 signer로 재서명합니다. 현재 binding과 일치하는 GET `/connect/logout` 성공, 다른 세션 GET 거절, session 없음 거절, 일반 decoder의 같은 expired 토큰 거절을 확인합니다. 기존 POST 성공 테스트도 유지합니다.
- [x] `./gradlew.bat test --tests '*OidcLogoutTokenValidatorTest' --tests '*OAuthProtocolSecurityAcceptanceTest' --tests '*SecurityChainIsolationIntegrationTest' --console=plain`을 통과시킵니다.
- [x] `fix: accept session-bound expired ID tokens for IdP logout`으로 커밋합니다.

## Task 6: 실제 HTTP 경쟁 조건과 회귀 검증·문서

**Files:** Create `RT/security/TokenLifecycleHttpIntegrationTest.java`; Modify `RT/security/MockOidcIssuer.java`, `RT/security/LocalLoginHttpTestSupport.java`, `reference-app/backend/README.md`, 이 계획과 설계의 상태; Create `docs/superpowers/reports/2026-09-10-reference-app-token-lifecycle-verification.md`.

**Interfaces:** Task 1–5의 공개 HTTP 계약만 검증합니다. 테스트 fixture의 `send(String,String,String,String,String...)`, `login()`과 mock issuer 카운터를 사용합니다. 토큰 expiry 변경은 테스트 configuration 또는 짧은 발급 수명으로 수행하며 production용 테스트 endpoint를 만들지 않습니다.

- [x] mock issuer의 초기 Access Token 수명을 테스트마다 제어하고 refresh 수명은 300초로 유지합니다. 실제 로그인한 세션에 동시 `/bff/profile` 요청을 보내고 원래 code 교환을 제외한 refresh/UserInfo 증가량이 각각 1인지 검증합니다.

```java
String cookie = login();
var response = send("GET", "/bff/profile", cookie, "");
assertThat(response.statusCode()).isEqualTo(200);
assertNoTokenLeak(response);
```

- [x] `./gradlew.bat test --tests '*TokenLifecycleHttpIntegrationTest' --console=plain`으로 새 경쟁 조건 테스트가 요구하는 동작을 확인하고 발견된 결함만 수정합니다.
- [x] latch로 token 교환 중 logout, DB 사본 커밋 후 게시 전 logout, deadline 이후 successor 도착을 재현합니다. 각 경우 쿠키 만료·이전 세션401·후속 토큰 폐기 카운터를 검증합니다. 임의 sleep 대신 latch와 제한된 future wait를 사용합니다.
- [x] invalid_grant·UserInfo 실패·DB 저장 실패 후 session 익명200과 profile401, 자동 재시도 없음, 원격 revoke 실패에도 local 종료, 보호 API 업무 로직 미진입을 확인합니다. 로그아웃 제외 경로와 CSRF/Origin403에서 token counter가 늘지 않아야 합니다.
- [x] Reference App에서 `./gradlew.bat clean test --console=plain`, IdP에서 `./gradlew.bat test --console=plain`을 실행합니다. Docker/DB 환경 문제와 assertion 실패를 구분하여 해결하고 통과한 뒤 불필요하게 반복 실행하지 않습니다.
- [x] 검증 기록에 실행 명령·exit code·JUnit XML의 tests/failures/errors/skipped 합계·경쟁 조건 결과·실제 브라우저 E2E 미실행 범위를 기록합니다. 기존 Task 5의 209개 수치를 새 테스트 합계로 재사용하지 않습니다.
- [x] README에 호출 예시, 고정 IdP endpoint, timeout 설정, 60초 continuation 실패/재사용 처리, ID Token URL·로그 예외, SPA 구현이 Task 8–10에 남는 점을 추가합니다.

```javascript
const response = await fetch('/bff/logout/identity-provider', {
  method: 'POST', credentials: 'include', headers: { 'X-CSRF-TOKEN': csrfToken }
});
if (response.ok) {
  const { continueUrl } = await response.json();
  window.location.assign(continueUrl);
}
```

위 예시는 SPA의 BFF proxy 경유를 전제로 하며 오류일 때도 로컬 로그인 상태를 제거해야 한다고 설명합니다. 실제 SPA 코드에는 이번 Task에서 적용하지 않습니다.

- [x] `git diff --check`와 최종 변경 검토 후 `test: verify token lifecycle and logout races`로 커밋합니다. 검증된 결과만 사용자에게 보고하고 병합·push는 별도 사용자 지시를 따릅니다.

## 자체 검토 결과와 실행 순서

Task 1은 사본·로컬 상태, Task 2는 프로토콜·시간 제한, Task 3은 요청/세션 경쟁, Task 4는 두 로그아웃과 전달 정보, Task 5는 IdP 예외, Task 6은 HTTP 결합·회귀·문서 요구를 담당합니다. 설계의 실패 정책과 응답표 전 항목을 해당 테스트에 배정했습니다. 타입과 메서드 이름은 task 간 일치하며 새 타입은 Files 또는 Interfaces에 정의했습니다.

실행은 Task 1 → 2 → 3 → 4 → 5 → 6 순서입니다. 각 task의 테스트와 검토를 통과한 뒤 다음 task로 진행합니다. Tasks 1–6 구현·검토와 최종 회귀 검증을 완료했습니다. Reference App clean test 270개와 IdP test 486개가 실패·오류·건너뜀 없이 통과했습니다. IdP 최종 명령은 변경 없음으로 UP-TO-DATE이며 기존 XML 결과를 확인했습니다. 결정 이유와 실행 증거·브라우저 E2E 한계는 [검증 보고서](../reports/2026-09-10-reference-app-token-lifecycle-verification.md)에 기록했습니다.
