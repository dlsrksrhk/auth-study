# Reference App Task 6: 토큰 갱신과 로그아웃 설계

작성일: 2026-09-10

상태: 승인된 [구현 계획](../plans/2026-09-10-reference-app-token-lifecycle.md)의 Tasks 1–6 구현 및 HTTP·PostgreSQL 회귀 검증을 완료했습니다. Reference App 283개, IdP 486개 테스트 결과와 실제 브라우저 E2E 미실행 범위는 [검증 보고서](../reports/2026-09-10-reference-app-token-lifecycle-verification.md)에 기록했습니다.

## 목적과 범위

기반 커밋은 Task 5가 병합된 `42d0858`입니다. 인증된 BFF 요청에서 만료가 임박한 토큰을 갱신하고, 앱 로그아웃과 IdP까지 포함하는 전체 로그아웃을 제공합니다. 동시에 실행되는 갱신과 로그아웃이 세션을 되살리거나 일회용 Refresh Token을 중복 소비하지 않도록 합니다.

상위 문서는 [Reference App 전체 계획](../plans/2026-08-21-oauth-oidc-reference-app.md)과 [Task 5 설계](2026-09-09-reference-app-local-login-design.md)입니다. Task 6 세부 사항이 충돌하면 이 문서를 우선합니다. 기존 계획의 전체 로그아웃 POST 직접 `303`은 아래의 `200 continueUrl → GET → 303` 방식으로 대체합니다.

포함 범위는 BFF 토큰 갱신, 외부 사용자 사본 갱신, 세션별 동시 실행 조율, Refresh Token 폐기, 두 로그아웃 API, 일회용 전달 정보, IdP의 로그아웃 전용 만료 ID Token 검증, 관련 테스트와 사용 문서입니다. 관리자 API, SPA 화면과 실제 IdP를 통한 브라우저 E2E는 Task 7–10의 범위입니다. 분산 세션·Redis·백그라운드 정기 갱신·DB 스키마 변경은 포함하지 않습니다.

## 선택한 구조와 책임

| 접근 | 장점 | 결정 |
|---|---|---|
| Spring 갱신 지원과 기존 세션 저장소 재사용, 세션별 조율 추가 | 기존 OAuth 계약을 유지하면서 경쟁 조건을 통제합니다 | 채택합니다 |
| 토큰 HTTP 프로토콜과 저장소 전체 직접 구현 | 모든 단계를 직접 제어합니다 | 기존 기능 중복과 검증 부담 때문에 제외합니다 |
| 백그라운드 주기 갱신 | 요청 시 갱신 지연이 줄어듭니다 | 사용하지 않는 세션까지 갱신하므로 제외합니다 |

`HttpSessionOAuth2AuthorizedClientRepository`를 정상 토큰 저장소로 유지합니다. 프로토콜 principal 이름과 registration ID를 변경하지 않습니다. 새 토큰은 UserInfo와 로컬 사용자 검증을 마친 후에만 저장합니다. 자동 authorized-client 저장이나 프레임워크 내부 재시도가 이 순서를 우회하지 않도록 구현 계획에서 실제 dependency API를 확인합니다.

책임 경계는 다음과 같습니다. 구체적인 클래스명과 메서드 시그니처는 구현 계획에서 확정합니다.

- 요청 필터: 갱신 대상 판단, 현재 로컬 사용자 검증과 갱신 순서 연결, 결과를 요청 인증과 명시적 DTO 입력에 반영합니다.
- 토큰 서비스: Spring의 Refresh Token 교환 기능, UserInfo 조회·검증, 갱신 결과 조립을 담당합니다.
- 세션 조율자: 같은 세션의 단일 갱신 작업과 종료 상태, 결과 게시를 관리합니다. 장기 토큰 저장소를 별도로 만들지 않습니다.
- 외부 사본 갱신 서비스: 기존 사용자의 identity·ACTIVE 상태를 확인하고 외부 사본만 트랜잭션 안에서 갱신합니다.
- 폐기 클라이언트: 고정 IdP revocation endpoint에 서버의 client 인증으로 Refresh Token 폐기를 한 번 시도합니다.
- 로그아웃 서비스·컨트롤러: 종료 선점, 로컬 정리, 폐기 요청과 전체 로그아웃 전달 정보를 조율합니다.
- 전달 정보 저장소: 최대 60초 동안 전체 로그아웃용 정보를 서버 메모리에 보관하고 원자적으로 한 번 소비합니다.
- IdP 로그아웃 전용 검증기: 현재 IdP 세션과 결합된 ID Token에 한해서 만료를 허용합니다.

## 갱신 대상과 정상 흐름

대상은 인증된 `/bff/session`, `/bff/profile` 및 보호 BFF API 요청입니다. Access Token의 만료까지 남은 시간이 30초 이하이면 갱신합니다. 만료 시각이 없거나 필요한 authorized client·Refresh Token이 없어서 유효성을 확보할 수 없으면 세션을 종료합니다. 로그인 진입, callback, CSRF 발급, 두 로그아웃 POST와 로그아웃 continuation GET에서는 갱신하지 않습니다.

1. 기존 `CurrentAppUserFilter`의 로컬 사용자 존재·ACTIVE·identity 검증을 먼저 수행합니다. 이 단계의 DB 장애는 기존처럼 세션을 유지한 `503`입니다.
2. 기존 서버 세션을 확인하고 같은 세션의 조율 상태에 참여합니다. 이미 진행 중인 갱신이 있으면 그 결과를 기다립니다. 소유자는 저장소의 최신 토큰을 다시 읽고 갱신 필요 여부를 재확인합니다.
3. 네트워크 호출 동안 조율 잠금과 DB 트랜잭션을 잡지 않고 Refresh Token 교환을 한 번 수행합니다.
4. 새 Access Token으로 UserInfo를 한 번 조회합니다. 원본 `sub`가 로그인 시 검증된 ID Token의 `sub`와 정확히 같아야 하며, Task 5의 엄격한 mapper로 허용된 외부 필드를 검증합니다. issuer·subject를 정규화하거나 ID Token의 선택 필드로 보충하지 않습니다.
5. 기존 사용자 UUID와 identity, 현재 ACTIVE 상태를 트랜잭션 안에서 다시 확인하고 외부 사본을 갱신합니다. 새로운 사용자를 생성하거나 bootstrap을 실행하지 않습니다. 로컬 roles·status·createdAt·lastLoginAt은 보존하고 updatedAt은 갱신합니다. 기존 로그인용 `replaceSnapshot`이 lastLoginAt도 바꾸므로 갱신 전용 동작을 구분합니다.
6. 종료 여부를 다시 확인한 뒤, 종료 선점과 같은 조율 경계에서 기존 세션에만 새 authorized client와 필요한 principal 정보를 게시합니다. 무효화된 세션을 새로 만들거나 이전 세션의 결과를 새 로그인 세션에 적용하지 않습니다.
7. 대기 요청은 게시된 결과를 공유하고 각 요청의 현재 로컬 상태·권한을 유지하여 원래 요청을 계속합니다. 이전 요청의 권한 사본으로 더 최신의 로컬 권한을 덮어쓰지 않습니다.

IdP의 현재 Refresh Token 응답은 새 Access/Refresh Token만 반환합니다. 원래 검증된 ID Token을 전체 로그아웃용으로 유지하며, 만료된 원래 ID Token을 새로운 로그인 자격으로 재사용하지 않습니다. 외부 사본 갱신의 DB 커밋과 세션 게시는 분산 트랜잭션이 아닙니다. 커밋 직후 로그아웃이 이기면 외부 사본 갱신은 남을 수 있지만 토큰과 인증 세션은 복구하지 않습니다.

## 동시 실행과 실패 정책

조율 상태는 서버 세션의 수명에 귀속됩니다. 세션 ID 회전·무효화·만료와 정합성을 유지하고, 요청마다 새 조율 객체를 만들어 진행 중인 작업을 놓치지 않습니다. 종료된 세션의 진행 중 작업은 종료 표시를 계속 관찰하며 완료 후 참조를 해제합니다.

| 상태 | 요청 처리 |
|---|---|
| 열림, 갱신 불필요 | 원래 요청을 진행합니다 |
| 열림, 갱신 필요 | 한 요청만 갱신을 소유합니다 |
| 갱신 중 | 동일 작업의 성공·실패를 공유합니다 |
| 종료 중·종료됨 | 새 갱신과 결과 저장을 금지합니다 |

`invalid_grant`, `invalid_token`, token/UserInfo 오류 응답, 연결·시간 초과, 잘못된 응답, UserInfo 검증 실패, 갱신 이후 DB 저장 실패는 모두 RP 세션 종료로 처리합니다. 자동 재시도는 하지 않습니다. 회전형 Refresh Token은 응답을 받지 못했어도 이미 소비되었을 수 있기 때문입니다. 대기 요청의 시간 초과나 취소도 두 번째 갱신을 시작할 근거가 되지 않습니다.

새 토큰을 받은 뒤 검증·저장이 실패하면 새 Refresh Token도 폐기를 시도합니다. 사용할 수 있는 기존 Refresh Token 역시 종료 정리 대상으로 취급하되, 폐기 요청을 자동 재시도하지 않습니다. 폐기 결과를 모르는 경우에도 세션 종료를 취소하지 않습니다. 원격 예외·토큰·claim 원문을 응답이나 로그에 노출하지 않습니다.

초기 구현의 네트워크 기본값은 연결 2초, 응답 3초, 전체 갱신 작업 대기 상한 10초로 정합니다. revocation은 호출당 전체 3초 이내로 제한합니다. 시간 제한은 테스트 가능한 설정으로 제공하며, 작업 상한 이후 도착한 결과도 종료 여부를 확인하고 폐기·폐기 시도 후 버립니다. 제한을 적용한 HTTP 구현과 작업 수명 관리는 구현 계획에서 검증합니다.

이미 업무 로직에 진입한 다른 요청의 DB 작업을 로그아웃으로 취소하거나 롤백한다는 보장은 하지 않습니다. 이 설계가 보장하는 것은 종료 이후 새 갱신과 인증 세션 복구의 방지입니다.

## 앱 로그아웃

`POST /bff/logout`은 기존 CSRF 헤더와 Origin 검사를 통과해야 합니다.

1. 조율 상태를 즉시 종료 중으로 표시하고 필요한 토큰 참조를 확보합니다.
2. SecurityContext와 서버 세션·authorized client를 정리하고 `RP_SESSION`을 만료시킵니다. 기존 `RpSessionCleaner`의 커밋된 응답 처리와 다른 쿠키 보존 동작을 유지합니다.
3. 보유 Refresh Token 폐기를 제한 시간 안에서 한 번 시도합니다. 실패·지연 때문에 로컬 정리를 미루거나 취소하지 않습니다.
4. `204`와 `Cache-Control: no-store`를 반환합니다. IdP 브라우저 세션은 유지됩니다.

로그아웃 중에 기존 갱신이 새 Refresh Token을 얻더라도 세션에 저장할 수 없습니다. 해당 작업이 후속 토큰의 폐기를 제한 시간 안에서 시도하고 참조를 버립니다. 로그아웃 응답은 진행 중인 갱신의 완료를 무기한 기다리지 않습니다.

## 전체 로그아웃과 일회용 전달 정보

헤더 전용 CSRF 방식에서 SPA의 fetch 응답 redirect만으로는 최상위 IdP 이동이 완료되지 않으므로 두 단계로 나눕니다.

1. SPA가 CSRF 헤더를 붙여 `POST /bff/logout/identity-provider`를 호출합니다.
2. BFF가 원래 ID Token과 등록된 client ID, 고정 end-session·post-logout URI를 확보하고 종료를 선점합니다. 일회용 전달 정보를 생성하고 앱 로그아웃과 동일하게 로컬 정리와 제한된 폐기 시도를 수행합니다.
3. `200 {"continueUrl":"<고정 BFF origin>/bff/logout/continue/<ticket>"}`를 반환합니다. JSON에는 OAuth 토큰이 없습니다.
4. SPA는 `continueUrl`로 최상위 탐색합니다. 이 GET은 RP 세션·로그인·갱신이 필요 없는 공개 endpoint입니다.
5. BFF는 유효한 ticket을 원자적으로 소비하고 고정 IdP end-session endpoint로 `303`을 반환합니다. 쿼리는 `id_token_hint`, `client_id`, 등록된 고정 `post_logout_redirect_uri`로 구성합니다. 임의 redirect 입력은 받지 않으며 별도 callback 처리가 필요 없는 이 흐름에서는 선택적 `state`를 보내지 않습니다.
6. IdP가 현재 브라우저 세션을 검증·종료한 뒤 등록된 SPA `/logged-out`으로 이동합니다. IdP 거절·장애 시에도 이미 종료한 RP 세션은 복구하지 않습니다.

전달 정보는 예측 불가능한 256비트 난수 ticket으로 찾으며 생성 후 최대 60초만 유효합니다. 소비·만료 시 삭제하고, 정기 만료 정리와 최대 항목 수 1,000개의 용량 제한을 둡니다. 재시작 시 소실되어도 안전하게 `410`으로 끝납니다. 세션 종료 뒤 ID Token을 서버 메모리에 잠시 보관하는 이 예외는 전체 로그아웃 전달에만 적용합니다. Access/Refresh Token·client secret은 전달 정보에 넣지 않습니다.

전달 정보 생성에 실패하거나 필요한 ID Token이 없으면 로컬 로그아웃은 완료하고 `503`의 고정 오류 코드 `logout_continuation_unavailable`를 반환합니다. 잘못된·만료된·이미 소비된 ticket은 모두 `410`이며 IdP로 redirect하지 않습니다. ticket 경로와 Location의 토큰 값은 애플리케이션 로그에 기록하지 않습니다.

전체 로그아웃 redirect의 `id_token_hint`는 ID Token이 브라우저 URL에 노출되는 명시적으로 승인된 예외입니다. 일반 API 응답, SPA 상태·스토리지에는 ID Token을 넣지 않습니다. 관련 응답에는 `no-store`, `Referrer-Policy: no-referrer`를 적용합니다. URL은 브라우저 기록이나 인프라 로그에 남을 수 있으므로 배포 시 해당 경로·Location의 값이 로그에 기록되지 않도록 사용 문서에 적습니다.

## IdP의 만료 ID Token 처리

현재 IdP refresh 응답에는 새 ID Token이 없고, logout은 일반 JWT decoder를 사용해 만료된 ID Token을 거절합니다. 이를 해결하기 위해 로그아웃 endpoint에만 별도 검증 경로를 연결합니다. 일반 로그인·API JWT decoder의 만료 검증은 변경하지 않습니다.

허용하는 예외는 `exp`가 지난 ID Token이 현재 IdP 브라우저 세션과 일치하는 경우뿐입니다. 서명·허용 알고리즘·issuer, 단일 audience와 client_id 일치, 활성 client, sub, sid, auth_time, 회사 일치, 등록된 post-logout URI 검증은 유지합니다. 필수 시간 claim의 존재·형식·순서와 아직 유효하지 않은 토큰의 거절도 유지합니다. 만료 허용을 위해 시간 검증 전체를 무조건 제거하지 않습니다. 현재 세션이 없거나 다른 세션이면 만료 여부와 관계없이 거절합니다.

이 범위는 [OpenID Connect RP-Initiated Logout 1.0](https://openid.net/specs/openid-connect-rpinitiated-1_0.html#RPLogout)의 현재 또는 최근 OP 세션과 연결된 만료 ID Token 수용 권고 중 현재 세션에 한정합니다. 오래된 서명 키 보존 정책 확대나 세션 없는 로그아웃 확인 화면은 포함하지 않습니다. 검증 가능한 서명 키가 없는 토큰은 거절합니다.

## 응답 계약

| 상황 | 결과 |
|---|---|
| 갱신 성공 | 원래 요청 진행, 기존 API DTO 계약 유지 |
| 갱신 실패 후 `/bff/session` | 익명 `200 {"authenticated":false}` |
| 갱신 실패 후 보호 API | `401`, 업무 로직 실행 안 함 |
| 갱신 전 로컬 사용자 조회 DB 장애 | 기존 계약대로 세션 유지, `503` |
| 앱 로그아웃 | 로컬 정리 후 `204` |
| 전체 로그아웃 시작 성공 | 로컬 정리 후 `200`과 토큰 없는 `continueUrl` |
| 전체 로그아웃 전달 정보 확보 실패 | 로컬 정리 후 `503`, 고정 오류 코드 |
| 유효한 continuation GET | 한 번 소비 후 IdP로 `303` |
| 잘못된·만료·재사용 continuation GET | `410`, redirect 없음 |

모든 응답은 `no-store`를 적용합니다. CSRF·Origin 거절은 기존 보안 체인의 `403` 계약을 유지하고 토큰 작업을 하지 않습니다. 익명 로그아웃 POST의 성공이나 재호출 멱등성을 별도로 보장하지 않으며 기존 인증·CSRF 정책을 우회하지 않습니다.

## 검증 기준

- 만료 경계 30초 전후, 갱신 제외 경로, anonymous 요청의 외부 호출 없음, 저장소 누락 처리.
- 같은 세션 동시 요청의 token·UserInfo 호출 각각 1회와 결과 공유, 서로 다른 세션의 독립 실행.
- 최신 토큰 재확인, 갱신 실패·시간 초과 시 재시도 없음, 모든 대기 요청의 실패 공유.
- UserInfo sub·구조 검증, 새 외부 사본 반영, 로컬 권한·상태·lastLoginAt 보존, JIT·bootstrap 미실행.
- 갱신 이후 DB 실패·사용자 비활성화 시 세션 종료 및 후속 토큰 폐기 시도.
- 갱신 시작 전·교환 중·DB 커밋 후·게시 직전 로그아웃 경쟁을 latch로 제어하여 세션 복구 없음 확인.
- 늦게 도착한 Refresh Token 폐기, revocation 오류·시간 초과 중에도 로컬 쿠키·세션 정리 확인.
- 실제 HTTP 보안 체인에서 CSRF·Origin·API 상태·쿠키·캐시·referrer 헤더와 토큰 비노출 검증.
- ticket 60초 경계, 원자적 단일 소비, 재사용·변조·만료 `410`, 용량 제한·실패 시 로컬 정리, 고정 redirect 검증.
- IdP backend에서 현재 세션과 맞는 만료 ID Token 허용, 다른 세션·sub·sid·auth_time·issuer·audience·서명·redirect 거절, 일반 JWT 만료 거절 회귀 검증.
- Reference App backend 전체 테스트와 IdP 변경에 필요한 테스트·기존 logout 회귀 테스트 실행. DB 동작은 실제 PostgreSQL 테스트 fixture를 사용하고, 외부 호출은 제어 가능한 mock issuer로 검증합니다.

실제 SPA와 외부 IdP의 최상위 브라우저 탐색은 Task 10에서 추가 검증합니다. Task 6에서는 HTTP 계약 검증과 브라우저 E2E 완료를 구분하여 보고합니다.

## 자체 검토 기록

승인된 갱신 범위·실패 정책·종료 우선순위·ID Token URL 예외·IdP 수정·60초 전달 정보·API 응답을 대조했습니다. 갱신 전 DB 장애와 갱신 후 실패를 구분하고, DB 커밋 후 로그아웃 및 늦은 네트워크 결과의 처리 경계를 명시했습니다. 누락된 운영 기본값과 전달 정보 확보 실패 응답도 구체화했습니다. 미완성 항목은 없으며 하나의 Task 6 구현 계획으로 진행할 범위입니다.
