# Reference App Task 4: OIDC 로그인 설정과 세션 보안 설계

작성일: 2026-09-08

상태: 공개 주소를 설정값으로 고정하는 방향은 대화에서 승인되었습니다. 아래 상세 계약은 문서 검토 후 확정하며, 구현 계획과 코드는 아직 작성하지 않았습니다.

## 목적과 범위

독립 Reference App BFF에 OIDC Authorization Code 로그인, PKCE S256, 콜백 검증과 서버 세션 보안을 추가합니다. 기반 커밋은 `9a590bc`입니다. 기존 Task 1–3에는 독립 백엔드, JIT 사용자 저장과 최초 관리자 bootstrap이 구현되어 있습니다.

상위 문서는 [Reference App 구현 계획의 Task 4](../plans/2026-08-21-oauth-oidc-reference-app.md)와 [OIDC 전체 설계](2026-08-21-oauth-oidc-idp-design.md)입니다. 이 문서는 상위 계획의 동적 `{baseUrl}`, 모호한 CSRF 전달 방식과 Task 5 연결 경계를 구체화합니다.

이번 범위는 프로토콜 로그인과 세션 보안 기반입니다. `AppLoginProvisioningService` 호출, 외부 claim의 로컬 사용자 변환, DISABLED 접근 거절, 로컬 principal, `/bff/session`·`/bff/profile` 구현은 Task 5입니다. 토큰 갱신·로그아웃은 Task 6, 관리자 API는 Task 7, SPA와 실제 IdP 브라우저 E2E는 Tasks 8–10입니다. 기존 DB 스키마와 사용자 도메인은 변경하지 않습니다.

## 접근 선택과 주소 계약

요청 Host/Forwarded에서 공개 주소를 계산하는 방식과 설정값으로 고정하는 방식을 비교했습니다. 후자는 프록시별 동적 주소 처리가 필요 없고 redirect 대상과 Origin 검사가 명확하므로 채택합니다.

| 설정 | 개발 값과 용도 |
|---|---|
| issuer | `http://idp.localhost:8080`: Discovery와 ID Token issuer 검증 |
| BFF 공개 origin | `http://rp.localhost:8180`: 직접 로그인 진입 및 콜백 authority |
| SPA origin | `http://rp.localhost:3100`: 상태 변경 Origin 허용값과 로그인 후 복귀 주소 |
| registration ID | `reference-app` |
| callback | `http://rp.localhost:8180/login/oauth2/code/reference-app` |
| 성공 복귀 | `http://rp.localhost:3100/` |
| 실패 복귀 | `http://rp.localhost:3100/login-error?code=oidc_login_failed` |

origin은 scheme/host/port만 허용하며 user-info, 경로, query, fragment와 wildcard를 허용하지 않습니다. callback은 고정 BFF origin과 고정 callback 경로로 구성하며 `{baseUrl}`을 사용하지 않습니다. 성공·실패 복귀 주소도 SPA origin에서 구성하며 요청의 redirect/returnTo 파라미터나 saved request로 덮어쓰지 않습니다.

로컬 직접 접속 구조에서는 forwarded header를 신뢰하지 않도록 Reference App의 `server.forward-headers-strategy`를 `none`으로 변경합니다. IdP 설정에는 영향을 주지 않습니다. 콜백의 실제 scheme/host/port/path가 고정 callback과 일치하는지 검증하며, 임의 Forwarded/X-Forwarded-* 헤더가 이를 우회하거나 redirect 주소를 바꾸지 못하게 합니다. 향후 reverse proxy 배포는 별도 신뢰 프록시 설계가 필요합니다.

## OAuth2 Client와 로그인 흐름

Spring Security OAuth2 Client의 표준 authorization request resolver, code 교환, OIDC ID Token 검증을 사용합니다. JWT 파싱·서명 검증·token HTTP client를 별도로 재구현하지 않습니다. 프로젝트가 실제 사용하는 Spring Security 6.5 계열 API와 검증 동작을 구현 시 확인합니다.

1. 브라우저가 GET `/bff/login`으로 이동합니다. BFF는 고정 BFF origin의 `/oauth2/authorization/reference-app`으로 redirect합니다. 향후 Vite가 `/bff/**`만 proxy해도 이 흐름이 동작합니다.
2. issuer Discovery로 얻은 authorization endpoint를 사용합니다. 설정은 confidential client와 `client_secret_basic`, `authorization_code`를 사용합니다.
3. 요청 scope는 `openid profile email hr.company hr.organization hr.roles`입니다. 무작위 state, nonce와 PKCE verifier를 세션에 보관하고 S256 challenge만 브라우저에 전달합니다. confidential client에서도 PKCE를 명시적으로 적용합니다.
4. GET callback에서 주소와 state를 code 교환 전에 검증합니다. 세션의 pending 요청과 state를 constant-time 비교하며, 누락·불일치·중복 파라미터를 거절합니다. 세션에는 한 번에 하나의 pending 로그인 요청만 유지하며 새 시도는 이전 시도를 대체합니다.
5. code와 verifier를 서버에서 token endpoint로 보내고 ID Token의 서명, issuer, audience, 만료, 필수 시간 claim 및 nonce를 검증합니다. `exp`와 `iat`는 필수이고 시간 오차 허용은 60초입니다. `exp`가 현재보다 60초 넘게 과거이거나 `iat`가 현재보다 60초 넘게 미래이면 거절합니다. 요청에 nonce가 있으므로 응답 nonce 누락·불일치는 실패입니다. 표준 검증기의 누락 부분만 최소한으로 보완합니다. 이번 요청에는 `max_age`를 보내지 않으므로 `auth_time`을 별도로 필수화하지 않습니다.
6. 프로토콜 인증 성공 시 세션 ID를 교체하고 고정 성공 주소로 redirect합니다. 토큰이나 principal을 HTTP 응답으로 직렬화하지 않습니다.

개발 client ID/secret은 상위 계획과 일치하는 로컬 기본값과 환경 변수 override를 제공합니다. 이는 IdP에 실제 client를 생성하는 동작을 포함하지 않습니다. 실제 로그인에는 동일한 client 인증 방식, callback과 scope를 가진 IdP 등록이 필요합니다. Task 4의 자동 테스트는 별도 mock issuer를 사용합니다.

## 서버 세션과 Task 5 연결 경계

`RP_SESSION`은 host-only, HttpOnly, SameSite=Lax, Path=/ 쿠키입니다. 유휴 만료는 30분이며 세션 식별자를 URL로 전달하는 rewriting은 비활성화합니다. Secure는 기본 true로 두고 로컬 `dev`와 HTTP 테스트 프로필에서만 false로 설정합니다. 서버 재시작 시 세션 복원을 하지 않습니다.

SecurityContext, pending authorization request, authorized client와 OAuth token은 해당 HttpSession의 메모리에만 보관합니다. 세션 외부의 사용자별 token 저장소나 JDBC 저장소를 사용하지 않습니다. 로그인 성공 때 이전 CSRF token을 폐기하고 새 세션에서 재발급받도록 합니다.

Task 4에서 생성되는 인증은 Spring의 OIDC 프로토콜 principal입니다. 이것을 로컬 사용자 인증 완료로 표현하지 않으며, 외부 HR 역할이나 scope를 `APP_USER`/`APP_ADMIN`으로 매핑하지 않습니다. 로컬 사용자 API와 관리자 API는 이번에 만들지 않습니다. 성공 redirect의 목적지는 향후 SPA이며, 현재 체크아웃에서 완성된 앱 로그인을 의미하지 않습니다.

Task 5는 검증된 UserInfo와 ID Token identity가 일치한 뒤 기존 `AppLoginProvisioningService`를 호출하고 로컬 principal을 수립합니다. HTTP 조회와 token 검증은 해당 DB 트랜잭션 전에 완료합니다. Spring 기본 OIDC 처리가 수행하는 UserInfo 조회는 Task 4에서도 허용하되, 로컬 snapshot 변환·저장과 앱 사용자 상태 판정은 Task 5 책임입니다. Task 5 구현 시 기본 조회와 사용자 정의 조회가 불필요하게 중복되지 않도록 연결합니다.

## 경로와 CSRF 계약

| 경로 | Task 4 동작 |
|---|---|
| GET `/bff/login` | 익명 허용, 고정 OIDC 로그인 진입 주소로 redirect |
| GET `/bff/csrf` | 익명 허용, 세션 CSRF token과 헤더 이름만 반환 |
| GET `/oauth2/authorization/reference-app` | Spring OAuth2 로그인 시작 |
| GET `/login/oauth2/code/reference-app` | 주소·state·OIDC 검증을 거치는 callback |
| GET `/bff/session` | 익명 허용 규칙만 예약, 응답 controller는 Task 5 |
| 그 외 `/bff/**` | 인증 필요, 익명 요청은 API용 401로 응답 |
| 그 외 경로 | 기본 거절; 필요한 내부 ERROR dispatch만 예외 처리 |

기본 form login, HTTP Basic과 기본 logout endpoint는 활성화하지 않습니다. API 요청을 자동으로 IdP 로그인 화면으로 보내지 않습니다. CSRF 또는 Origin 검사 실패는 403이며, 거절된 unsafe 요청은 인증 오류보다 먼저 403으로 끝날 수 있습니다.

CSRF는 `HttpSessionCsrfTokenRepository`를 사용합니다. GET `/bff/csrf`는 `{csrfHeaderName: "X-CSRF-TOKEN", csrfToken: "..."}`만 반환하고 `Cache-Control: no-store`를 적용합니다. 응답의 token은 Spring의 요청 속성에서 얻은 마스킹된 값이며, 클라이언트는 그 값을 그대로 헤더에 보냅니다. 쿠키 기반 double-submit, HTML meta token, query/form token 전달은 사용하지 않습니다. 헤더만 받아들이면서 Spring의 XOR token 복원 동작은 유지합니다.

GET/HEAD/OPTIONS/TRACE 이외의 `/bff/**` 요청은 유효한 CSRF 헤더와 정확히 일치하는 SPA Origin을 모두 요구합니다. Origin 누락·`null`·다른 scheme/host/port·복수 값은 거절하며, Referer나 forwarded header로 대체하지 않습니다. TRACE는 별도로 거절하고 어떤 상태 변경도 safe method로 구현하지 않습니다. Task 4에서 mutation controller를 임시로 배포하지 않고 테스트 전용 endpoint로 정책을 검증합니다.

브라우저는 향후 SPA의 `/bff/**` proxy를 통해 호출하므로 credentialed cross-origin CORS는 열지 않습니다. 외부 origin은 CSRF 응답을 읽을 수 없고, token을 알아도 허용 Origin 검사를 통과하지 못합니다. callback은 IdP에서 오는 top-level GET이므로 SPA Origin 검사 대상이 아니며 주소·state·nonce로 검증합니다.

## 실패 처리와 응답 보안

콜백 주소·state·token 검증 실패 및 IdP의 인증 거절은 동일한 정리 경로로 처리합니다. SecurityContext를 비우고 기존 HttpSession을 invalidate하며 RP_SESSION 쿠키를 동일 Path/Domain 정책으로 만료시킵니다. 세션 폐기 후 오류를 저장하기 위해 새 세션을 만들지 않습니다. OAuth token과 verifier를 브라우저 응답·오류 메시지·로그에 포함하지 않습니다. state와 nonce는 프로토콜 authorization redirect에만 전달하며 API·오류 응답이나 로그로 재노출하지 않습니다.

실패 응답은 고정 SPA 오류 주소로 redirect하며 외부 `error_description`을 그대로 전달하지 않습니다. token 교환 전 실패는 token endpoint 호출이 없어야 합니다. 교환 이후 실패한 token의 원격 revocation은 Task 6 범위이며 Task 4에서는 로컬 보관을 제거합니다.

`Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`와 CSP `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`을 BFF 응답에 적용합니다. BFF는 이번에 HTML form이나 script를 제공하지 않습니다. CSRF 응답과 로그인 관련 응답은 캐시되지 않게 하고, 응답에는 OAuth token·client secret·전체 인증 객체를 내보내지 않습니다.

## 구성 요소의 책임

| 구성 요소 | 책임 |
|---|---|
| `ReferenceSecurityProperties` | 고정 공개 주소와 registration 계약 검증 |
| `OAuth2ClientSecurityConfig` | 경로 정책, OIDC, 세션 저장소, CSRF와 보안 헤더 연결 |
| `PkceAuthorizationRequestResolver` | 표준 resolver에 confidential client PKCE S256 적용 |
| 콜백 검증 구성 요소 | callback 주소와 pending state 검증, 표준 OIDC 흐름으로 전달 |
| 로그인 성공·실패 handler | 고정 redirect와 실패 시 세션·쿠키 정리 |
| BFF Origin guard | unsafe BFF 요청의 단일 exact Origin 확인 |
| CSRF controller·요청 handler | 최소 token 응답과 헤더 전용 token 해석 |
| 세션 설정 | 쿠키, timeout, cookie-only tracking과 메모리 수명 |

세션 설정은 가능한 한 Spring Boot 기본 property로 표현하며 값만 전달하는 중복 wrapper는 만들지 않습니다. IdP 코드·entity·repository를 import하지 않습니다. 파일명과 bean wiring은 구현 계획에서 확정합니다.

## 검증 기준

검증은 mock issuer의 Discovery/JWKS/token/UserInfo HTTP 응답과 실제 Spring Security 필터 체인을 사용합니다. `oauth2Login()` 테스트 주입만으로 callback 검증이 통과했다고 판단하지 않습니다. cookie와 session fixation은 내장 서버를 실행한 실제 HTTP 테스트로 확인합니다.

1. `/bff/login`과 authorization redirect가 고정 주소를 사용하며 hostile Host/Forwarded 헤더·returnTo 입력으로 바뀌지 않습니다.
2. Discovery에서 받은 endpoint로 이동하며 response_type, scope, state, nonce, exact redirect URI와 PKCE S256이 올바릅니다. code 교환의 verifier가 challenge와 일치하고 client 인증은 Basic입니다.
3. 유효한 signed ID Token과 UserInfo로 callback이 성공하고 세션 ID가 변경됩니다. OAuth token은 응답 body/header/redirect에 없으며 새 세션에만 보관됩니다.
4. state 누락·불일치·중복, session 없는 callback, 잘못된 callback authority는 token 교환 전에 실패합니다. 실패 후 pending 정보와 세션은 남지 않습니다.
5. nonce 누락·불일치, issuer/audience 불일치, 잘못된 서명·미확인 kid, 만료 token·허용 범위 밖의 미래 iat를 거절합니다. 실패 테스트는 유효한 기준 token에서 해당 조건만 바꿉니다.
6. callback 재사용은 성공하지 못하며 IdP error 응답과 token endpoint 실패도 정리됩니다.
7. 실제 Set-Cookie의 이름·Path·HttpOnly·SameSite·Secure·Domain 부재, 30분 timeout 설정, URL rewriting 비활성화를 검증합니다.
8. 실제 CSRF endpoint에서 받은 token으로 테스트 전용 mutation을 호출합니다. 허용 Origin+유효 token만 통과하고, token 누락·다른 세션 token·query/form 전송·로그인 전 token과 잘못된 Origin은 거절됩니다.
9. CSRF 응답은 필요한 두 필드만 포함하며 no-store이고, 임의 origin에 CORS 읽기 권한을 주지 않습니다. 익명 보호 API는 401이며 form/Basic/login page로 우회되지 않습니다.
10. 기존 JIT/bootstrap 테스트는 실제 로컬 IdP 없이 실행됩니다. 일반 test 프로필에는 테스트용 고정 registration metadata를 두고, Discovery/callback suite는 mock issuer로 실제 HTTP 연동을 별도로 검증합니다. 운영 코드에 테스트용 보안 우회는 추가하지 않습니다.
11. Reference App backend 전체 회귀 테스트와 `git diff --check`를 실행합니다. 실제 IdP 브라우저 E2E를 실행하지 않았다면 해당 범위는 미검증으로 보고합니다.

## 공식 문서 확인

- [Spring Security 6.5 OAuth2 Login 설정](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/login/advanced.html): 표준 로그인 확장 지점.
- [Spring Security 6.5 Authorization Grant 지원](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/client/authorization-grants.html): authorization request resolver와 PKCE.
- [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html): 세션 저장, 요청 속성의 마스킹된 token과 헤더 해석. 실제 동작은 프로젝트의 resolved dependency 기준으로 검증합니다.

## 다음 단계

문서 검토에서 상세 계약을 승인받은 뒤 Task 4 구현 계획을 작성합니다. 이후 격리된 작업 공간에서 실패 테스트, 최소 구현, 회귀 검증 순서로 진행합니다. 이번 설계 문서 커밋은 애플리케이션 구현 완료를 의미하지 않습니다.
