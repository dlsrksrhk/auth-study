# Reference App BFF

독립 Spring Boot BFF의 Task 5 구현입니다. OIDC Authorization Code 로그인과 로컬 사용자 JIT·최초 관리자 지정을 연결하고, 현재 DB 상태와 권한을 반영하는 세션·프로필 API를 제공합니다. confidential client의 PKCE S256, 콜백 검증과 서버 세션 보안도 유지합니다. Java 21과 Docker Desktop이 필요합니다.

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

토큰 refresh·revocation·logout, 실제 관리자 API와 SPA는 아직 구현하지 않았습니다. 테스트의 권한·authorized client·상태 변경 확인용 endpoint는 테스트 소스에만 있습니다.

## 검증

`reference-app/backend`에서 실행합니다. 전체 테스트의 PostgreSQL은 Testcontainers로 띄우므로 Docker가 필요합니다. 실제 로컬 IdP는 필요하지 않습니다.

```powershell
.\gradlew.bat test --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest' --tests '*ReferenceSecurityPropertiesTest'
.\gradlew.bat test --tests '*OidcLocalLoginIntegrationTest' --tests '*LocalSessionLifecycleIntegrationTest'
.\gradlew.bat clean test
git diff --check
```

HTTP suite는 임시 포트의 Discovery/JWKS/token/UserInfo 서버와 실제 RSA 서명 token을 사용하고 실제 내장 BFF에 redirect를 자동 추적하지 않는 HTTP client로 접속합니다. 일반 `test` 프로필은 고정 provider metadata를 사용하므로 기존 JIT/bootstrap 회귀 테스트도 로컬 IdP 없이 실행됩니다. 기존 프로토콜 HTTP suite는 persistence 자동 구성을 제외합니다. OidcLocalLoginIntegrationTest와 LocalSessionLifecycleIntegrationTest는 실제 ReferenceApplication·서비스·JPA·PostgreSQL Testcontainer를 연결하여 콜백, JIT/bootstrap rollback, 다음 요청의 상태·권한 변경, API와 CSRF를 검증합니다. 테스트 DB 변경은 HTTP 요청에서 관찰할 수 있도록 커밋하며 로컬 개발 DB는 사용하지 않습니다.

실제 IdP와 브라우저를 연결한 E2E는 이번 Task 5에서 실행하지 않았습니다.
