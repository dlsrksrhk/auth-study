# Reference App 사용자 관리 API 설계

작성일: 2026-09-10. 상위 작업: `2026-08-21-oauth-oidc-reference-app.md`의 Task 7.

## 목적과 승인 범위

Reference App의 로컬 APP_ADMIN이 앱 사용자를 조회하고 상태·역할을 변경합니다. 사용자 생성은 기존 OIDC 최초 로그인 JIT를 유지합니다. 사용자 수동 생성·삭제, HR 정보 편집, SPA, 별도 감사 로그 시스템은 이번 범위에 포함하지 않습니다. Task 9의 관리자 SPA가 이 API를 사용합니다.

대화에서 기능 범위, 공통 DB 잠금과 version 검사, 자기 자신 변경 허용 조건, API 계약, 오류·검증 기준을 승인받았습니다. 이 문서는 그 결정을 구체화한 검토본이며 구현은 별도 계획 승인 이후 진행합니다.

## 기존 구현과 연결

- AppUser는 UUID, issuer/subject, 외부 snapshot, ACTIVE/DISABLED, APP_USER/APP_ADMIN, 시각과 JPA version을 가집니다.
- APP_USER는 모든 사용자에게 필수입니다. HR roles는 외부 사본이며 APP_ADMIN 인가에 사용하지 않습니다.
- CurrentAppUserFilter가 요청별 현재 사용자와 역할을 읽습니다. 토큰 갱신 필터도 갱신 후 로컬 사용자를 다시 확인합니다.
- AppLoginProvisioningService는 bootstrap singleton을 먼저 잠근 뒤 사용자 행을 잠가 JIT와 최초 관리자 지정을 처리합니다.
- 토큰 갱신의 snapshot 저장은 사용자 행만 잠그며 bootstrap 잠금을 나중에 요청하지 않습니다.
- 기존 사용자 version을 그대로 사용합니다. 로그인·외부 사본 갱신으로 version이 바뀌어도 오래된 관리자 수정은 충돌로 처리하며 별도 adminVersion은 만들지 않습니다.

## 책임 분리

- 관리자 controller/DTO: 경로·입력 검증, 현재 인증에서 actor UUID 추출, 공개 응답 변환.
- 관리자 application service: 조회, 트랜잭션 내 actor 재인가, version·마지막 관리자 검사, 변경 조율.
- domain: 상태·역할 불변식과 변경값 계산. 외부 snapshot·로그인 시각 보존.
- repository port/adapter: 페이지 조회, UUID 기반 잠금 조회, 활성 관리자 집계, 상태·역할 저장과 flush.
- 관리자 API 오류 처리: Reference App 안에서 Problem Details 계약을 구현합니다. IdP의 오류 표현을 참고하되 IdP 코드·패키지·DB에 의존하지 않습니다.

## 인가와 공통 잠금

`/bff/admin/**`는 인증된 로컬 APP_ADMIN만 접근합니다. 기존 CSRF·Origin 검사와 로그인 세대 보호를 유지합니다. actor는 요청 JSON이나 임의 헤더로 받지 않습니다.

변경 트랜잭션은 READ_COMMITTED이며 다음 순서를 따릅니다.

1. 기존 bootstrap singleton 행에 공통 배타 DB 잠금을 획득합니다. 상태·역할 변경과 최초 관리자 지정이 같은 잠금을 공유하며 bootstrap 기록 자체는 변경하지 않습니다.
2. actor를 DB에서 새로 조회하여 ACTIVE APP_ADMIN인지 확인합니다. 필터가 조회한 객체나 영속성 컨텍스트의 오래된 값으로 판단하지 않습니다.
3. 대상 사용자 행을 UUID로 잠그고 최신 상태·역할·version을 읽습니다. actor와 대상이 같아도 중복된 상태를 만들지 않습니다.
4. 요청 version을 현재 version과 비교합니다.
5. 변경 결과를 계산하고 마지막 활성 관리자 보호를 검사합니다.
6. 실제 변경이면 저장·flush한 후 새 version을 포함한 상세 응답을 생성합니다. 응답은 트랜잭션 커밋 성공 후 전달합니다.

actor의 상태·역할을 변경할 수 있는 모든 운영 경로는 공통 잠금을 따릅니다. 따라서 잠금 안에서 actor 권한이 다시 회수되는 경합이 발생하지 않습니다. 사용자 행을 먼저 잡은 뒤 공통 잠금을 요청하는 역순 경로는 추가하지 않습니다. 네트워크·UserInfo·토큰 폐기는 관리자 DB 트랜잭션 안에서 호출하지 않습니다.

공통 잠금은 관리자 변경뿐 아니라 기존 로그인과도 경합하므로 짧게 유지합니다. 관리자 변경 트랜잭션의 PostgreSQL lock_timeout은 3초, statement_timeout은 5초, Spring 트랜잭션 timeout은 10초로 제한합니다. DB 설정은 SET LOCAL 등 해당 트랜잭션에만 적용하여 풀의 다음 연결 사용자에게 전파하지 않습니다. 자동 재시도는 하지 않습니다.

## 상태·역할 불변식

- 허용 상태는 ACTIVE, DISABLED입니다.
- 역할 전체 집합은 APP_USER 또는 APP_USER+APP_ADMIN입니다. APP_USER 누락, 빈 집합, 알 수 없는 값은 거절합니다.
- 마지막 ACTIVE APP_ADMIN을 비활성화하거나 APP_ADMIN에서 제외할 수 없습니다.
- 자기 자신의 비활성화·권한 회수는 다른 ACTIVE APP_ADMIN이 남는 경우 허용합니다.
- DISABLED 사용자의 APP_ADMIN 부여·회수도 허용하지만 그 사용자는 활성 관리자 수에 포함하지 않습니다.
- 활성 관리자 수를 감소시키는 실제 변경에는 변경 후 최소 1명 조건을 적용합니다. 최초 bootstrap 전 0명 상태를 관리자 API로 복구하거나 bootstrap 완료 기록을 초기화하지 않습니다.
- 같은 값 요청은 version 일치 확인 후 성공하며 쓰기·updatedAt·version 변경을 하지 않습니다. 같은 값이어도 오래된 version은 409입니다.
- 실제 변경 시 updatedAt을 갱신하고 JPA version을 증가시킵니다. 역할만 바뀌어도 version이 증가해야 합니다. createdAt, lastLoginAt, issuer/subject, 외부 snapshot은 보존합니다.
- 비활성화는 다음 요청의 기존 사용자 검사에서 세션을 종료합니다. 권한 회수도 다음 요청부터 적용합니다. 이미 실행 중인 업무의 소급 취소나 모든 세션에 대한 즉시 원격 폐기는 보장하지 않습니다.

## HTTP 계약

| 메서드·경로 | 입력 | 성공 |
|---|---|---|
| GET /bff/admin/users | page, size, status, role | 200 목록 |
| GET /bff/admin/users/{userId} | UUID | 200 상세 |
| PUT /bff/admin/users/{userId}/status | status, version | 200 변경 후 상세 |
| PUT /bff/admin/users/{userId}/roles | roles, version | 200 변경 후 상세 |

page는 0 이상, 기본 0입니다. size는 1~100, 기본 20입니다. 범위를 벗어나면 잘라내지 않고 400으로 거절합니다. status와 role은 선택적 단일 enum 필터이며 함께 있으면 AND로 적용합니다. 빈 값·알 수 없는 값은 400입니다. 정렬은 createdAt DESC, id ASC로 고정합니다. 사용자 선택 정렬·이름 검색은 추가하지 않습니다. offset 계산은 overflow 없이 처리하며 전체 범위 밖 페이지는 빈 목록입니다.

목록 응답은 `items`, `page`, `size`, `totalElements`, `totalPages`입니다. 각 항목은 `id`, `displayName`, `email`, `status`, `roles`, `lastLoginAt`, `version`입니다. 역할 필터 JOIN으로 사용자나 합계가 중복되지 않아야 합니다. 목록과 count는 읽기 전용 REPEATABLE_READ 트랜잭션의 같은 조회 snapshot에서 산출하며, 여러 페이지를 이동하는 동안의 변경까지 고정하지는 않습니다. 상세 조회는 읽기 전용 트랜잭션으로 수행합니다. 조회에는 공통 배타 잠금을 사용하지 않습니다.

상세는 목록 항목에 `createdAt`, `updatedAt`, `externalIdentity: {issuer, subject}`, `company`, `organization`, `hrRoles`를 추가합니다. 외부 정보는 기존 공개 snapshot 구조를 사용하고 없는 name/email/company/organization은 null을 유지합니다. 시각은 UTC ISO-8601 문자열, version은 0 이상의 정수, 역할은 중복 없는 문자열 배열입니다. APP_USER, APP_ADMIN 순으로 응답을 안정화합니다. OAuth 토큰, client secret, 원본 인증 객체, IdP 내부 DB ID는 응답하지 않습니다.

상태 요청 예:

```json
{"status":"DISABLED","version":3}
```

역할 요청 예:

```json
{"roles":["APP_USER","APP_ADMIN"],"version":3}
```

version은 필수이며 음수·null·소수·문자열은 400입니다. enum과 roles도 JSON 자료형을 엄격히 검사합니다. 동일 역할 중복은 집합으로 정규화하되 허용되지 않는 값이나 APP_USER 누락을 보정하지 않습니다. 쓰기 요청은 application/json을 사용합니다. 성공·오류 응답에는 Cache-Control: no-store를 적용합니다.

## 오류 계약과 우선순위

관리자 MVC 계층 오류는 application/problem+json으로 `type`, `title`, `status`, `detail`, `instance`, `code`를 반환합니다. type은 about:blank, detail은 고정된 안전한 설명, instance는 query 없는 요청 경로입니다. SQL·예외 원문·내부 경로·비밀값은 노출하지 않습니다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | 형식·자료형·범위 오류 |
| 403 | FORBIDDEN | 트랜잭션 안에서 actor 권한 상실 확인 |
| 404 | APP_USER_NOT_FOUND | 대상 사용자 없음 |
| 409 | OPTIMISTIC_LOCK_CONFLICT | 요청 version 불일치 또는 실제 낙관적 잠금 충돌 |
| 409 | LAST_ACTIVE_ADMIN_REQUIRED | 마지막 활성 관리자 제거 |
| 503 | SERVICE_UNAVAILABLE | DB 장애, 잠금 대기·트랜잭션 시간 제한 |

보안 필터가 먼저 반환하는 익명/만료 401, 인가·CSRF·Origin 403, 기존 로컬 조회/갱신의 실패 응답은 기존 계약을 유지합니다. 이 계층의 응답이 모두 Problem Details 본문을 가진다고 보장하지 않으며 SPA는 HTTP 상태만으로도 처리할 수 있어야 합니다. 잘못된 Content-Type/메서드는 표준 415/405입니다.

입력 검증 이후 서비스 판정 순서는 actor 인가 → 대상 존재 → version → 마지막 관리자 보호입니다. 잠금 대기 중 actor가 비활성화·강등된 경우 403이며 대상 변경은 수행하지 않습니다. 이미 요청 입구에서 비활성화가 확인되면 기존 필터 정책에 따라 세션이 종료되고 401이 됩니다. DB 장애를 404/409로 숨기지 않습니다. 실패 시 상태·역할·version 변경 전체가 롤백됩니다.

## 검증 기준

1. 실제 PostgreSQL에서 목록·필터·정렬·페이지·합계·빈 결과 및 잘못된 입력을 검증합니다.
2. 상태/역할 변경, 같은 값 재요청, 오래된 version, 필드 보존, 역할만 변경할 때의 version 증가를 검증합니다.
3. 마지막 관리자와 자기 자신 변경, DISABLED 관리자 집계 제외를 검증합니다.
4. 두 관리자의 동시 비활성화·강등에서도 0명이 되지 않음을 별도 트랜잭션·연결과 gate로 검증합니다. 권한을 잃은 actor의 403과 마지막 관리자 보호 409는 각각 독립 사례로 검증합니다.
5. 공통 잠금 대기 중 actor 권한 회수 후 쓰기가 차단되는 것을 검증합니다. 부여·활성화와 회수·비활성화의 동시 변경도 보호 조건을 유지합니다.
6. lock timeout과 저장 실패의 503 및 전체 롤백을 실제 DB에서 검증합니다. 임의 sleep이나 mock 호출 횟수만으로 DB 경쟁을 입증하지 않습니다.
7. 실제 HTTP에서 APP_USER 403, 익명 401, CSRF·Origin 거절, 응답 정보 제한을 검증합니다. 비활성화·권한 변경 후 다음 요청의 세션/인가 결과를 확인합니다.
8. 최초 bootstrap·JIT 로그인·외부 snapshot refresh와의 동시 실행을 검증하여 잠금 순서 역전과 역할 덮어쓰기가 없음을 확인합니다.
9. 기존 재로그인·단일 갱신·로그아웃 회귀를 포함한 Reference App 전체 테스트를 실행합니다. IdP에 변경이 없으면 그 전체 suite를 반복 실행할 필요는 없습니다.

## 완료 기준과 후속 작업

4개 API, DB 불변식, 현재 actor 재인가, 오류 처리, 동시성/HTTP 회귀가 구현되고 검토를 통과해야 Task 7이 완료됩니다. 새 DB 테이블은 예정하지 않으며 기존 bootstrap singleton을 재사용합니다. 저장소 port/entity 변경은 필요합니다.

구현 계획에서 상위 Task 7의 활성 관리자 행 전체 잠금 방식은 이 문서의 공통 잠금 방식으로 대체합니다. 상위 문서의 옛 principal 명칭은 실제 AppOidcUser/OAuth2AuthenticationToken과 현재 로컬 사용자 조회 구조에 맞춥니다. README와 검증 보고서에 새 API, DB 잠금으로 로그인과 경합할 수 있는 점, 실제 테스트 결과를 기록합니다.

Task 8의 사용자용 SPA, Task 9의 관리자 SPA, Task 10의 실제 브라우저 E2E는 후속 작업입니다. 이 설계 승인은 구현 및 원격 push 완료를 뜻하지 않습니다.
