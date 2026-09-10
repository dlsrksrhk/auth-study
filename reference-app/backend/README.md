# Reference App BFF

독립 Spring Boot BFF의 Task 7 구현입니다. OIDC Authorization Code 로그인과 로컬 사용자 JIT·최초 관리자 지정, 현재 DB 상태와 권한을 반영하는 API, 요청 시 토큰 갱신 및 앱·IdP 로그아웃을 제공합니다. confidential client의 PKCE S256, 콜백 검증과 서버 세션 보안도 유지합니다. Java 21과 Docker Desktop이 필요합니다.

## 로컬 실행

저장소 루트에서 두 데이터베이스를 시작합니다.

```powershell
docker compose -f infrastructure/docker-compose.yml up -d postgres reference-postgres
docker compose -f infrastructure/docker-compose.yml ps
```

두 서비스가 healthy 상태가 되면 별도 PowerShell 창에서 IdP를 실행합니다.

```powershell
cd backend
.\gradlew.bat bootRun
```

Java와 브라우저가 `idp.localhost`, `rp.localhost`를 로컬 컴퓨터로 해석할 수 있어야 합니다. 해석되지 않으면 운영체제의 hosts 설정에 두 호스트를 `127.0.0.1`로 등록하세요. IdP Discovery 주소는 `http://idp.localhost:8080/.well-known/openid-configuration`입니다. BFF 시작 시 Discovery에 연결하므로 IdP를 먼저 실행합니다.

IdP에는 활성 사용자와 아래 조건의 활성 OAuth client가 필요합니다. BFF 시작은 IdP client를 자동 등록하지 않습니다. 기존 IdP 관리자 기능의 `POST /api/v1/admin/companies/{companyCode}/oauth-clients`로 confidential client를 등록하고, 발급된 client ID와 일회성 secret을 BFF 환경 변수에 설정합니다. 관리자 인증과 해당 회사 관리 권한이 필요합니다.

| 항목 | 등록 조건 |
| --- | --- |
| 공개 client 여부 | `publicClient: false` |
| 인증 / grant | `client_secret_basic` / `authorization_code` |
| Redirect URI | `http://rp.localhost:8180/login/oauth2/code/reference-app` |
| Post-logout redirect URI | `http://rp.localhost:3100/logged-out` |
| Scope | `openid profile email hr.company hr.organization hr.roles` |
| PKCE | S256 |

새 PowerShell 창을 저장소 루트에서 열고 실행합니다. 아래 자리표시자는 실제 발급값으로 바꿉니다.

```powershell
cd reference-app/backend
$env:REFERENCE_APP_CLIENT_ID = '<발급된 client ID>'
$env:REFERENCE_APP_CLIENT_SECRET = '<발급된 client secret>'
.\gradlew.bat bootRun
```

기본 프로필은 `dev`입니다. `application-dev.yaml`에는 상위 Reference App 계획과 같은 로컬 학습용 client 기본값이 있으며, 별도로 그 client를 등록한 환경에서만 유효합니다. 일반 관리자 API로 생성한 client에는 위 환경 변수 override를 사용합니다. 개발 secret은 다른 환경에서 재사용하지 않습니다.

| 개발 설정 | 기본값 | 환경 변수 |
| --- | --- | --- |
| Issuer | `http://idp.localhost:8080` | `REFERENCE_APP_ISSUER` |
| BFF origin | `http://rp.localhost:8180` | `REFERENCE_APP_BFF_ORIGIN` |
| SPA origin | `http://rp.localhost:3100` | `REFERENCE_APP_SPA_ORIGIN` |

Origin 설정에는 scheme, host, 선택적 port만 넣습니다. 끝의 `/`, 경로, user-info, query, fragment, wildcard는 거절합니다. BFF origin을 바꾸면 실제 서버 port와 IdP의 등록 redirect URI도 맞춰야 합니다. Host/Forwarded 헤더로 공개 주소를 변경할 수 없으며 프록시 헤더는 신뢰하지 않습니다. `dev` 이외 환경은 별도로 두 `reference.security` origin과 Spring OAuth2 client/provider 설정, 데이터베이스 접속 설정을 제공해야 합니다.

## HTTP 계약과 현재 구현 경계

브라우저에서 `http://rp.localhost:8180/bff/login`으로 이동하면 OIDC 로그인이 시작됩니다. 성공은 `http://rp.localhost:3100/`, 실패는 `http://rp.localhost:3100/login-error?code=oidc_login_failed`로 돌아갑니다. `returnTo`와 saved request로 복귀 주소를 덮어쓸 수 없습니다. SPA는 아직 구현하지 않아 해당 주소에 화면이 없을 수 있습니다.

GET `/bff/csrf`는 익명으로 호출할 수 있고 `csrfHeaderName`, `csrfToken` 두 필드만 반환합니다. 응답은 `Cache-Control: no-store`이며 token은 요청마다 마스킹됩니다. 같은 브라우저 세션을 유지하며 응답 token을 그대로 `X-CSRF-TOKEN` 헤더로 보내세요. query/form 전송은 허용하지 않습니다. 로그인 성공 후에는 기존 token이 폐기되므로 다시 가져와야 합니다.

```powershell
$csrf = Invoke-RestMethod 'http://rp.localhost:8180/bff/csrf' -SessionVariable browser
$headers = @{ Origin = 'http://rp.localhost:3100'; 'X-CSRF-TOKEN' = $csrf.csrfToken }
```

상태 변경 `/bff/**` 요청에는 세션의 유효한 CSRF 헤더와 정확히 일치하는 단일 SPA Origin이 모두 필요합니다. 누락·`null`·복수 Origin은 403입니다. 향후 SPA는 `/bff/**` proxy로 접근하며 credentialed cross-origin CORS는 열지 않습니다. 익명 보호 API는 로그인 redirect 대신 401을 반환합니다. 기본 form login, HTTP Basic, logout은 비활성화되어 있습니다.

`RP_SESSION`은 host-only, HttpOnly, SameSite=Lax, Path=/ 쿠키이며 기본 Secure=true입니다. 로컬 `dev`와 HTTP 테스트에서만 Secure=false를 사용합니다. 유휴 만료는 30분이고 URL rewriting과 재시작 후 세션 복원은 꺼져 있습니다. SecurityContext, 한 개의 pending 로그인 요청, authorized client와 OAuth token은 해당 HttpSession에만 저장합니다. 성공 시 세션 ID를 교체하며 실패 시 기존 세션과 쿠키를 폐기합니다. 토큰 검증은 Spring Security 6.5.11의 기본 OIDC 검증기를 사용합니다. `exp`/`iat` 필수 및 60초 clock skew 검증도 기본 검증기에 포함됩니다.

인증은 `OAuth2AuthenticationToken`과 로컬 UUID를 연결한 OIDC principal을 사용합니다. 검증된 UserInfo를 한 번 조회한 뒤 로컬 사용자 생성·외부 사본 갱신·최초 관리자 지정을 한 트랜잭션으로 처리합니다. 최초 적격 `COMPANY_ADMIN` 사용자만 bootstrap으로 `APP_ADMIN`을 받으며 이후 외부 역할로 로컬 권한을 동기화하지 않습니다.

`GET /bff/session`의 익명 응답은 200 `{"authenticated":false}`이며 새 세션을 만들지 않습니다. 인증된 응답은 다음 형식입니다.

```json
{
  "authenticated": true,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "displayName": "홍길동",
    "email": "user@example.com",
    "status": "ACTIVE",
    "roles": ["APP_USER"]
  },
  "csrfHeaderName": "X-CSRF-TOKEN",
  "csrfToken": "masked-session-csrf-token"
}
```

`GET /bff/profile`은 익명에게 redirect 없이 401을 반환하며 인증된 응답은 다음 필드만 포함합니다. 이름·이메일·회사·조직은 null을 허용합니다.

```json
{
  "displayName": "홍길동",
  "email": "user@example.com",
  "company": null,
  "organization": null,
  "hrRoles": [],
  "roles": ["APP_USER"]
}
```

두 API와 `/bff/csrf`는 `Cache-Control: no-store`를 반환합니다. OAuth 토큰·client secret·issuer·subject·원본 principal·DB 내부 시각은 API에 노출하지 않습니다. CSRF 토큰은 요청 헤더 전송을 위한 공개 값입니다.

인증된 `/bff/**` 요청마다 DB에서 현재 사용자와 역할을 읽고 그 조회 결과를 인가와 응답에 사용합니다. 이 과정은 UserInfo 호출이나 `lastLoginAt` 갱신을 수행하지 않습니다. 역할 변경은 다음 요청부터 적용되며, 비활성화·삭제 사용자는 세션과 쿠키를 폐기하여 session은 익명 200, profile은 401을 반환합니다. DB 조회 장애는 503으로 차단하며 과거 권한을 사용하지 않습니다.

콜백에서 비활성 사용자가 확인되면 `/login-error?code=local_user_disabled`로 이동하고 외부 사본·로그인 시각 변경도 롤백합니다. 프로토콜·UserInfo·DB 오류는 `/login-error?code=oidc_login_failed`로 이동합니다. 두 주소는 고정 SPA origin을 사용하며 실패 시 기존 세션·쿠키를 정리합니다. 외부 오류 문자열로 오류 코드를 선택하지 않습니다.

실제 관리자 API와 SPA는 후속 작업입니다. 테스트의 권한·authorized client·상태 변경 확인용 endpoint는 테스트 소스에만 있습니다.

## 토큰 갱신과 로그아웃

인증된 BFF 요청은 Access Token 수명이 30초 이하일 때 세션별로 한 번만 갱신하며 동시 요청은 결과를 공유합니다. 새 UserInfo의 원본 sub와 외부 필드를 검증한 뒤 PostgreSQL 사본을 커밋하고, 세션이 계속 열려 있을 때만 새 토큰을 게시합니다. 로컬 권한·상태·생성 시각·마지막 로그인 시각은 보존합니다. 로그인·callback·CSRF·로그아웃·continuation 경로는 갱신에서 제외합니다.

토큰·UserInfo 실패와 갱신 후 DB 실패는 세션을 종료합니다. `/bff/session`은 익명 200, 보호 API는 업무 진입 없이 401을 반환합니다. 갱신 전 로컬 사용자 조회의 DB 장애는 기존처럼 세션을 유지한 503입니다. 회전형 Refresh Token 교환과 폐기는 자동 재시도하지 않습니다. 원격 폐기 실패에도 로컬 종료는 유지하며, 진행 중인 작업이 늦게 얻은 후속 Refresh Token도 폐기를 시도합니다. DB 커밋 직후 로그아웃이 이기면 사본은 남을 수 있지만 세션은 복구하지 않습니다.

| `reference.token-lifecycle` 설정 | 기본값 |
| --- | --- |
| `connect-timeout` | 2s |
| `read-timeout` | 3s |
| `refresh-timeout` | 10s, 네트워크와 DB 작업을 기다리는 상한 |
| `revocation-timeout` | 폐기 호출당 전체 3s |
| `handoff-ttl` / `handoff-capacity` | 60s / 1000, 상한을 넘는 설정은 시작 시 거절 |
| `revocation-uri` | `http://idp.localhost:8080/oauth2/revoke` |
| `end-session-uri` | `http://idp.localhost:8080/connect/logout` |

IdP endpoint는 서버 설정으로 고정합니다. 배포 시 `REFERENCE_APP_REVOCATION_URI`, `REFERENCE_APP_END_SESSION_URI`를 설정할 수 있으며 요청 query·Host·Forwarded로 변경하지 않습니다. HTTP transport는 Apache HttpClient의 redirect·자동 재시도를 명시적으로 끕니다. 최대 32개 worker가 네트워크와 DB 작업을 수행하고 원래 요청 스레드가 결과를 게시합니다. 제한을 넘긴 작업은 세션을 복구할 수 없습니다.

`POST /bff/logout`은 CSRF·Origin 검증 후 로컬 쿠키·세션을 정리하고 보유 Refresh Token 폐기를 시도한 뒤 204를 반환합니다. IdP 브라우저 세션은 유지합니다. 전체 로그아웃은 아래 두 단계입니다. 이 예시는 향후 SPA의 `/bff/**` proxy 경유를 전제로 하며 현재 SPA에 적용된 코드는 아닙니다.

```javascript
const response = await fetch('/bff/logout/identity-provider', {
  method: 'POST', credentials: 'include', headers: { 'X-CSRF-TOKEN': csrfToken }
});
// 성공 여부와 관계없이 앱의 로컬 로그인 표시를 제거합니다.
if (response.ok) {
  const { continueUrl } = await response.json();
  window.location.assign(continueUrl);
}
```

성공 응답은 `200 {"continueUrl":"<설정된 BFF origin>/bff/logout/continue/<ticket>"}`입니다. URL은 반드시 설정된 BFF origin의 절대 주소이며 JSON에 OAuth 토큰은 없습니다. 최상위 GET은 ticket을 한 번 소비하고 고정 IdP endpoint로 303 이동합니다. IdP는 현재 브라우저 세션과 일치하는 ID Token이면 만료 경과만 예외로 허용하며 서명·issuer·client·sub·sid·auth_time·회사·등록 redirect 검증은 유지합니다. 일반 로그인/API JWT 만료 검증은 그대로입니다.

전달 정보 확보 실패는 로컬 종료 후 `503 {"code":"logout_continuation_unavailable"}`입니다. ticket은 최대 60초이고 잘못된 값·만료·재사용·서버 재시작 후 소실은 모두 redirect 없는 410입니다. 오류가 나도 로컬 로그인 상태를 복원하지 않으며 사용자는 필요하면 다시 로그인합니다. IdP 장애나 거절 역시 종료된 RP 세션을 복구하지 않습니다.

전체 로그아웃 303의 `id_token_hint`만 ID Token이 브라우저 URL에 노출되는 승인된 예외입니다. 일반 API·SPA 상태·스토리지에는 OAuth 토큰을 넣지 않습니다. 응답에 `no-store`·`Referrer-Policy: no-referrer`를 적용하며 배포 시 프록시·APM·접근 로그에서도 continuation ticket 경로와 IdP Location 쿼리의 토큰 값을 제거하거나 해당 로깅을 제외해야 합니다. 브라우저 기록에는 URL이 남을 수 있습니다. SPA 구현과 실제 최상위 브라우저 E2E는 Task 8–10의 후속 범위입니다.

## 검증

`reference-app/backend`에서 실행합니다. 전체 테스트의 PostgreSQL은 Testcontainers로 띄우므로 Docker가 필요합니다. 실제 로컬 IdP는 필요하지 않습니다.

```powershell
.\gradlew.bat test --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest' --tests '*ReferenceSecurityPropertiesTest'
.\gradlew.bat test --tests '*OidcLocalLoginIntegrationTest' --tests '*LocalSessionLifecycleIntegrationTest'
.\gradlew.bat clean test
git diff --check
```

HTTP suite는 임시 포트의 Discovery/JWKS/token/UserInfo 서버와 실제 RSA 서명 token을 사용하고 실제 내장 BFF에 redirect를 자동 추적하지 않는 HTTP client로 접속합니다. 일반 `test` 프로필은 고정 provider metadata를 사용하므로 기존 JIT/bootstrap 회귀 테스트도 로컬 IdP 없이 실행됩니다. 기존 프로토콜 HTTP suite는 persistence 자동 구성을 제외합니다. OidcLocalLoginIntegrationTest와 LocalSessionLifecycleIntegrationTest는 실제 ReferenceApplication·서비스·JPA·PostgreSQL Testcontainer를 연결하여 콜백, JIT/bootstrap rollback, 다음 요청의 상태·권한 변경, API와 CSRF를 검증합니다. 테스트 DB 변경은 HTTP 요청에서 관찰할 수 있도록 커밋하며 로컬 개발 DB는 사용하지 않습니다.

TokenLifecycleHttpIntegrationTest는 같은 HTTP·PostgreSQL fixture에서 단일 갱신, 실제 커밋 후 게시 전 로그아웃, 토큰 교환 중 로그아웃, 전체 작업 제한 이후 후속 토큰 폐기, 프로토콜·DB 실패를 latch로 검증합니다. 테스트 전용 transaction proxy 외부 gate를 사용하며 production 테스트 endpoint는 추가하지 않습니다. 실제 IdP와 브라우저를 연결한 E2E는 이번 Task 6에서 실행하지 않았습니다. 정확한 실행 결과와 한계는 [검증 보고서](../../docs/superpowers/reports/2026-09-10-reference-app-token-lifecycle-verification.md)에 기록합니다.

## 로컬 사용자 관리 API

`/bff/admin/**`는 현재 DB의 ACTIVE APP_ADMIN만 접근합니다. HR 관리자 snapshot만으로는 접근할 수 없습니다. 사용자 생성은 OIDC 최초 로그인 JIT로 유지하며 수동 생성·삭제와 HR 편집은 제공하지 않습니다. 모든 관리 응답은 `Cache-Control: no-store`입니다.

로그인한 브라우저 세션을 `$browser`로 유지한 PowerShell 예시입니다. 실제 사용자 UUID를 넣으세요. PUT은 매번 상세를 다시 조회한 version과 유효한 CSRF·SPA Origin을 사용합니다.

```powershell
$base = 'http://rp.localhost:8180'
$users = Invoke-RestMethod "$base/bff/admin/users?page=0&size=20&status=ACTIVE&role=APP_ADMIN" -WebSession $browser
$userId = '<사용자 UUID>'
$detail = Invoke-RestMethod "$base/bff/admin/users/$userId" -WebSession $browser
$csrf = Invoke-RestMethod "$base/bff/csrf" -WebSession $browser
$headers = @{ Origin = 'http://rp.localhost:3100'; 'X-CSRF-TOKEN' = $csrf.csrfToken }
$body = @{ status = 'DISABLED'; version = $detail.version } | ConvertTo-Json
Invoke-RestMethod "$base/bff/admin/users/$userId/status" -Method Put -WebSession $browser -Headers $headers -ContentType 'application/json' -Body $body
$detail = Invoke-RestMethod "$base/bff/admin/users/$userId" -WebSession $browser
$body = @{ roles = @('APP_USER', 'APP_ADMIN'); version = $detail.version } | ConvertTo-Json
Invoke-RestMethod "$base/bff/admin/users/$userId/roles" -Method Put -WebSession $browser -Headers $headers -ContentType 'application/json' -Body $body
```

목록 page는 0부터, size는 1~100(기본 20)입니다. status·role은 AND 필터이고 정렬은 createdAt DESC, id ASC입니다. 목록은 items/page/size/totalElements/totalPages이며 issuer·subject는 상세 externalIdentity에만 포함됩니다. OAuth 토큰과 내부 인증 객체는 노출하지 않습니다.

역할 전체 집합에는 APP_USER가 필수입니다. 같은 값·같은 version은 version/updatedAt을 바꾸지 않습니다. 로그인과 외부 snapshot 갱신도 version을 바꾸므로 `409 OPTIMISTIC_LOCK_CONFLICT`이면 상세를 재조회하고 변경 의도를 확인한 뒤 새 version으로 요청합니다. 자동 재시도하지 않습니다. `409 LAST_ACTIVE_ADMIN_REQUIRED`는 마지막 ACTIVE APP_ADMIN의 강등·비활성화가 거절되었다는 뜻입니다. 다른 활성 관리자가 있으면 자기 변경도 PUT 200으로 성공합니다. 권한 회수는 다음 요청부터 403, 비활성화는 다음 요청의 사용자 검사에서 세션 종료와 보호 API 401로 반영되며 `/bff/session`은 익명 200을 반환합니다.

관리 변경은 기존 bootstrap singleton → 사용자 행 순서로 잠그고 actor 권한을 다시 확인합니다. 최초 관리자 지정·JIT 로그인과 공통 잠금으로 경합할 수 있습니다. READ_COMMITTED에서 PostgreSQL lock_timeout 3초, statement_timeout 5초, Spring 트랜잭션 timeout 10초를 적용하며 DB 설정은 해당 트랜잭션에만 유지합니다. DB 장애·잠금 시간 제한은 MVC에서 `503 SERVICE_UNAVAILABLE`과 전체 롤백으로 처리합니다. 필터의 익명/만료 401과 인가·CSRF·Origin 403은 기존 본문 계약을 유지하며 항상 Problem Details인 것은 아닙니다.

실제 HTTP 관리 회귀는 `AppUserAdminHttpIntegrationTest`에서 같은 OIDC callback·PostgreSQL fixture로 검증합니다. 최신 명령·XML 합계·경쟁 검증·독립 검토 상태는 [사용자 관리 검증 보고서](../../docs/superpowers/reports/2026-09-10-reference-app-user-admin-verification.md)에 기록합니다. 관리자 SPA와 실제 브라우저 E2E는 후속 Task 9·10입니다.
