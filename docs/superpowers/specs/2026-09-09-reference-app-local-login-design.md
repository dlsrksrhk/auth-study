# Reference App Task 5: OIDC 로그인과 앱 내부 사용자 연결 설계

작성일: 2026-09-09

상태: 대화에서 연결 구조, 요청별 상태·권한 확인, API 계약과 검증 범위를 승인받았으며 작성된 설계 문서도 승인되었습니다. 구현 계획은 `../plans/2026-09-09-reference-app-local-login.md`입니다. 구현은 아직 시작하지 않았습니다.

## 목적과 범위

기반 커밋은 `7f9b995`입니다. Task 1–3의 독립 사용자 저장·JIT·최초 관리자 지정과 Task 4의 OIDC 로그인·세션 보안을 연결하여 로컬 사용자 인증을 완성합니다.

상위 문서는 [Reference App 계획 Task 5](../plans/2026-08-21-oauth-oidc-reference-app.md#task-5-callback-userinfo-검증-local-principal과-session-api)와 [Task 4 설계](2026-09-08-reference-app-oidc-session-security-design.md)입니다. 상위 계획과 충돌하는 Task 5의 세부 사항에는 이 문서를 적용합니다.

포함 범위는 검증된 UserInfo 변환, 기존 JIT·bootstrap 연결, 로컬 사용자 인증, 요청별 현재 상태·권한 조회, `/bff/session`과 `/bff/profile`, 실패 정리 및 통합 테스트입니다. 토큰 refresh·revocation·logout은 Task 6, 관리자 API는 Task 7, SPA와 실제 IdP 브라우저 E2E는 Task 8–10에서 구현합니다.

IdP 코드·DB·키를 공유하지 않으며 기존 사용자 identity와 bootstrap 정책을 유지합니다. 사용자 관리 화면이나 DB 스키마 변경은 필요하지 않습니다.

## 선택한 연결 구조

세 가지 접근을 비교했습니다.

| 접근 | 장점 | 부담과 결정 |
|---|---|---|
| 기존 Spring OIDC 처리 확장 | 기존 UserInfo 조회·검증과 세션 토큰 저장 재사용 | 로컬 연결 경계를 명확히 정의해야 하며, 이 방식을 채택합니다 |
| 성공 핸들러에서 독립 인증 객체로 교체 | 로컬 인증 모델 독립 표현 | authorized client와 인증 이름의 연계를 추가 관리해야 합니다 |
| UserInfo HTTP 호출·토큰 보관 직접 구현 | 전체 흐름 직접 제어 | 기존 동작과 중복되므로 채택하지 않습니다 |

`OAuth2AuthenticationToken`과 OIDC principal 계약을 유지하면서 로컬 사용자 UUID와 로컬 권한을 연결합니다. 표준 OIDC 사용자 처리에 위임하는 확장 지점에서 로컬 연결을 수행하고, 연결 실패가 인증 성공으로 저장되기 전에 인증 오류로 처리되도록 합니다. 구체적인 클래스·필터 연결은 구현 계획에서 현재 dependency 소스로 확인합니다.

원본 OIDC 사용자와 토큰은 서버 내부에만 존재합니다. 컨트롤러는 OIDC 객체를 직접 반환하지 않고 별도 응답 DTO를 사용합니다. 프로토콜 principal의 이름과 registration ID는 로그인 및 후속 요청에서 안정적으로 유지하고, 로컬 UUID를 별도 필드로 둡니다. 토큰 조회를 위해 principal 이름을 로컬 UUID로 바꾸지 않습니다.

`HttpSessionOAuth2AuthorizedClientRepository`를 토큰 저장소로 유지합니다. 상위 계획의 별도 `AppUserAuthentication`, `RpOAuthSession`, 직접 HTTP 호출용 `OidcUserInfoClient`는 필수 생성 대상에서 제외합니다. OAuth 토큰을 같은 세션의 별도 객체에 중복 저장하지 않습니다.

## 로그인 데이터 흐름

1. 기존 Task 4의 고정 callback, state, PKCE, ID Token 검증을 통과합니다.
2. 표준 OIDC 처리로 얻은 UserInfo를 사용합니다. 로그인 한 번에 UserInfo HTTP 요청을 중복 실행하지 않습니다.
3. UserInfo가 존재하고 `sub`가 유효한 문자열이며 검증된 ID Token `sub`와 정확히 일치해야 합니다. 누락·불일치는 DB 호출 전에 거절합니다. 병합된 claims에서 `sub`를 읽어 불일치를 숨기지 않습니다.
4. identity issuer는 검증된 ID Token에서 가져옵니다. `(issuer, subject)`를 trim·대소문자 변환·URI 정규화로 합치지 않습니다.
5. UserInfo의 허용 필드만 `ExternalIdentityProfile`로 변환하고 타입·구조를 검증합니다. HTTP 호출과 이 검증은 DB 트랜잭션 전에 완료합니다.
6. 트랜잭션을 가진 로컬 로그인 조율 계층이 기존 `AppLoginProvisioningService.provision`을 호출하고 결과가 ACTIVE인지 확인합니다. 기존 서비스의 REQUIRED 참여와 `bootstrap_state → app_user` 잠금 순서를 유지합니다.
7. DISABLED이면 해당 트랜잭션을 롤백하고 인증을 거절합니다. 실패한 비활성 사용자 로그인 때문에 외부 사본이나 `lastLoginAt`이 바뀌지 않게 합니다. 기존 standalone JIT의 상태 보존 계약은 변경하지 않습니다.
8. 커밋된 로컬 사용자 ID와 역할을 OIDC principal에 연결한 뒤 인증을 완료합니다. 기존 세션 ID 교체, 로그인 전 CSRF 토큰 폐기, 고정 SPA `/` redirect를 유지합니다.

JIT와 최초 관리자 지정은 하나의 DB 트랜잭션입니다. 저장 실패는 함께 롤백됩니다. DB 커밋과 HTTP 세션 저장을 분산 트랜잭션으로 묶지는 않습니다. DB 커밋 후 세션 수립 실패 시 로컬 세션은 정리하지만 이미 커밋한 사용자·bootstrap을 되돌리지는 않습니다. 재로그인은 기존 identity로 수렴합니다.

## UserInfo 변환 계약

| 원본 | 로컬 입력 |
|---|---|
| 검증된 ID Token `iss`, `sub` | `issuer`, `subject` |
| UserInfo `email`, `name` | `email`, `displayName` |
| `https://auth-study.local/claims/company` | `company` |
| `https://auth-study.local/claims/organization` | `organization` |
| `https://auth-study.local/claims/roles` | `hrRoles` |

claim 이름은 Reference App에 독립적으로 정의합니다. IdP 구현 상수를 import하지 않습니다. 이메일·이름·회사·조직이 없으면 null, HR 역할이 없으면 빈 집합입니다. 존재하는 이메일·이름은 문자열이어야 합니다. HR 역할은 문자열 배열을 집합으로 변환하며 null·blank 원소와 잘못된 타입은 거절합니다.

회사·조직의 중첩 구조는 기존 `ExternalUserSnapshot` 계약을 재사용합니다. 중첩 객체의 허용되지 않은 필드나 잘못된 타입을 조용히 제거하지 않고 거절합니다. 선택하지 않은 최상위 OIDC claim은 저장하지 않습니다. 선택 정보의 부재를 ID Token의 동명 claim으로 채우지 않습니다.

HR 역할은 외부 사본입니다. 최초 관리자 지정 이후 이를 로컬 권한으로 지속 동기화하지 않습니다. 인가에는 DB의 `APP_USER`·`APP_ADMIN`만 사용하며 scope나 외부 역할로 앱 권한을 얻을 수 없습니다.

## 요청별 현재 사용자 확인

인증된 `/bff/**` 요청은 인가 전에 로컬 UUID로 현재 사용자와 역할을 조회합니다. 잠금 없는 읽기 전용 조회를 추가하고 bootstrap/JIT 저장 경로를 재사용하지 않습니다. 한 요청의 인가와 응답은 같은 조회 결과를 사용하며 컨트롤러에서 다시 조회하지 않습니다.

ACTIVE이면 현재 로컬 역할을 요청의 인증 권한에 반영합니다. 공유 HttpSession의 기존 인증 객체를 직접 수정하지 않고 요청용 인증 객체를 만들어 동시 요청 간 변경을 피합니다. 다음 요청도 반드시 DB를 다시 조회하므로 세션에 남은 과거 권한을 인가의 근거로 삼지 않습니다.

DISABLED·삭제 사용자 또는 로컬 연결이 없는 이전 Task 4 세션은 SecurityContext·세션·쿠키를 정리하고 익명으로 취급합니다. `/bff/session`은 200 익명 응답, `/bff/profile`은 401입니다. 세션 폐기는 해당 요청에서 관찰한 사용자 상태에 따르며, 이미 진행 중인 다른 요청까지 소급 취소하지 않습니다. 변경이 커밋된 뒤 조회하는 다음 요청부터 반영합니다.

요청별 조회는 IdP 호출, 토큰 refresh, 외부 사본 갱신, `lastLoginAt` 변경을 수행하지 않습니다. DB 장애 시 기존 세션 권한으로 요청을 통과시키지 않고 일반 서버 오류로 종료합니다. DB 장애를 사용자 삭제나 비활성 상태로 오인하지 않습니다.

Task 4의 CSRF·Origin 검사는 유지합니다. unsafe 요청의 CSRF·Origin 실패가 먼저 403으로 끝날 수 있으며 위 익명 응답 계약은 GET API 기준입니다. 향후 관리자 API는 이번에 제공하는 최신 권한을 사용하며, 실제 관리 동작과 마지막 관리자 보호는 Task 7 책임입니다.

## API 계약

두 API와 기존 `/bff/csrf`에는 `Cache-Control: no-store`를 적용합니다. 오류 응답에도 토큰·원본 claims·예외 내용을 포함하지 않습니다.

### GET `/bff/session`

익명 응답은 HTTP 200과 다음 본문입니다. 익명 조회를 위해 사용자나 새 인증 세션을 만들지 않습니다.

```json
{"authenticated": false}
```

인증 상태의 HTTP 200 응답은 다음 필드로 제한합니다. `id`는 로컬 UUID, `status`는 ACTIVE, `roles`는 현재 로컬 역할입니다. 역할 배열은 결정적 순서로 반환합니다. 이름과 이메일은 null을 허용합니다.

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

CSRF 값은 기존 Spring 요청 속성의 마스킹된 토큰을 사용합니다. 저장된 원본 토큰을 직접 읽거나 별도 CSRF 저장소를 만들지 않습니다. 익명 클라이언트가 CSRF 토큰을 필요로 하면 기존 `/bff/csrf`를 호출합니다.

### GET `/bff/profile`

익명은 redirect 없이 HTTP 401입니다. 인증된 사용자의 HTTP 200 응답은 `displayName`, `email`, `company`, `organization`, `hrRoles`, `roles`로 제한합니다. 회사·조직은 기존 외부 사본의 공개 구조와 null을 유지하고 HR 역할·로컬 역할은 배열로 반환합니다. 두 역할은 별도 필드로 구분하며 DB에 저장된 최신 사본을 사용합니다.

두 API 모두 issuer·subject·DB version·저장 시각·원본 principal·OAuth 토큰·client secret을 응답 DTO에 포함하지 않습니다. OAuth 토큰과 달리 CSRF 토큰은 화면이 헤더로 전송하기 위해 의도적으로 노출하는 값입니다.

## 실패 처리

| 상황 | 결과 |
|---|---|
| 콜백에서 로컬 DISABLED 확인 | 고정 SPA `/login-error?code=local_user_disabled` |
| 프로토콜 검증, UserInfo 불일치·변환, 로그인 DB 처리 등 그 밖의 로그인 실패 | 고정 SPA `/login-error?code=oidc_login_failed` |
| 기존 세션 사용자가 DISABLED·삭제 상태 | 세션 폐기 후 session API는 익명 200, profile API는 401 |
| 인증은 유효하지만 필요한 로컬 권한 없음 | 403 |

로그인 실패는 기존 공통 정리 경로를 확장해 SecurityContext를 비우고 기존 HttpSession을 invalidate하며 RP_SESSION 쿠키를 만료시킵니다. 실패 저장을 위해 새 세션을 만들지 않습니다. 허용된 두 오류 코드만 내부에서 선택하며 요청 값이나 외부 `error_description`으로 redirect를 구성하지 않습니다.

토큰·secret·원본 UserInfo·민감한 프로토콜 값을 로그나 오류 응답으로 노출하지 않습니다. 원격 token revocation은 Task 6까지 추가하지 않습니다. 상위 계획의 공개 `subject_mismatch` 코드는 일반 오류로 대체합니다.

## 컴포넌트 책임

| 구성 요소 | 책임 |
|---|---|
| OIDC 사용자 처리 확장 | 표준 처리 위임, 검증된 identity/UserInfo 연결, 로컬 로그인 실패의 인증 오류 변환 |
| 외부 정보 변환기 | 허용 claim 추출·타입 검증·`ExternalIdentityProfile` 생성 |
| 로컬 로그인 조율 | 기존 provisioning과 ACTIVE 확인을 같은 트랜잭션으로 실행 |
| 로컬 정보가 연결된 OIDC principal | OIDC 계약·인증 이름 유지, 로컬 UUID·역할 제공 |
| 현재 사용자 조회 서비스와 요청 필터 | 읽기 전용 DB 조회, 현재 역할 반영, 폐기 대상 세션 정리 |
| Session/Profile 컨트롤러와 DTO | 요청의 검증된 로컬 사용자 결과를 최소 응답으로 변환 |
| 공통 실패 정리 | 두 고정 오류 안내와 세션·쿠키 정리 |

파일명·세부 메서드·필터 순서는 구현 계획에서 확정하되 책임과 외부 계약은 이 문서를 따릅니다. 관련 없는 리팩터링은 포함하지 않습니다.

## 검증 기준

실제 Spring Security 필터 체인, 테스트용 OIDC HTTP 서버의 서명된 ID Token·UserInfo, PostgreSQL Testcontainers를 연결합니다. 단순 인증 주입 테스트만으로 실제 콜백의 성공을 주장하지 않습니다.

1. 정상 콜백에서 UserInfo를 한 번 조회하고 사용자·APP_USER 생성, 세션 ID 교체, session/profile 조회가 성공합니다.
2. 같은 identity 재로그인은 사용자 UUID를 유지하고 외부 사본을 갱신하며 로컬 역할을 보존합니다. 적격 최초 사용자만 APP_ADMIN이 되는 기존 정책을 연결합니다.
3. UserInfo 부재·sub 누락·불일치·잘못된 허용 claim은 로컬 provisioning 전에 거절하며 DB 사용자·bootstrap이 변하지 않습니다.
4. DISABLED 사용자의 로그인은 `local_user_disabled`로 거절하며 기존 사용자 사본·시각·역할과 bootstrap이 그대로입니다.
5. 저장 실패에서 JIT·bootstrap이 함께 롤백되고 인증 세션이 남지 않습니다. 프로토콜·변환 오류는 일반 로그인 오류로 처리합니다.
6. 실패 뒤 기존 쿠키로 보호 API에 접근할 수 없고 pending 요청·authorized client·인증 정보가 남지 않으며 실패용 새 세션을 만들지 않습니다.
7. 로그인 후 DB에서 사용자를 비활성화·삭제하면 다음 요청에서 세션이 폐기되고 API별 익명 계약이 적용됩니다.
8. 역할 부여·회수 후 다음 요청의 응답과 테스트 전용 권한 보호 endpoint에 최신 역할이 반영됩니다. 테스트용 관리 endpoint는 production에 추가하지 않습니다.
9. 기존 Task 4의 로컬 연결 없는 세션은 앱 인증으로 받아들이지 않습니다. 외부 HR 역할·scope만으로 로컬 관리자 접근을 얻을 수 없습니다.
10. 요청별 조회에는 UserInfo 호출·JIT 저장이 없고 같은 요청의 조회 결과를 재사용합니다. DB 조회 장애에서 과거 권한으로 보호 endpoint를 실행하지 않습니다.
11. session/profile 응답의 정확한 허용 필드와 no-store, 익명 200/401, 권한 부족 403을 검증합니다. 비밀 필드 이름뿐 아니라 fixture의 실제 OAuth 토큰·secret 값이 body/header/redirect에 없는지도 확인합니다.
12. 실제 CSRF 응답을 헤더로 사용해 로그인 전 토큰 폐기와 현재 토큰 동작을 확인하며, Task 4의 Origin·callback·PKCE·세션 보안 및 기존 JIT·bootstrap 회귀를 통과시킵니다.

Reference App backend 전체 테스트와 `git diff --check` 결과를 기록합니다. 실제 IdP·Chromium E2E는 Task 10 범위로 유지하며 이번 검증 결과로 대체했다고 표현하지 않습니다.

## 다음 단계

문서 자체 검토 후 사용자에게 이 파일의 검토를 요청합니다. 승인되면 Superpowers writing-plans로 구현 계획을 작성합니다. 이번 문서 커밋에는 구현·테스트 실행 결과·원격 push를 포함하지 않습니다.
