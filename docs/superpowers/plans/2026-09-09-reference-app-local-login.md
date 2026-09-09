# Reference App Local Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검증된 OIDC 로그인을 독립 앱 사용자와 연결하고, 현재 DB 상태·권한을 사용하는 session/profile API를 제공합니다.

**Architecture:** 표준 OidcUserService에 위임하는 서비스가 허용 claim을 변환하고 기존 JIT·bootstrap과 ACTIVE 검사를 한 트랜잭션으로 연결합니다. OAuth2AuthenticationToken과 HttpSessionOAuth2AuthorizedClientRepository를 유지하며, BFF 요청마다 읽기 전용 사용자 조회 결과로 요청용 인증을 구성합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Security 6.5.11, Gradle 9.5.1, PostgreSQL 17, JUnit 5, AssertJ, Mockito, Testcontainers, embedded Tomcat/JDK HttpClient.

**Spec:** `docs/superpowers/specs/2026-09-09-reference-app-local-login-design.md` — 사용자 승인 완료, 설계 커밋 `c1ec443`.

## Global Constraints

- Task 5만 구현합니다. token refresh·revocation·logout, 관리자 API, SPA와 실제 IdP 브라우저 E2E는 후속 Task 6–10입니다.
- IdP 코드·DB·키를 공유하지 않으며 기존 사용자 identity와 bootstrap 정책을 유지합니다.
- identity issuer는 검증된 ID Token에서 가져옵니다. `(issuer, subject)`를 trim·대소문자 변환·URI 정규화로 합치지 않습니다.
- HTTP 호출과 claim 검증은 DB 트랜잭션 전에 완료합니다. 로그인 한 번에 UserInfo HTTP 요청을 중복 실행하지 않습니다.
- 인가에는 DB의 `APP_USER`·`APP_ADMIN`만 사용하며 scope나 외부 역할로 앱 권한을 얻을 수 없습니다.
- `HttpSessionOAuth2AuthorizedClientRepository`를 토큰 저장소로 유지합니다. OAuth 토큰을 같은 세션의 별도 객체에 중복 저장하지 않습니다.
- 두 API 모두 issuer·subject·DB version·저장 시각·원본 principal·OAuth 토큰·client secret을 응답 DTO에 포함하지 않습니다.
- 두 API와 기존 `/bff/csrf`에는 `Cache-Control: no-store`를 적용합니다.
- 기존 `RP_SESSION`, fixed origin/callback, CSRF·Origin·PKCE·nonce 정책을 유지합니다.
- 기존 `frontend/.idea/`는 수정·stage하지 않습니다. 모든 commit은 해당 작업의 파일만 명시합니다. 원격 push·merge는 포함하지 않습니다.

## 실행 준비와 검증 방법

문서 작성 위치는 `C:/dev/auth-study`입니다. 구현 시작 시 using-git-worktrees 스킬로 별도 작업 공간을 준비하고 실제 경로와 HEAD를 기록합니다. 기존 `.worktrees/oidc-reference-app`의 오래된 브랜치를 그대로 구현 기준으로 사용하지 않습니다. 새 작업 공간은 이 계획과 승인 설계를 포함해야 합니다.

각 단계는 실패 테스트 작성 → 실행과 실패 원인 확인 → 최소 구현 → 통과 → 명시적 파일 commit 순서입니다. 아래 `J`는 `reference-app/backend/src/main/java/com/sweet/referenceapp`, `T`는 `reference-app/backend/src/test/java/com/sweet/referenceapp`의 정확한 경로 별칭입니다. 테스트 명령은 작업 공간의 `reference-app/backend`에서 실행합니다.

```powershell
git status --short --branch
git log -3 --oneline
./gradlew.bat test
```

환경 실패를 RED로 기록하지 않습니다. Docker가 없으면 프로토콜·단위 테스트는 진행할 수 있지만 PostgreSQL 검증을 통과했다고 보고하지 않습니다. 실행한 테스트 XML에서 tests/failures/errors/skipped를 집계하고 실제 명령을 기록합니다. 생성되는 보고서는 테스트가 실행된 이번 작업 공간의 것만 사용합니다.

## 파일 구조와 책임

| 작업 | 생성·수정 파일 | 책임 |
|---|---|---|
| 1 | Create J/security/OidcExternalIdentityMapper.java | raw UserInfo 타입 확인과 허용 필드 변환 |
| 1 | Create T/security/OidcExternalIdentityMapperTest.java | identity·선택 정보·타입 경계 |
| 2 | Create J/user/application/AppLocalLoginService.java, LocalUserDisabledException.java | ACTIVE 검사 포함 상위 트랜잭션 |
| 2 | Create J/user/application/CurrentAppUserService.java | 요청별 읽기 전용 조회 |
| 2 | Modify J/user/domain/AppUserRepository.java, J/user/infrastructure/AppUserJpaRepository.java, AppUserRepositoryAdapter.java | UUID와 역할을 한 조회로 로드 |
| 2 | Create T/user/AppLocalLoginIntegrationTest.java, CurrentAppUserIntegrationTest.java | rollback·조회 검증 |
| 3 | Create J/security/AppOidcUser.java, AppOidcUserService.java, RpSessionCleaner.java | OIDC 확장·principal·공통 정리 |
| 3 | Modify J/security/OAuth2ClientSecurityConfig.java, OidcLoginFailureHandler.java | OIDC 서비스 wiring과 안전한 실패 분기 |
| 3 | Create T/security/AppOidcUserServiceTest.java | delegation·인증 오류·토큰 저장 연결 |
| 3 | Modify T/security/OAuth2ClientConfigurationIntegrationTest.java | 기존 DB 없는 fixture의 서비스 대역 |
| 4 | Create J/security/CurrentAppUserFilter.java, CurrentAppUser.java | 인가 전 현재 사용자·요청 인증 |
| 4 | Modify J/security/OAuth2ClientSecurityConfig.java | AuthorizationFilter 앞 등록 |
| 4 | Create T/security/CurrentAppUserFilterTest.java | 현재 상태·권한·동시 요청 격리 |
| 5 | Create J/user/presentation/SessionController.java, ProfileController.java | 명시적 nested DTO와 API |
| 5 | Create T/security/BffUserApiIntegrationTest.java | 실제 필터와 API 응답 |
| 5 | Modify T/security/OAuth2ClientConfigurationIntegrationTest.java | HTTP fixture에 실제 컨트롤러 import |
| 6 | Create T/security/LocalLoginHttpTestSupport.java, OidcLocalLoginIntegrationTest.java, LocalSessionLifecycleIntegrationTest.java | 실제 DB와 OIDC HTTP 전체 흐름 |
| 6 | Modify T/security/MockOidcIssuer.java | 조작 가능한 UserInfo와 호출 계수 |
| 6 | Modify reference-app/backend/README.md | Task 5 사용·오류·검증 안내 |

## Task 1: 정확한 UserInfo 변환

**Interfaces:** `ExternalIdentityProfile OidcExternalIdentityMapper.map(OidcIdToken token, OidcUserInfo info)`와 `void validateClaims(Map<String,Object> claims)`. Spring 보안 타입은 security 패키지에만 둡니다.

- [ ] **Step 1: 정상·불일치 테스트 작성**

T/security/OidcExternalIdentityMapperTest.java에서 고정 유효 token과 서로 다른 UserInfo를 생성합니다. 테스트 helper `token(String sub)`는 `new OidcIdToken("fixture-id-token", Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-09T01:00:00Z"), Map.of("iss", "http://idp.localhost:8080", "sub", sub))`를 반환합니다. 여기서는 이미 검증된 token 입력의 변환만 테스트합니다.

```java
var mapper = new OidcExternalIdentityMapper();
var profile = mapper.map(token("one"), new OidcUserInfo(Map.of("sub", "one")));
assertThat(profile.subject()).isEqualTo("one");
assertThat(profile.email()).isNull();
assertThat(profile.hrRoles()).isEmpty();
assertThatThrownBy(() -> mapper.map(token("one"), new OidcUserInfo(Map.of("sub", "two"))))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew.bat test --tests '*OidcExternalIdentityMapperTest'`. Expected: mapper 부재 compilation failure.

- [ ] **Step 3: 필드 변환 구현**

mapper는 stateless component입니다. `validateClaims`는 `sub`가 nonblank String인지, 존재하는 `name/email`이 null 또는 String인지 확인합니다. 역할 claim은 null 또는 List이며 각 원소가 nonblank String인지 검사합니다. 중복 문자열은 Set으로 합칩니다. company/organization은 null 또는 String key의 Map으로 변환하고 `ExternalUserSnapshot` 검증을 그대로 적용합니다. 숫자→문자열 coercion, 문자열→역할 목록 변환을 하지 않습니다. 오류 메시지는 입력값을 포함하지 않습니다.

```java
public ExternalIdentityProfile map(OidcIdToken token, OidcUserInfo info) {
    if (token == null || info == null) throw new IllegalArgumentException("Identity information missing");
    validateClaims(info.getClaims());
    if (!Objects.equals(token.getSubject(), info.getClaims().get("sub")))
        throw new IllegalArgumentException("Identity mismatch");
    var claims = info.getClaims();
    var snapshot = snapshot(claims);
    return new ExternalIdentityProfile(URI.create(token.getClaimAsString("iss")), token.getSubject(),
            snapshot.email(), snapshot.displayName(), snapshot.company(), snapshot.organization(), snapshot.hrRoles());
}
```

private `ExternalUserSnapshot snapshot(Map<String,Object> claims)`는 `email`, `name`과 spec의 세 `https://auth-study.local/claims/*`만 선택합니다. `validateClaims`는 기본 문자열 검사 후 이 helper도 호출해 중첩 구조까지 검사합니다. 선택되지 않은 최상위 claim은 무시하고 중첩 허용 필드는 기존 도메인 생성자가 검사합니다.

- [ ] **Step 4: 경계 사례와 GREEN**

같은 test에 null UserInfo, 없는/숫자/공백 sub, 숫자 name/email, 잘못된 중첩 key/type, 역할 문자열·숫자 원소·null 원소·blank 원소를 parameterized case로 추가합니다. null 값을 넣는 fixture는 Map.of 대신 LinkedHashMap을 사용합니다. ID Token에 email/name이 있어도 UserInfo에 없으면 null이며 issuer/subject 표현과 독립 identity를 보존하는지 단언합니다. Run: `./gradlew.bat test --tests '*OidcExternalIdentityMapperTest'`. Expected: PASS.

- [ ] **Step 5: commit**

위 mapper와 test만 stage, `git diff --cached --check` 후 `git commit -m "feat: map validated oidc userinfo to local identity"`.

## Task 2: 로컬 로그인 트랜잭션과 현재 사용자 조회

**Interfaces:** `AppUserView AppLocalLoginService.login(ExternalIdentityProfile profile)`, `Optional<AppUserView> CurrentAppUserService.find(UUID id)`, repository에 `Optional<AppUser> findById(UUID id)` 추가. `LocalUserDisabledException extends RuntimeException`은 고정 메시지의 no-arg constructor만 제공합니다.

- [ ] **Step 1: rollback·조회 실패 테스트**

AppLocalLoginIntegrationTest는 기존 BootstrapIntegrationSupport를 사용합니다. 기존 `AppUserProvisioningService.provision`으로 사용자 생성 후 jdbc로 status를 DISABLED로 바꾸고 row 전체 및 roles/bootstrap을 저장합니다. 새 서비스에 다른 name/email을 포함한 같은 identity를 전달합니다.

```java
assertThatThrownBy(() -> localLogin.login(changedProfile))
        .isExactlyInstanceOf(LocalUserDisabledException.class);
assertThat(jdbc.queryForMap("select * from app_user where id=?", user.id())).isEqualTo(before);
```

`user`, `before`, `changedProfile`은 위 준비 단계에서 만드는 로컬 변수입니다. ACTIVE 일반 사용자·최초 COMPANY_ADMIN·완료 후 다른 후보도 같은 서비스로 호출합니다. CurrentAppUserIntegrationTest는 실제 저장 사용자의 UUID 조회와 없는 UUID의 Optional.empty, 조회 전후 user/role/bootstrap 행 불변을 검사합니다.

- [ ] **Step 2: RED 실행**

Run: `./gradlew.bat test --tests '*AppLocalLoginIntegrationTest' --tests '*CurrentAppUserIntegrationTest'`. Expected: 신규 서비스 부재.

- [ ] **Step 3: 최소 서비스·조회 구현**

AppLocalLoginService는 constructor로 AppLoginProvisioningService만 받고 Spring @Service로 등록합니다.

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public AppUserView login(ExternalIdentityProfile profile) {
    var user = provisioning.provision(profile);
    if (user.status() != AppUserStatus.ACTIVE) throw new LocalUserDisabledException();
    return user;
}
```

현재 조회 서비스는 constructor로 AppUserRepository를 받고 `@Transactional(readOnly=true)`의 `find`에서 `repository.findById(id).map(AppUserView::from)`을 반환합니다. adapter의 기존 MANDATORY 정책 안에서 동작하며 JPA entity가 transaction 밖으로 나오지 않습니다.

```java
@Query("select distinct u from AppUserJpaEntity u left join fetch u.roles where u.id=:id")
Optional<AppUserJpaEntity> findWithRoles(@Param("id") UUID id);
```

위 메서드를 JPA repository에 추가하고 adapter의 findById는 `jpaRepository.findWithRoles(id).map(AppUserJpaEntity::toDomain)`입니다. `@Lock`, refresh, bootstrap 조회를 붙이지 않습니다. entity의 실제 collection 필드 `roles`를 확인하고 fetch join을 사용합니다.

- [ ] **Step 4: GREEN 및 회귀**

Task 2 focused 명령 후 `./gradlew.bat test --tests '*AppAdminBootstrap*' --tests '*AppUserProvisioning*'` 실행. 기존 standalone JIT의 DISABLED snapshot 갱신 계약은 유지되고 새 로그인 wrapper만 rollback하는지 확인합니다.

- [ ] **Step 5: commit**

파일 표의 Task 2 파일만 stage, staged diff 검사 후 `git commit -m "feat: validate active local login transactionally"`.

## Task 3: 표준 OIDC 처리에 로컬 principal 연결

**Interfaces:** `AppOidcUser implements OidcUser`의 constructor `(OidcUser delegate, AppUserView localUser)`, `UUID localUserId()`, `AppUserView localUser()`, `AppOidcUser withLocalUser(AppUserView current)`. `AppOidcUserService implements OAuth2UserService<OidcUserRequest,OidcUser>`의 constructor `(OidcExternalIdentityMapper mapper, AppLocalLoginService login)`과 `loadUser(OidcUserRequest request)`. `RpSessionCleaner(boolean secureCookie).clear(HttpServletRequest, HttpServletResponse)`.

- [ ] **Step 1: 서비스·principal 테스트와 RED**

AppOidcUserServiceTest에서 package-private 추가 constructor `(OAuth2UserService<OidcUserRequest,OidcUser> delegate, OidcExternalIdentityMapper mapper, AppLocalLoginService login)`로 표준 처리의 단위 테스트 대역을 주입합니다. public constructor는 실제 OidcUserService를 구성해야 합니다.

```java
when(delegate.loadUser(request)).thenReturn(oidc);
when(login.login(any())).thenReturn(local);
var result = (AppOidcUser) service.loadUser(request);
assertThat(result.getName()).isEqualTo(oidc.getName());
assertThat(result.localUserId()).isEqualTo(local.id());
verify(delegate, times(1)).loadUser(request);
assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
        .containsExactly("APP_USER");
```

fixture `local`는 ACTIVE APP_USER AppUserView, `oidc`는 유효 ID Token/UserInfo를 가진 DefaultOidcUser, `request`는 같은 token의 OidcUserRequest로 만듭니다. Run: `./gradlew.bat test --tests '*AppOidcUserServiceTest'`. Expected: 클래스 부재.

- [ ] **Step 2: OIDC 위임과 명시적 claim 타입 검사**

Spring 6.5.11의 OidcUserService는 UserInfo sub를 검증하고 provider는 nonce 검증 후 userService를 호출합니다. 다음 구성으로 기본 claim coercion이 숫자 name/email/sub를 문자열로 바꾸기 전에 Task 1 검증을 적용합니다. 전체 JWT/token HTTP 처리는 직접 구현하지 않습니다.

```java
var standard = new OidcUserService();
standard.setClaimTypeConverterFactory(registration -> claims -> {
    mapper.validateClaims(claims);
    return claims;
});
```

delegate.loadUser → mapper.map(request.getIdToken(), oidc.getUserInfo()) → login.login → new AppOidcUser 순서입니다. 비활성 예외는 OAuth2AuthenticationException의 내부 code `local_user_disabled`, 그 밖의 RuntimeException은 내부 `oidc_login_failed`와 고정 메시지로 변환합니다. 외부 프로토콜 오류가 `local_user_disabled`라는 code를 보내도 별도 안내가 되지 않도록 별도 marker exception `LocalUserLoginAuthenticationException`을 AppOidcUserService의 package-private nested class로 정의하고 비활성 로컬 예외에만 사용합니다. 원본 예외 메시지를 공개 response/log에 기록하지 않습니다.

AppOidcUser는 getClaims/getAttributes/getIdToken/getUserInfo/getName을 delegate에 위임하고 getAuthorities는 local.roles를 이름순 SimpleGrantedAuthority로 변환합니다. 객체·권한 컬렉션은 불변이며 withLocalUser는 새 wrapper를 반환합니다. 원본 delegate authority를 합치지 않습니다. toString에는 원본 claims/token을 넣지 않습니다.

- [ ] **Step 3: 실패 정리와 security wiring**

기존 OidcLoginFailureHandler의 세션 정리 부분을 RpSessionCleaner로 추출합니다. cleaner는 SecurityContextHolder.clearContext, getSession(false).invalidate, 동일 RP_SESSION 만료 cookie, no-store만 수행하고 redirect나 새 세션 생성을 하지 않습니다.

failure handler는 marker exception일 때만 `URI.create(properties.spaOrigin() + "/login-error?code=local_user_disabled")`, 나머지는 기존 properties.failureUri()로 redirect합니다. public ReferenceSecurityProperties 계약은 확장하지 않습니다.

```java
.oauth2Login(login -> login
    .userInfoEndpoint(endpoint -> endpoint.oidcUserService(appOidcUserService))
    .authorizedClientRepository(authorizedClients))
```

위 설정은 기존 oauth2Login 체인에 병합합니다. 기존 authorization/callback/failure/success 설정을 삭제하지 않습니다. 신규 mapper/service는 component, 필터/cleaner는 security 설정에서 직접 생성하는 내부 객체로 관리합니다.

- [ ] **Step 4: 기존 HTTP fixture를 정상 구동하고 GREEN**

현재 HttpSecurityTestSupport.Application은 security 패키지만 scan하고 persistence를 제외합니다. test configuration에 Mockito AppLocalLoginService·CurrentAppUserService bean을 추가하고 각 @BeforeEach에서 고정 ACTIVE 사용자 결과로 reset/stub합니다. 실제 새 AppOidcUserService·mapper·보안 chain은 유지합니다. `authorities`가 APP_USER/APP_ADMIN을 포함하지 않는 기존 assertion은 현재 APP_USER만 포함하는 assertion으로 변경합니다. 인증 타입 OAuth2AuthenticationToken과 authorized client 보관, 두 세션 토큰 분리는 그대로 검사합니다.

Run: `./gradlew.bat test --tests '*AppOidcUserServiceTest' --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest' --tests '*ReferenceSecurityPropertiesTest'`. Expected: PASS. malformed userInfo·delegate failure에서 login 미호출, local DB 예외에서 일반 실패, getName/registrationId/authorized client 연결 유지도 검사합니다.

- [ ] **Step 5: commit**

파일 표 Task 3만 stage, staged diff 검사 후 `git commit -m "feat: connect oidc authentication to local users"`.

## Task 4: 요청별 현재 상태·권한 적용

**Interfaces:** `CurrentAppUserFilter(CurrentAppUserService users, RpSessionCleaner cleaner) extends OncePerRequestFilter`; `CurrentAppUser`는 요청 속성을 다루는 final utility로 `static void set(HttpServletRequest request, AppUserView user)`, `static Optional<AppUserView> find(HttpServletRequest request)`를 제공합니다. key는 `CurrentAppUser.class.getName()`입니다.

- [ ] **Step 1: 필터 실패 테스트와 RED**

MockHttpServletRequest/Response, 실제 OAuth2AuthenticationToken/AppOidcUser, Mockito 조회 서비스를 사용합니다. filterChain 대역은 doFilter 안에서 SecurityContextHolder와 CurrentAppUser.find를 캡처합니다. 매 테스트 뒤 holder를 clear합니다.

```java
when(users.find(local.id())).thenReturn(Optional.of(promoted));
filter.doFilter(request, response, chain);
verify(users, times(1)).find(local.id());
assertThat(capturedAuthentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
        .contains("APP_ADMIN");
assertThat(original.getAuthorities()).extracting(GrantedAuthority::getAuthority)
        .doesNotContain("APP_ADMIN");
```

`promoted`는 같은 local UUID의 APP_USER+APP_ADMIN view, `original`은 요청 전 세션 인증입니다. Run: `./gradlew.bat test --tests '*CurrentAppUserFilterTest'`. Expected: 클래스 부재.

- [ ] **Step 2: 경로·상태·권한 구현**

shouldNotFilter는 servletPath가 `/bff/`로 시작하지 않거나 ERROR/ASYNC 재dispatch인 경우 true입니다. 인증이 없거나 AnonymousAuthenticationToken이면 DB 조회 없이 chain으로 전달합니다. 인증은 있지만 OAuth2AuthenticationToken/AppOidcUser 형태가 아니면 cleaner로 정리하고 익명 chain으로 전달합니다.

정상 wrapper면 users.find(localUserId)를 한 번 호출합니다. empty/DISABLED는 clear 후 chain, ACTIVE는 새 요청 인증을 구성합니다. principal issuer/subject와 DB identity도 일치하는지 확인하고 불일치는 정리합니다.

```java
var principal = previousPrincipal.withLocalUser(current);
var next = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), previous.getAuthorizedClientRegistrationId());
next.setDetails(previous.getDetails());
var context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(next);
SecurityContextHolder.setContext(context);
CurrentAppUser.set(request, current);
chain.doFilter(request, response);
```

shared session의 context나 authentication을 수정하거나 새로운 요청 인증을 session repository에 save하지 않습니다. 외부 SecurityContextHolderFilter의 종료 정리를 사용합니다. 인가 시 holder가 empty면 Spring AuthorizationFilter가 인증 부재를 401로 처리할 수 있도록 기존 entry point를 유지합니다.

조회의 DataAccessException은 no-store HTTP 503과 빈 본문으로 종료하며 chain을 호출하지 않습니다. 이 경우 사용자 상태로 간주해 세션을 폐기하지 않습니다. 예외를 잡는 범위는 조회 호출에 한정하고 downstream controller 예외를 삼키지 않습니다.

```java
.addFilterBefore(new CurrentAppUserFilter(currentUsers, cleaner), AuthorizationFilter.class)
```

Security 설정에서 직접 생성하여 servlet auto-registration으로 두 번 실행되지 않게 합니다. CSRF/Origin보다 뒤, 인가보다 앞입니다.

- [ ] **Step 3: 상태·동시 요청 테스트와 GREEN**

empty/DISABLED/legacy 인증은 session invalid 및 cookie 만료, local 조회는 legacy에서 0회, 원래 익명은 세션 생성·조회 0회로 확인합니다. 조회 장애는 503과 chain 미호출·세션 유지입니다. 같은 원본 인증을 공유하는 두 요청에 다른 조회 결과를 돌려주고 서로의 권한·원본 인증이 변하지 않는지 확인합니다. Task 6에서 실제 HTTP 권한 부여·회수도 검증합니다.

Run: `./gradlew.bat test --tests '*CurrentAppUserFilterTest' --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest'`. Expected: PASS.

- [ ] **Step 4: commit**

Task 4 파일만 stage, staged diff 검사 후 `git commit -m "feat: enforce current local user state on bff requests"`.

## Task 5: Session/Profile API

**Interfaces:** public controller 클래스의 GET handler는 ResponseEntity<?>를 반환할 수 있지만 본문은 아래 nested record로 제한합니다. 사용자 입력 identity는 받지 않습니다. 조회 결과는 CurrentAppUser.find(request)에서만 얻습니다.

- [ ] **Step 1: 실제 HTTP API 실패 테스트와 RED**

BffUserApiIntegrationTest는 기존 HttpSecurityTestSupport를 상속하고 Application에 두 실제 controller를 import합니다. DB 대역은 Task 3에서 만든 것을 사용합니다. 익명 session과 로그인 후 session/profile의 status·key·CSRF를 확인합니다.

```java
var anonymous = send("GET", "/bff/session", null, "");
assertThat(anonymous.statusCode()).isEqualTo(200);
assertThat(JSON.readTree(anonymous.body())).isEqualTo(JSON.readTree("{\"authenticated\":false}"));
assertThat(anonymous.headers().allValues("Set-Cookie")).isEmpty();
```

Run: `./gradlew.bat test --tests '*BffUserApiIntegrationTest'`. Expected: API 부재에 따른 404/assertion failure.

- [ ] **Step 2: DTO와 handler 구현**

```java
record AnonymousSession(boolean authenticated) { }
record SessionUser(UUID id, String displayName, String email, AppUserStatus status, List<String> roles) { }
record AuthenticatedSession(boolean authenticated, SessionUser user, String csrfHeaderName, String csrfToken) { }
record ProfileResponse(String displayName, String email, Map<String,Object> company,
        Map<String,Object> organization, List<String> hrRoles, List<String> roles) { }
```

SessionController에 앞의 세 record, ProfileController에 마지막 record를 둡니다. roles/hrRoles는 `stream().map(...).sorted().toList()`이며 Local roles는 enum.name을 사용합니다. Optional snapshot 필드는 null을 유지합니다.

session handler는 CurrentAppUser.find가 empty면 no-store 200 AnonymousSession(false)로 즉시 반환합니다. 이 분기에서는 CsrfToken argument resolver로 token을 강제 로드하지 않습니다. 인증된 분기에서만 `(CsrfToken) request.getAttribute(CsrfToken.class.getName())`를 얻어 headerName/token을 응답에 넣습니다. csrf attribute가 없으면 성공 응답을 조작하지 않고 서버 오류로 처리합니다.

profile handler는 CurrentAppUser.find가 empty면 no-store 401, 있으면 snapshot과 현재 roles로 ProfileResponse를 반환합니다. 추가 user service 호출과 원본 Authentication 직렬화는 하지 않습니다.

- [ ] **Step 3: 계약·보안 GREEN**

인증 session의 정확한 top-level/user field, profile의 정확한 6개 field를 집합 비교합니다. null 선택 정보와 빈 roles snapshot, no-store, `/bff/csrf` 유지도 확인합니다. 로그인 후 session API에서 받은 실제 masked csrfToken으로 기존 POST `/bff/test`를 호출하여 허용 Origin과 함께 통과하는지, 로그인 전 csrfToken은 거절되는지 확인합니다. OAuth token fixture 실제 값과 secret이 body/header/redirect에 없는지 검사합니다.

Run: `./gradlew.bat test --tests '*BffUserApiIntegrationTest' --tests '*BffSessionSecurityIntegrationTest'`. Expected: PASS.

- [ ] **Step 4: commit**

Task 5 파일만 stage, staged diff 검사 후 `git commit -m "feat: expose local session and profile api"`.

## Task 6: 실제 OIDC·PostgreSQL 연동과 회귀

**Interfaces:** LocalLoginHttpTestSupport는 실제 ReferenceApplication, PostgresContainerConfiguration, mock issuer를 사용하는 HTTP fixture입니다. 기존 DB 없는 fixture를 상속하지 않습니다. helper 계약은 기존과 같은 `Pending begin(String cookie)`, `HttpResponse<String> callback(Pending pending,String query)`, `HttpResponse<String> send(String method,String path,String cookie,String body,String... headers)`, `String cookie(HttpResponse<?> response)`입니다. Pending record는 `(String cookie, String state, Map<String,String> parameters)`입니다.

- [ ] **Step 1: issuer·통합 fixture 준비**

MockOidcIssuer에 volatile `Map<String,Object> userInfoClaims`, volatile `int userInfoStatus`, AtomicInteger userInfoRequests를 추가합니다. `/userinfo` handler는 counter를 증가시키고 현재 status/claims를 반환합니다. reset은 기존 유효 UserInfo와 200, counter 0을 복원합니다. 새 helper `int userInfoRequestCount()`를 제공합니다.

```java
server.createContext("/userinfo", exchange -> {
    userInfoRequests.incrementAndGet();
    respond(exchange, userInfoStatus, userInfoClaims);
});
```

새 support는 `@SpringBootTest(classes=ReferenceApplication.class, webEnvironment=DEFINED_PORT)`, `@Import({PostgresContainerConfiguration.class, ProbeConfiguration.class})`, `@ActiveProfiles("test")`로 구성합니다. reserved loopback port를 server.port/bff-origin/redirect에 동일 적용합니다. 기존 HTTP fixture의 begin/callback/send/cookie 구현을 이 support로 복사하되 shared static issuer 상태는 공유하지 않습니다. 테스트 context 종료 시 issuer.close를 호출합니다.

DynamicPropertySource에 discovery issuer와 registration client-id/secret/basic/scopes/redirect를 등록합니다. application-test.yaml의 고정 authorization-uri/token-uri/jwk-set-uri/user-info-uri도 새 issuer endpoint로 모두 override하여 `http://idp.test`로 잘못 향하지 않게 합니다. 실제 security configuration·application services·JPA를 사용하며 production test switch는 추가하지 않습니다.

@BeforeEach에서 해당 Testcontainer DB만 초기화합니다. 기존 BootstrapIntegrationSupport처럼 한 트랜잭션에서 `delete from app_bootstrap_state`, `delete from app_user`, `insert into app_bootstrap_state(singleton_key) values (1)`을 순서대로 실행합니다. role은 FK cascade로 정리됩니다. 로컬 5432/55433 DB를 건드리지 않습니다. 테스트 클래스 병렬 실행은 활성화하지 않습니다.

- [ ] **Step 2: 실제 callback 성공·실패 테스트**

OidcLocalLoginIntegrationTest에서 정상 로그인, 재로그인, 최초 관리자, UserInfo 누락/mismatch/type 오류, disabled rollback, DB 저장 오류를 실행합니다. helper는 위 support 계약을 사용하고 issuer nonce는 begin에서 요청 nonce로 설정합니다.

```java
var pending = begin(null);
var result = callback(pending, "code=valid-code&state=" + pending.state());
assertThat(result.statusCode()).isEqualTo(302);
assertThat(ISSUER.userInfoRequestCount()).isEqualTo(1);
var session = send("GET", "/bff/session", cookie(result), "");
assertThat(JSON.readTree(session.body()).path("authenticated").asBoolean()).isTrue();
assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
```

disabled fixture는 기존 JIT로 만든 후 SQL로 상태를 바꾸고 전체 row/roles/bootstrap을 보관합니다. callback 결과는 local_user_disabled, 보관한 값은 불변, 이전 cookie는 profile 401이어야 합니다. storage 오류는 전용 test DB에 `BEFORE INSERT ON app_user` trigger를 설치해 고정 예외를 발생시키고 finally에서 trigger/function을 제거합니다. callback 요청은 별도 thread/transaction이므로 rollback용 outer test transaction에 trigger를 숨기지 않습니다. trigger를 commit한 뒤 요청하고 독립 query로 사용자 0건/bootstrap 미완료를 확인합니다.

실패 응답의 Set-Cookie에는 RP_SESSION 삭제만 있으며 새 세션 발급이 없고 old cookie로 API에 접근할 수 없음을 단언합니다. 프로토콜 오류의 remote code를 local_user_disabled로 설정해도 일반 오류인지 검증합니다.

- [ ] **Step 3: 실제 세션 수명·권한 변경 테스트**

LocalSessionLifecycleIntegrationTest에서 실제 로그인 후 UUID를 session 응답으로 얻고 별도 커밋된 SQL로 status/roles를 변경합니다. 일반 사용자로 삭제 테스트를 수행해 bootstrap FK 삭제 제약과 혼동하지 않습니다. role 부여·회수는 다음 profile의 roles와 test-only `/bff/test-admin` 접근 200/403 양쪽으로 검사합니다.

test-only ProbeConfiguration은 @EnableMethodSecurity와 별도 imported controller를 제공하고 다음 endpoint를 정의합니다. production 관리 API는 만들지 않습니다.

```java
@GetMapping("/bff/test-admin")
@PreAuthorize("hasAuthority('APP_ADMIN')")
Map<String,Boolean> admin() { return Map.of("allowed", true); }
```

DB 변경 전후 UserInfo counter와 user.last_login_at이 늘지 않는지 확인합니다. 삭제·disabled 뒤 session은 익명 200, profile은 401, 삭제 cookie가 반환되는지 확인합니다. 실제 authorized client를 읽는 test-only probe는 boolean만 반환하고 두 독립 로그인 세션의 토큰이 섞이지 않는지도 기존 Task 4 검증과 함께 유지합니다.

- [ ] **Step 4: 통합 실행과 테스트 민감도**

Run: `./gradlew.bat test --tests '*OidcLocalLoginIntegrationTest' --tests '*LocalSessionLifecycleIntegrationTest'`. Expected: PASS. 이미 구현된 동작은 첫 실행부터 통과할 수 있으므로 가짜 RED를 기록하지 않습니다. 새 테스트가 결함을 발견하면 해당 실패가 RED이며 최소 수정 후 재실행합니다.

요청 필터를 임시로 DB roles 대신 principal의 과거 roles를 쓰게 수정하면 role 회수 테스트가 실패해야 합니다. AppLocalLoginService의 ACTIVE 검사 제거 시 disabled 테스트가 실패해야 합니다. 각각 focused 실행 후 apply_patch로 즉시 원복하고 다시 PASS를 확인합니다. 환경·컴파일 실패는 mutation 증거로 인정하지 않습니다. 사용자 변경을 git restore/reset으로 지우지 않습니다.

- [ ] **Step 5: README와 전체 검증**

README의 Task 4 중간 상태 설명을 Task 5 결과로 갱신하고 session/profile JSON, 두 오류 code, 요청별 DB 확인, client 등록 선행 조건, 토큰·logout 미구현 범위, 테스트 명령을 기록합니다. 현재 실제 실행 결과만 기재합니다.

```powershell
./gradlew.bat clean test
```

전체 XML의 실패/오류/skip과 테스트 수를 집계합니다. worktree root에서 다음을 실행합니다.

```powershell
git diff --check
rg -n 'com\.sweet\.authstudy|jdbc:postgresql://localhost:5432' reference-app/backend/src/main
rg -n 'org\.springframework|jakarta\.persistence' reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain
git status --short
```

검색은 no matches, diff 검사는 성공이어야 합니다. spec의 12개 검증 항목이 아래 매핑대로 충족되는지 검토합니다. 파일 표 밖 변경이 있으면 필요한 이유와 실제 diff를 검토하고 관련 없는 변경은 추가하지 않습니다.

- [ ] **Step 6: commit과 인수 보고**

Task 6 파일과 검증 중 필요한 최소 수정 파일만 명시적으로 stage합니다. `git diff --cached --check` 후 `git commit -m "test: verify local oidc login and session lifecycle"`. 보고에는 커밋, focused/전체 실제 테스트 수, mutation 증거, 미검증 실제 IdP E2E, 작업 공간 경로와 남은 사용자 변경을 포함합니다. 승인 설계와 별개로 원격 push·merge를 실행하지 않습니다.

## 설계 검증 항목 매핑과 자체 검토

| Spec 검증 번호 | 구현·검증 작업 |
|---|---|
| 1–2 정상/JIT/bootstrap | Tasks 2, 3, 6 |
| 3 UserInfo 선행 거절 | Tasks 1, 3, 6 |
| 4–5 비활성·저장 rollback | Tasks 2, 3, 6 |
| 6 실패 세션 정리 | Tasks 3, 6 |
| 7–9 현재 상태·권한·legacy | Tasks 4, 6 |
| 10 요청별 읽기·장애 차단 | Tasks 2, 4, 6 |
| 11 API·토큰 미노출 | Tasks 5, 6 |
| 12 CSRF·전체 회귀 | Tasks 3, 5, 6 |

계획 작성 중 확인한 현재 fixture는 security 패키지만 scan하며 DB가 없습니다. Task 3–5의 서비스 대역과 Task 6의 실제 PostgreSQL suite를 명확히 구분했습니다. 표준 authority 이름과 로컬 authority를 합치지 않으며, 인가는 `hasAuthority('APP_ADMIN')`로 통일합니다. role prefix를 암묵적으로 추가하는 hasRole은 사용하지 않습니다.

Spring 확장 지점은 [6.5.11 OIDC provider 소스](https://github.com/spring-projects/spring-security/blob/6.5.11/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcAuthorizationCodeAuthenticationProvider.java)와 [6.5.11 OidcUserService 소스](https://github.com/spring-projects/spring-security/blob/6.5.11/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/userinfo/OidcUserService.java)를 확인했습니다. UserInfo 처리 전후 hook와 claim converter를 사용하되 기존 token·nonce 검증을 대체하지 않습니다.
