# OAuth 2.0/OIDC Identity Provider 설계

## 1. 문서 상태

- 상태: 대화 설계 승인, 작성본 검토 대기
- 작성일: 2026-08-21
- 기준 브랜치: `main`
- 기준 커밋: `aca75122d6051275bd2b98b969a016590067d82d`
- 선행 설계: `2026-08-20-hr-admin-foundation-design.md`

이 문서는 완성된 HR·관리자·애플리케이션 로컬 JWT 기반 위에 OAuth 2.0 Authorization Server와 OpenID Connect Provider를 추가하는 두 번째 단계를 정의합니다. 프로토콜을 처음 접하는 학습자가 요청과 검증 단계를 깊이 이해할 수 있어야 하며, 동시에 독립 애플리케이션이 표준 클라이언트 라이브러리로 로그인할 수 있는 상호운용성을 제공해야 합니다.

## 2. 승인된 핵심 결정

1. Spring Authorization Server가 표준 프로토콜 처리를 담당하고, 프로젝트가 클라이언트·테넌시·동의·claim·감사 모델을 소유하는 혼합형을 사용합니다.
2. IdP는 회사별 경로가 없는 하나의 공유 issuer를 사용합니다.
3. 모든 OAuth client는 정확히 한 Company에 귀속됩니다.
4. 회사 관리자는 자기 회사 client만 관리하고 시스템 관리자는 전체 client를 관리합니다.
5. `/oauth2/authorize` 흐름은 HR SPA JWT가 아니라 전용 서버 세션과 서버 렌더링 로그인·동의 화면을 사용합니다.
6. HR SPA 토큰과 OAuth/OIDC 토큰은 키, claim, 수명, 저장소와 폐기 정책을 모두 분리합니다.
7. 동의는 기본적으로 필요하며 시스템 관리자가 지정한 `TRUSTED_FIRST_PARTY` client만 생략합니다.
8. HR claim은 `hr.company`, `hr.organization`, `hr.roles` scope로 분리합니다.
9. OIDC `sub`는 Account마다 한 번 생성되는 공개 opaque UUID입니다.
10. 독립 서비스 애플리케이션은 SPA, Spring Boot BFF와 전용 PostgreSQL 데이터베이스로 구성합니다.
11. 독립 앱은 `(iss, sub)`를 외부 identity key로 사용하는 자체 User 도메인과 `ACTIVE`/`DISABLED` 생명주기를 가집니다.
12. 최초 `COMPANY_ADMIN` 로그인 사용자 한 명만 독립 앱의 `APP_ADMIN`으로 bootstrap하고, 이후 앱 역할은 독립적으로 관리합니다.
13. 브라우저에는 OAuth token을 전달하지 않습니다. BFF가 Authorization Code, PKCE, token과 Refresh 회전을 관리하고 브라우저에는 HttpOnly 세션 쿠키만 제공합니다.

## 3. 목표와 성공 기준

### 3.1 목표

- Authorization Code Grant와 PKCE S256을 구현합니다.
- confidential client와 public client를 모두 지원합니다.
- OIDC Discovery, JWKS, ID Token과 UserInfo를 제공합니다.
- 회사 소유 client, 정확한 redirect URI, scope 동의와 일회성 client secret을 관리합니다.
- Authorization Code와 Refresh Token의 일회성·회전·재사용 탐지 규칙을 구현합니다.
- HR 계정을 인증 원천으로 사용하되 프로토콜 데이터와 기존 HR SPA token을 분리합니다.
- 독립 BFF 애플리케이션이 표준 Spring Security OAuth2 Client로 로그인할 수 있게 합니다.
- 안전한 프로토콜 이벤트와 학습 문서로 각 단계를 관찰할 수 있게 합니다.

### 3.2 성공 기준

- 독립 SPA에서 IdP로 이동해 로그인, 비밀번호 변경, 동의, callback, 로컬 사용자 생성과 세션 수립까지 완료됩니다.
- 독립 BFF가 Discovery와 JWKS를 사용해 ID Token을 검증하고 UserInfo를 호출합니다.
- 같은 이메일 문자열이나 사번이 아니라 `(iss, sub)`로 동일 사용자를 재식별합니다.
- 다른 회사 client·계정 조합, 변형 redirect URI, 잘못된 PKCE verifier와 재사용 Code가 모두 거부됩니다.
- 기존 HR SPA 로그인, Refresh, 관리자 API와 E2E가 변경 없이 통과합니다.
- 원문 비밀번호, client secret, Authorization Code, PKCE verifier와 OAuth token이 로그나 프로토콜 이력에 남지 않습니다.

## 4. 제외 범위

- Implicit Grant
- Resource Owner Password Credentials Grant
- Client Credentials Grant
- Token Introspection
- Dynamic Client Registration
- Pushed Authorization Requests와 Device Authorization
- DPoP와 mTLS sender-constrained token
- pairwise subject
- 외부 IdP federation
- SAML
- 운영 배포, HSM, 외부 secret manager와 운영용 TLS 자동화
- 독립 앱의 자체 비밀번호, 일반 회원가입과 HR 조직 복제
- 다중 issuer와 회사별 signing key

위 항목은 Authorization Code/OIDC 흐름이 안정된 뒤 별도 설계로 다룹니다.

## 5. 전체 아키텍처

### 5.1 저장소 구조

```text
auth-study/
├─ backend/                 기존 HR API + 새 IdP OAuth 모듈
├─ frontend/                기존 HR 관리자 Next.js 앱
├─ reference-app/
│  ├─ frontend/             Vite + React + TypeScript SPA
│  └─ backend/              Spring Boot BFF + 독립 앱 API
├─ infrastructure/          IdP DB와 reference DB Docker Compose
└─ docs/                    설계, 계획과 OAuth/OIDC 학습 문서
```

기본 로컬 주소는 다음과 같습니다.

| 구성 | 주소 |
|---|---|
| 기존 HR 관리자 SPA | `http://localhost:3000` |
| IdP issuer | `http://idp.localhost:8080` |
| 독립 SPA | `http://rp.localhost:3100` |
| 독립 BFF | `http://rp.localhost:8180` |
| IdP PostgreSQL | 기존 `auth_study` 데이터베이스 |
| 독립 앱 PostgreSQL | 별도 `reference_app` 데이터베이스, 기본 host port `55433` |

`.localhost` 하위 호스트를 사용해 IdP와 RP의 host-only session cookie를 분리합니다. 독립 SPA 개발 서버는 `/bff/**`를 `rp.localhost:8180`으로 proxy합니다. OAuth callback은 BFF가 직접 처리하며 정확한 개발 redirect URI는 `http://rp.localhost:8180/login/oauth2/code/reference-app`입니다.

### 5.2 신뢰 경계

- 기존 HR 백엔드는 Account 비밀번호와 HR 데이터를 신뢰할 수 있는 원천으로 소유합니다.
- 새 OAuth 모듈은 HR JPA Entity를 직접 참조하지 않습니다. Account 인증과 claim 조회용 애플리케이션 인터페이스만 사용합니다.
- 독립 앱은 IdP의 코드나 데이터베이스를 공유하지 않습니다.
- 독립 BFF는 Discovery, Authorization, Token, Revocation, JWKS, UserInfo와 OIDC Logout endpoint로만 IdP와 통신합니다.
- 독립 앱 User는 IdP Account의 복사본이 아니라 외부 identity에 연결된 별도 도메인입니다.

## 6. IdP 모듈 경계

새 `backend/.../oauth` 기능은 기존 모듈 규칙과 같은 계층을 사용합니다.

```text
oauth.presentation → oauth.application → oauth.domain
                                      ↑
                           oauth.infrastructure
```

### 6.1 책임

| 영역 | 책임 |
|---|---|
| `oauth.domain` | client, redirect URI, scope, consent, subject, authorization, Code와 Refresh family 규칙 |
| `oauth.application` | client 관리, secret 회전, 인가·동의·폐기 orchestration, HR claim 조회 조합 |
| `oauth.infrastructure` | JPA adapter, Spring Authorization Server repository adapter, RS256/JWK 구성 |
| `oauth.presentation` | 관리자 API, 프로토콜 trace API, 서버 렌더링 로그인·동의·오류 화면 연결 |

Spring Authorization Server의 다음 확장 인터페이스를 프로젝트 adapter가 구현합니다.

- `RegisteredClientRepository`
- `OAuth2AuthorizationService`
- `OAuth2AuthorizationConsentService`
- `OAuth2TokenCustomizer<JwtEncodingContext>`
- `OidcUserInfoMapper`에 해당하는 UserInfo mapping
- `JWKSource<SecurityContext>`

Spring filter와 provider는 요청 parsing, 표준 오류, PKCE 검증, Token encoding 같은 프로토콜 기계를 담당합니다. 프로젝트 domain은 tenant, 상태, 수명, 동의, secret과 저장 제약을 담당합니다.

### 6.2 기존 identity와의 연결

OAuth 로그인은 기존 `AuthenticationService.login()`을 호출해 HR SPA token을 발급하지 않습니다. 비밀번호 검증과 계정 상태 검사를 재사용할 수 있도록 identity에 credential 인증용 애플리케이션 인터페이스를 분리합니다.

이 인터페이스는 다음 결과만 반환합니다.

- 인증된 Account ID
- Company ID와 User ID
- Account 역할
- `mustChangePassword`
- 인증 시각

비밀번호나 token 원문은 OAuth 모듈로 전달하지 않습니다.

## 7. SecurityFilterChain과 세션

백엔드는 우선순위가 명시된 별도 `SecurityFilterChain`을 사용합니다.

### 7.1 Authorization Server 체인

대상은 다음과 같습니다.

- `/.well-known/**`
- `/oauth2/**`
- `/userinfo`
- `/connect/logout`
- `/idp/login`
- `/idp/password`
- `/idp/consent`
- `/idp/error`

이 체인은 필요한 경로에서 서버 세션을 사용합니다. `IDP_AUTH_SESSION`은 HttpOnly, SameSite=Lax, local profile에서 Secure=false인 host-only cookie입니다. idle timeout은 30분, 절대 timeout은 8시간입니다. 로그인, 비밀번호 변경, 동의와 로그아웃 POST에는 CSRF token과 Origin 검사를 모두 적용합니다.

### 7.2 기존 HR API 체인

`/api/v1/**`는 기존 HS256 Bearer Token과 `AUTH_STUDY_REFRESH` cookie를 사용하는 stateless 구조를 유지합니다. OAuth session principal이나 OAuth Access Token이 기존 관리자 API 권한으로 자동 변환되지 않습니다.

### 7.3 키 경계

- HR SPA: 기존 HS256 secret과 기존 Access/Refresh 수명
- OAuth/OIDC: 별도 RS256 key ring과 `kid`
- YAML에는 하나의 active private key와 0개 이상의 verification-only public key를 설정할 수 있습니다.
- JWKS는 active public key와 아직 검증 유예 기간인 이전 public key만 노출합니다.
- 이전 public key는 마지막으로 서명한 Token의 최대 수명보다 오래 유지합니다.

## 8. Issuer, tenant와 client 소유권

### 8.1 단일 issuer

issuer는 local profile에서 `http://idp.localhost:8080` 하나입니다. 회사 code를 issuer path나 host에서 읽지 않습니다.

### 8.2 안전한 tenant 결정

- client의 Company는 검증된 client ID에서 결정합니다.
- 사용자의 Company는 인증된 Account에서 결정합니다.
- 두 Company ID가 같을 때만 Authorization을 계속합니다.
- 입력 이메일 domain이나 query parameter를 인가 단계의 tenant selector로 신뢰하지 않습니다.
- Company가 없는 SYSTEM_ADMIN Account는 회사 소유 OAuth client를 사용할 수 없습니다.

### 8.3 client 관리 권한

- COMPANY_ADMIN: 자기 Company의 client 조회·생성·수정·비활성화·secret 회전
- SYSTEM_ADMIN: 모든 Company의 client 관리와 trusted 정책 지정
- USER: client 관리 불가
- COMPANY_ADMIN은 `TRUSTED_FIRST_PARTY`를 설정하거나 해제할 수 없습니다.
- COMPANY_ADMIN이 만든 client의 기본 정책은 `CONSENT_REQUIRED`입니다.

## 9. IdP 데이터 모델

Flyway V7부터 다음 테이블을 추가합니다. 실제 migration은 기능 단위로 V7 이후 여러 파일로 나눌 수 있지만 테이블 책임은 아래와 같아야 합니다.

### 9.1 subject와 client

#### `oauth_subject`

- `id`
- `account_id`: unique
- `subject`: UUID, unique, immutable
- `created_at`

subject는 첫 OAuth 인증 시 transaction 안에서 생성합니다. 동시 생성은 unique constraint로 하나만 성공하게 합니다.

#### `oauth_client`

- 내부 ID와 외부 `client_id`
- `company_id`
- 표시 이름
- `PUBLIC` 또는 `CONFIDENTIAL`
- `ACTIVE` 또는 `DISABLED`
- `CONSENT_REQUIRED` 또는 `TRUSTED_FIRST_PARTY`
- 허용 grant type과 client authentication method
- 생성·수정 시각과 optimistic-lock version

`client_id`는 서버가 생성하는 URL-safe opaque 값이며 전체 시스템에서 유일합니다.

#### `oauth_client_secret`

- client FK
- BCrypt secret hash
- 표시용 마지막 4자 또는 별도 non-secret hint
- 발급·만료·폐기 시각
- version

CONFIDENTIAL client만 secret을 가집니다. 회전 transaction은 이전 active secret을 즉시 폐기하고 관련 OAuth Authorization과 Refresh family도 폐기합니다. 새 원문은 성공 응답에서 한 번만 반환합니다.

#### `oauth_client_redirect_uri`

- client FK
- redirect URI 원문
- `(client_id, redirect_uri)` unique

URI는 등록 시 scheme, absolute URI, fragment 금지, user-info 금지 규칙을 검증합니다. local profile에서는 `http://*.localhost`만 HTTP 예외로 허용합니다. 비교는 저장한 문자열과 요청 문자열의 정확한 일치이며 decode, normalize, prefix와 wildcard 비교를 하지 않습니다.

#### `oauth_client_scope`

- client FK
- 허용 scope
- `(client_id, scope)` unique

### 9.2 동의와 Authorization

#### `oauth_consent` / `oauth_consent_scope`

- Account와 client 조합 unique
- 승인 scope 집합
- 최초·최근 승인 시각

요청 scope가 기존 승인 scope의 부분집합이면 다시 묻지 않습니다. 새 scope가 포함되면 전체 요청 scope와 새 항목을 구분해 보여주고 승인 결과를 저장합니다. 거부는 기존 승인을 확장하지 않습니다.

#### `oauth_authorization`

- client, Account, subject와 Company
- 인증 시각
- 승인 scope
- 상태와 폐기 사유
- 생성·만료·폐기 시각

#### `oauth_authorization_code`

- Authorization FK
- Code SHA-256 hash
- 정확한 redirect URI
- PKCE S256 challenge
- nonce metadata
- 발급·만료·사용 시각

Code 원문은 저장하지 않습니다. Token 요청의 Code를 hash해 조회하고 행 잠금 안에서 한 번만 소비합니다. 성공한 교환뿐 아니라 소비를 확정한 invalid exchange와 replay 처리도 재사용이 불가능해야 합니다.

#### `oauth_access_token`

- Authorization FK
- `jti`, token hash와 audience
- 발급·만료·폐기 시각

Access Token은 self-contained JWT이지만 UserInfo, Revocation과 프로토콜 상태 조회를 위해 non-secret metadata와 hash를 보관합니다. 원문 JWT는 저장하지 않습니다.

#### `oauth_refresh_token`

- Authorization FK
- token SHA-256 hash
- family UUID
- 발급·절대 만료·사용·폐기 시각
- successor 관계

Refresh 원문은 저장하지 않습니다. 사용된 Token 재제출은 row lock 안에서 family 전체를 폐기합니다. 동시 Refresh 요청 중 하나만 성공합니다.

### 9.3 프로토콜 이력

#### `oauth_protocol_event`

- correlation ID
- client, Company, Account와 Authorization의 nullable 식별자
- event type
- 성공 여부와 표준 오류 code
- 비민감 metadata
- 발생 시각

event type은 최소 다음을 포함합니다.

- `AUTHORIZATION_REQUEST_VALIDATED`
- `LOGIN_REQUIRED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`
- `PASSWORD_CHANGE_REQUIRED`, `PASSWORD_CHANGED`
- `CONSENT_GRANTED`, `CONSENT_DENIED`
- `AUTHORIZATION_CODE_ISSUED`, `AUTHORIZATION_CODE_EXCHANGED`, `AUTHORIZATION_CODE_REPLAY_REJECTED`
- `REFRESH_ROTATED`, `REFRESH_REUSE_DETECTED`
- `AUTHORIZATION_REVOKED`
- `USERINFO_SUCCEEDED`, `USERINFO_DENIED`

비밀번호, client secret, Code, Token, PKCE verifier, cookie와 전체 요청 본문은 저장하지 않습니다. 성공 event는 업무 transaction과 함께 저장하고 실패 event는 기존 audit 실패 기록 원칙처럼 잠금과 connection을 해제한 뒤 별도 transaction에서 저장합니다.

## 10. Token과 claim 계약

### 10.1 수명

| 항목 | 수명 |
|---|---|
| Authorization Code | 60초, 일회용 |
| ID Token | 5분 |
| Access Token | 5분 |
| Refresh Token family | 최초 발급부터 최대 7일 |
| IdP session | idle 30분, 절대 8시간 |
| RP session | idle 30분, Refresh family보다 길 수 없음 |

Refresh 회전은 family의 절대 만료를 연장하지 않습니다.

### 10.2 ID Token

항상 포함하는 claim은 다음과 같습니다.

- `iss`
- opaque UUID `sub`
- client ID인 `aud`
- `exp`, `iat`
- `auth_time`
- 요청에 포함된 `nonce`
- signing key의 `kid`는 JOSE header에 포함

`profile` 또는 `email` scope가 있어도 HR 조직 claim을 ID Token에 넣지 않습니다. BFF는 인증 사실을 ID Token에서, 프로필과 HR 속성을 UserInfo에서 얻습니다.

### 10.3 Access Token

- `iss`, `sub`, `aud`
- `client_id`
- space-delimited `scope`
- `jti`, `iat`, `exp`

MVP의 audience는 `auth-study-userinfo`입니다. HR 역할·부서·직위는 Access Token에 넣지 않습니다. 별도 Resource Server를 추가할 때 audience와 resource scope를 새 설계로 확장합니다.

### 10.4 UserInfo

UserInfo의 `sub`는 ID Token `sub`와 반드시 같아야 합니다. 모든 custom claim은 collision을 피하기 위해 namespaced claim을 사용합니다.

| scope | UserInfo claim |
|---|---|
| `openid` | `sub` |
| `profile` | `name` |
| `email` | `email`, `email_verified=false` |
| `hr.company` | `https://auth-study.local/claims/company`에 Company code와 이름 |
| `hr.organization` | `https://auth-study.local/claims/organization`에 직위와 활성 부서 소속 |
| `hr.roles` | `https://auth-study.local/claims/roles`에 Account 역할 이름 |

`email_verified`는 실제 이메일 소유 확인 절차가 없으므로 false입니다. 내부 Long ID, employee number와 비밀번호 상태는 claim으로 노출하지 않습니다.

## 11. Authorization Code와 로그인 흐름

1. 독립 SPA가 `/bff/login`으로 top-level navigation합니다.
2. BFF는 server session에 무작위 `state`, `nonce`, PKCE `code_verifier`를 저장합니다.
3. BFF는 S256 `code_challenge`와 정확한 callback URI로 Authorization 요청을 시작합니다.
4. IdP는 client 상태, redirect URI와 요청 scope를 검증합니다.
5. IdP session에 인증 principal이 없으면 `/idp/login`을 표시하고 원래 Authorization 요청을 session에 보존합니다.
6. identity credential 인증 인터페이스가 비밀번호와 Account·User·Company 상태를 검증합니다.
7. `mustChangePassword=true`이면 `/idp/password`로 이동합니다. 변경 완료 후 같은 Authorization 요청으로 복귀합니다.
8. client Company와 Account Company가 다르면 일반적인 접근 거부로 종료합니다.
9. `CONSENT_REQUIRED` client는 `/idp/consent`를 표시합니다. trusted client는 이 단계를 생략합니다.
10. IdP는 client, redirect URI, subject, scope, PKCE challenge와 nonce에 묶인 60초 Code를 발급합니다.
11. BFF callback은 `state`를 먼저 constant-time 비교하고 일치하지 않으면 session을 폐기합니다.
12. BFF는 Code, verifier와 `client_secret_basic`으로 Token 요청을 보냅니다.
13. IdP는 Code row와 Authorization을 잠그고 client, redirect URI, PKCE와 상태를 검증한 뒤 Code를 소비합니다.
14. IdP는 RS256 ID/Access Token과 opaque Refresh Token을 발급합니다.
15. BFF는 Discovery/JWKS를 사용해 signature, `kid`, `iss`, `aud`, `exp`, `iat`, `nonce`, `auth_time`을 검증합니다.
16. 검증이 끝난 뒤에만 Access Token으로 UserInfo를 호출합니다.
17. BFF는 UserInfo `(iss, sub)`로 로컬 User를 생성하거나 snapshot을 갱신합니다.
18. 로컬 User가 DISABLED이면 OAuth token과 RP session을 폐기하고 앱 접근을 거부합니다.
19. BFF는 OAuth token을 server memory session에 저장하고 브라우저에는 `RP_SESSION` cookie만 발급합니다.

BFF 재시작은 로그인 해제로 처리합니다. MVP는 raw Refresh Token을 디스크나 독립 앱 데이터베이스에 저장하지 않습니다.

## 12. 독립 앱 User 도메인

### 12.1 데이터 모델

#### `app_user`

- 내부 UUID
- `issuer`, `subject`
- email과 display name snapshot
- `ACTIVE` 또는 `DISABLED`
- 최초·마지막 로그인 시각
- 생성·수정 시각과 version
- `(issuer, subject)` unique

#### `app_user_role`

- User FK
- `APP_USER` 또는 `APP_ADMIN`
- `(user_id, role)` unique

#### `app_bootstrap_state`

- singleton row
- 최초 관리자 할당 여부와 할당 User
- version

### 12.2 JIT provisioning

- 최초 로그인은 `(issuer, subject)`로 User를 생성하고 `APP_USER`를 부여합니다.
- 이후 로그인은 이름·이메일 snapshot과 마지막 로그인 시각을 갱신합니다.
- claim 변경은 로컬 식별자를 바꾸지 않습니다.
- IdP Company·Department·Position을 로컬 테이블로 복제하지 않습니다.

### 12.3 최초 관리자 bootstrap

- bootstrap state를 row lock으로 잠급니다.
- 아직 관리자가 없고 현재 UserInfo roles에 `COMPANY_ADMIN`이 있으면 현재 User에 `APP_ADMIN`을 추가합니다.
- 동시에 여러 COMPANY_ADMIN이 로그인해도 한 명만 최초 관리자가 됩니다.
- bootstrap 뒤 HR 역할 변경은 앱 역할을 자동 변경하지 않습니다.
- APP_ADMIN은 다른 User의 앱 역할을 관리할 수 있습니다.
- 마지막 ACTIVE APP_ADMIN의 비활성화와 `APP_ADMIN` 회수는 거부합니다.

## 13. 동의, Refresh와 폐기

### 13.1 동의

- 기본 client 정책은 `CONSENT_REQUIRED`입니다.
- SYSTEM_ADMIN만 `TRUSTED_FIRST_PARTY`를 지정할 수 있습니다.
- 동의 화면은 client 표시 이름, 요청 scope와 scope별 제공 정보를 설명합니다.
- 사용자가 거부하면 검증된 redirect URI에 `error=access_denied`와 원래 `state`를 전달합니다.
- client가 이전보다 넓은 scope를 요청하면 다시 동의를 받습니다.

### 13.2 Refresh 회전

- BFF가 Access Token 만료 전에 Refresh를 요청합니다.
- IdP는 Account, User, Company, client, Authorization과 동의 상태를 현재 값으로 다시 검증합니다.
- 현재 Refresh row와 관련 Account를 잠그고 기존 Token을 사용 처리한 뒤 successor를 발급합니다.
- 이미 사용된 Token 제출은 family reuse로 간주하고 family 전체를 폐기합니다.
- 브라우저의 동시 요청은 BFF session 단위 single-flight로 하나의 Refresh만 실행합니다.

### 13.3 상태 변경 폐기

다음 사건은 관련 OAuth Authorization과 Refresh family를 폐기합니다.

- 비밀번호 변경·재설정
- Account 비활성화 또는 잠금
- HR User 퇴사 또는 잠금
- Company 비활성화
- Account 역할 변경
- OAuth client 비활성화
- client secret 회전·폐기
- 사용자 동의 또는 관리자의 grant 철회

Access Token은 self-contained JWT이므로 이미 발급된 Token은 최대 5분간 암호학적으로 유효할 수 있습니다. UserInfo는 현재 Account·User·Company·client 상태를 다시 조회해 즉시 거부합니다. BFF RP session은 Access Token 수명 경계에서 Refresh/UserInfo 재검증을 수행하므로 외부 상태 변경 반영 지연의 상한은 5분입니다. 즉시 전역 세션 종료가 필요한 back-channel logout과 introspection은 후속 범위입니다.

## 14. 로그아웃

독립 앱은 두 동작을 구분합니다.

### 14.1 앱 로그아웃

1. BFF가 현재 Refresh family를 Revocation Endpoint로 폐기합니다.
2. RP server session과 `RP_SESSION` cookie를 제거합니다.
3. IdP 로그인 session은 유지합니다.

다시 로그인하면 IdP에서 비밀번호 입력이 생략될 수 있지만 필요한 동의 정책은 다시 적용합니다.

### 14.2 전체 로그아웃

1. 앱 로그아웃을 수행합니다.
2. BFF가 `id_token_hint`와 등록된 post-logout redirect URI를 사용해 OIDC RP-Initiated Logout을 시작합니다.
3. IdP가 `IDP_AUTH_SESSION`을 종료하고 검증된 독립 SPA 주소로 이동합니다.

두 버튼은 사용자가 차이를 알 수 있도록 설명을 표시합니다.

## 15. 오류 처리

### 15.1 Authorization Endpoint

- client ID나 redirect URI 검증 전 오류는 외부 URI로 redirect하지 않습니다.
- 검증된 redirect URI가 있을 때만 OAuth 오류와 원래 `state`를 전달합니다.
- client·회사·계정 존재 여부를 불필요하게 노출하지 않습니다.
- 로그인 실패는 기존 generic 인증 오류와 lockout timing 보호를 유지합니다.

### 15.2 Token, UserInfo와 Revocation Endpoint

- RFC/OIDC 규칙에 맞는 `invalid_client`, `invalid_grant`, `invalid_scope`, `invalid_token` 오류를 사용합니다.
- 관리자 REST API의 Problem Details 응답과 프로토콜 endpoint 응답을 섞지 않습니다.
- Code replay와 Refresh reuse는 generic protocol 오류를 반환하되 내부 event에는 분류된 원인을 기록합니다.

### 15.3 BFF callback

다음 중 하나라도 실패하면 임시 state, token과 RP session을 제거하고 로컬 User를 만들지 않습니다.

- state 불일치·누락
- nonce 불일치·누락
- issuer 또는 audience 불일치
- signature 또는 `kid` 검증 실패
- 만료·미래 `iat`
- UserInfo `sub` 불일치
- 로컬 User DISABLED

## 16. 브라우저 보안

- Authorization Code와 Access Token을 URL fragment에 넣지 않습니다.
- public·confidential client 모두 PKCE S256을 사용하며 `plain`은 허용하지 않습니다.
- Authorization Endpoint에는 CORS를 허용하지 않습니다.
- public client 통합 테스트를 위해 Token, Metadata와 JWKS endpoint에는 등록된 exact origin만 선택적으로 허용합니다.
- wildcard CORS와 wildcard redirect URI는 금지합니다.
- IdP와 RP의 state-changing POST에는 CSRF token과 exact Origin 검사를 적용합니다.
- 로그인·동의·callback 화면은 third-party script와 외부 resource를 사용하지 않습니다.
- `Referrer-Policy: no-referrer`, 제한된 CSP와 frame 거부 정책을 적용합니다.
- local HTTP profile에서만 session cookie `Secure=false`를 허용합니다.
- client secret, private key, token과 cookie는 애플리케이션 로그에서 redaction합니다.

## 17. 화면 설계

### 17.1 기존 HR 관리자 SPA

`인증/인가 설정` 영역에 다음 route를 추가합니다.

- OAuth client 목록·상세·생성·수정
- client 상태 변경
- redirect URI와 scope 관리
- client 유형과 authentication method 조회
- client secret 발급·회전·폐기와 일회성 표시
- 동의·Authorization 조회와 철회
- correlation ID별 안전한 protocol event timeline

일회성 client secret은 기존 임시 비밀번호 provider pattern을 재사용하되 타입과 provenance를 분리해 서로 대체할 수 없게 합니다.

### 17.2 서버 렌더링 IdP 화면

- 로그인
- 강제 비밀번호 변경
- scope 동의·거부
- protocol 오류
- 전체 로그아웃 확인

동의 화면은 raw scope 이름뿐 아니라 사람이 읽을 수 있는 정보 제공 설명을 함께 보여줍니다.

### 17.3 독립 SPA

- 로그인 전 랜딩
- 내 프로필과 IdP claim 확인
- 현재 session과 마지막 로그인 시각
- 앱 로그아웃과 전체 로그아웃
- APP_ADMIN 사용자 목록·상세
- User 활성화·비활성화
- APP_USER·APP_ADMIN 역할 관리
- 최초 APP_ADMIN bootstrap 결과 안내

## 18. 테스트 전략

### 18.1 IdP domain·통합 테스트

- public client의 PKCE 누락, mismatch, `plain`, downgrade와 Code replay
- confidential client의 secret 누락·오류, BCrypt hash와 일회성 rotation 표시
- exact redirect URI의 대소문자, encoded path, slash, query와 fragment edge case
- client·Account Company 불일치와 COMPANY_ADMIN cross-tenant 관리
- `state` 전달과 `nonce` ID Token 계약
- Code 만료와 동시 교환
- 동의 승인·거부·재사용·추가 scope 재동의
- trusted client 권한과 COMPANY_ADMIN의 trusted 설정 거부
- scope별 UserInfo claim 포함·누락
- `iss`, `sub`, `aud`, `exp`, `iat`, `auth_time`, `jti`, `kid` 계약
- signing key active·verification-only JWKS 동작
- Refresh 회전, 동시 요청과 reuse family 폐기
- Account·User·Company·client·역할·비밀번호 상태 변경 폐기
- protocol event의 secret 비기록
- 두 SecurityFilterChain의 matcher와 principal 격리

### 18.2 독립 BFF·User 테스트

- Discovery와 JWKS cache·key rotation 대응
- state·nonce·issuer·audience·signature 실패 시 session 폐기
- UserInfo `sub` mismatch 거부
- `(iss, sub)` JIT 생성 concurrency
- profile snapshot과 마지막 로그인 갱신
- 동시 최초 APP_ADMIN bootstrap 한 명 보장
- HR 역할 변경이 기존 APP_ADMIN을 자동 회수하지 않음
- 로컬 DISABLED User 로그인 거부
- 마지막 ACTIVE APP_ADMIN 보호
- BFF Refresh single-flight
- 앱 로그아웃과 전체 로그아웃 차이

### 18.3 프런트엔드 테스트

- 기존 Next.js client 관리 form과 일회성 secret provider
- consent·protocol event 조회 UI
- 독립 SPA 로그인 상태와 callback 오류 화면
- profile, 사용자 목록·상세, 상태·역할 mutation
- 접근성 있는 form error와 keyboard 흐름

### 18.4 실제 Chromium E2E

최소 다음 흐름을 실제 프로세스와 두 PostgreSQL 데이터베이스로 실행합니다.

1. SYSTEM_ADMIN이 Company client를 만들고 secret을 한 번 확인합니다.
2. COMPANY_ADMIN 사용자가 독립 SPA에서 로그인을 시작합니다.
3. IdP 로그인과 scope 동의를 거쳐 BFF callback이 완료됩니다.
4. 독립 앱 User가 생성되고 최초 APP_ADMIN으로 bootstrap됩니다.
5. 두 번째 일반 사용자가 로그인해 APP_USER가 됩니다.
6. APP_ADMIN이 두 번째 사용자를 DISABLED로 바꾸고 재접근을 거부합니다.
7. 앱 로그아웃 후 IdP SSO session 유지, 전체 로그아웃 후 IdP 재로그인을 확인합니다.
8. protocol timeline에 성공 event가 있고 secret 원문이 없음을 확인합니다.
9. 기존 HR 관리자 E2E를 다시 실행해 회귀가 없음을 확인합니다.

PKCE verifier 변조, Code replay, nonce mismatch와 Refresh race처럼 브라우저 조작보다 통합 테스트가 더 정확한 공격 경로는 backend integration suite에서 검증합니다.

## 19. 학습 문서

`docs/oauth-oidc-study-guide.md`를 구현과 함께 작성합니다. 다음 내용을 포함합니다.

- OAuth 2.0 역할: Resource Owner, User Agent, Client, Authorization Server, Resource Server
- OAuth와 OIDC의 차이
- Authorization Code + PKCE의 HTTP 요청·응답
- state, nonce와 PKCE가 막는 공격의 차이
- ID Token, Access Token과 Refresh Token의 대상과 사용처
- Discovery와 JWKS 검증 과정
- scope, consent와 claim mapping
- public client, confidential client와 BFF 비교
- tenant와 issuer의 차이
- 프로젝트의 Spring Authorization Server extension point와 해당 테스트 위치
- 의도적으로 연기한 introspection, pairwise subject, DPoP, federation과 SAML

문서 예제에는 실제 secret과 token을 넣지 않고 구조가 보존된 축약값만 사용합니다.

## 20. 구현 순서

1. 격리된 `codex/oauth2-oidc-idp` worktree를 생성합니다.
2. 현재 baseline 테스트를 재실행합니다.
3. Spring Authorization Server dependency와 SecurityFilterChain 골격을 TDD로 추가합니다.
4. subject와 client domain, V7+ migration과 관리자 API를 구현합니다.
5. custom Spring repository adapter와 Authorization Code + PKCE를 구현합니다.
6. 서버 session 로그인·비밀번호 변경·동의를 연결합니다.
7. RS256, Discovery, JWKS, ID/Access Token과 UserInfo를 구현합니다.
8. OAuth Refresh rotation, Revocation과 상태 변경 폐기를 구현합니다.
9. 기존 Next.js에 client·동의·protocol timeline UI를 추가합니다.
10. 독립 PostgreSQL과 Spring Boot BFF/User domain을 구현합니다.
11. Vite React SPA와 실제 login/user-admin 흐름을 구현합니다.
12. 학습 문서와 전체 Chromium E2E를 완성합니다.
13. backend, frontend, reference app의 전체 test·lint·build와 독립 review를 통과합니다.

각 단계는 실패 테스트부터 작성하고 기존 HR/JWT 동작을 회귀 테스트로 고정합니다.

## 21. 로컬 학습 환경의 의도적 제약

이 설계의 프로토콜 구조와 검증 규칙은 표준을 따르지만 실행 환경은 운영 배포가 아닌 로컬 학습용입니다. 다음 항목은 운영 환경에 그대로 적용할 수 없는 의도적 차이입니다.

- `idp.localhost`와 `rp.localhost`에서 HTTP redirect를 사용합니다. 운영 환경은 HTTPS redirect만 허용해야 합니다.
- local profile의 session cookie는 `Secure=false`입니다. HTTPS 환경에서는 반드시 `Secure=true`여야 합니다.
- RS256 private key와 BFF client secret을 개발 YAML에 둘 수 있습니다. 운영 환경은 외부 secret manager나 HSM과 제한된 파일 권한을 사용해야 합니다.
- Access Token이 self-contained JWT이므로 상태 변경 뒤 최대 5분의 폐기 지연이 있습니다. 즉시 폐기가 필요한 운영 요구는 introspection, sender-constrained token 또는 back-channel session 종료를 별도로 설계해야 합니다.
- 이 단계의 상호운용성 검증은 Spring Security OAuth2 Client와 프로젝트 테스트를 이용하며 OpenID Certification 적합성 시험을 대체하지 않습니다.

README와 학습 문서는 위 차이를 눈에 띄게 표시하고 개발용 key·secret을 다른 환경에서 재사용하지 말아야 한다고 설명합니다.

## 22. 표준과 공식 참고 자료

- OAuth 2.0 Security Best Current Practice, RFC 9700: <https://www.rfc-editor.org/rfc/rfc9700>
- Proof Key for Code Exchange, RFC 7636: <https://www.rfc-editor.org/rfc/rfc7636>
- OAuth 2.0 Authorization Server Metadata, RFC 8414: <https://www.rfc-editor.org/rfc/rfc8414>
- JSON Web Key, RFC 7517: <https://www.rfc-editor.org/rfc/rfc7517>
- OAuth 2.0 Token Revocation, RFC 7009: <https://www.rfc-editor.org/rfc/rfc7009>
- OpenID Connect Core 1.0: <https://openid.net/specs/openid-connect-core-1_0.html>
- OpenID Connect Discovery 1.0: <https://openid.net/specs/openid-connect-discovery-1_0.html>
- OpenID Connect RP-Initiated Logout 1.0: <https://openid.net/specs/openid-connect-rpinitiated-1_0.html>
- OAuth 2.0 for Browser-Based Applications draft: <https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/>
- Spring Authorization Server reference: <https://docs.spring.io/spring-authorization-server/reference/>
- Spring Authorization Server protocol endpoints: <https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html>

## 23. 완료 조건

이 단계는 다음 조건을 모두 만족해야 완료입니다.

- 승인된 scope와 claim 계약이 자동화 테스트로 고정됩니다.
- Spring Security OAuth2 Client 기반 독립 BFF 로그인이 실제 브라우저에서 성공합니다.
- public client PKCE와 confidential client secret 흐름이 모두 검증됩니다.
- client·Account·Company 상태와 tenant 경계가 Authorization, Token, Refresh와 UserInfo에서 일관되게 적용됩니다.
- OAuth token과 기존 HR SPA token이 키·저장소·principal·cookie에서 섞이지 않습니다.
- 독립 앱이 IdP DB 없이 자체 `(iss, sub)` User와 권한을 관리합니다.
- Code·Refresh replay와 concurrent rotation 테스트가 통과합니다.
- protocol event와 audit에서 민감 원문이 검출되지 않습니다.
- 기존 HR backend/frontend 테스트와 E2E가 모두 통과합니다.
- `docs/oauth-oidc-study-guide.md`만 읽고 전체 redirect와 token 흐름을 재현할 수 있습니다.
