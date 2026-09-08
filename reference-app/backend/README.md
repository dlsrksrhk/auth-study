# Reference App BFF

독립 Spring Boot BFF의 Task 4 구현입니다. OIDC Authorization Code 로그인, confidential client의 PKCE S256, 콜백 검증과 서버 세션 보안을 제공합니다. Java 21과 Docker Desktop이 필요합니다.

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

현재 인증은 Spring의 `OAuth2AuthenticationToken`과 OIDC principal입니다. 로컬 `AppUser` 인증 완료를 의미하지 않습니다. 외부 역할을 `APP_USER`/`APP_ADMIN`으로 매핑하지 않습니다. Task 5의 사용자 provisioning 연결, 로컬 principal, `/bff/session`·`/bff/profile` controller는 아직 없습니다. GET `/bff/session`은 익명 접근 규칙만 예약되어 있습니다. token 갱신·logout은 Task 6이며 mutation 테스트용 endpoint는 테스트 소스에만 있습니다.

## 검증

`reference-app/backend`에서 실행합니다. 전체 테스트의 PostgreSQL은 Testcontainers로 띄우므로 Docker가 필요합니다. 실제 로컬 IdP는 필요하지 않습니다.

```powershell
.\gradlew.bat test --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*BffSessionSecurityIntegrationTest' --tests '*ReferenceSecurityPropertiesTest'
.\gradlew.bat test
git diff --check
```

HTTP suite는 임시 포트의 Discovery/JWKS/token/UserInfo 서버와 실제 RSA 서명 token을 사용하고 실제 내장 BFF에 redirect를 자동 추적하지 않는 HTTP client로 접속합니다. 일반 `test` 프로필은 고정 provider metadata를 사용하므로 기존 JIT/bootstrap 회귀 테스트도 로컬 IdP 없이 실행됩니다. 보안 HTTP fixture만 persistence 자동 구성을 제외하며 실제 프로덕션 필터 체인과 controller를 사용합니다.

실제 IdP와 브라우저를 연결한 E2E는 이번 Task 4에서 실행하지 않았습니다.
