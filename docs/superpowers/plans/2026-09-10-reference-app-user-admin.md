# Reference App User Administration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 APP_ADMIN이 사용자 목록·상세를 조회하고 상태·역할을 변경하며, 동시 변경에서도 마지막 활성 관리자와 현재 요청자 인가를 보호합니다.

**Architecture:** 기존 bootstrap singleton을 공통 DB 잠금으로 재사용합니다. 변경 서비스는 공통 잠금 이후 actor 재인가, 대상 잠금, version 비교와 마지막 관리자 검사를 하나의 트랜잭션으로 수행합니다. 읽기 전용 조회 및 HTTP 표현은 변경 서비스와 분리합니다.

**Tech Stack:** Java 21, 현재 Spring Boot 3.5.15/Spring Security, JPA, PostgreSQL, JUnit 5, Testcontainers, 실제 HTTP fixture.

**Spec:** [승인된 설계](../specs/2026-09-10-reference-app-user-admin-design.md)

## Global Constraints

- 사용자 생성은 기존 OIDC 최초 로그인 JIT를 유지합니다. 수동 생성·삭제, HR 편집, SPA, 별도 감사 로그 시스템은 범위 밖입니다.
- APP_USER는 모든 사용자에게 필수입니다. HR roles는 외부 사본이며 APP_ADMIN 인가에 사용하지 않습니다.
- 공통 bootstrap singleton → 사용자 행 순서로 잠급니다. 사용자 행을 먼저 잡은 뒤 공통 잠금을 요청하는 역순 경로는 추가하지 않습니다.
- 관리자 변경은 READ_COMMITTED, lock_timeout 3초, statement_timeout 5초, Spring 트랜잭션 timeout 10초입니다. DB 설정은 해당 트랜잭션에만 적용합니다. 자동 재시도는 하지 않습니다.
- actor 인가 → 대상 존재 → version → 마지막 관리자 보호 순서로 검사합니다.
- 같은 값 요청은 version 일치 확인 후 성공하며 쓰기·updatedAt·version 변경을 하지 않습니다. 같은 값이어도 오래된 version은 409입니다.
- 실제 변경은 updatedAt과 JPA version을 갱신하고 createdAt, lastLoginAt, issuer/subject, 외부 snapshot을 보존합니다.
- 마지막 ACTIVE APP_ADMIN 제거를 금지합니다. 다른 활성 관리자가 있으면 자기 자신 변경을 허용합니다. DISABLED 관리자는 활성 관리자 수에서 제외합니다.
- 기존 보안 필터 순서, 로그인 세대·토큰 갱신·로그아웃을 보존합니다. 네트워크·토큰 폐기를 관리자 DB 트랜잭션 안에서 호출하지 않습니다.
- 관리자 MVC 오류만 Problem Details로 정규화합니다. 기존 보안 필터의 상태/본문 계약을 바꾸지 않습니다. 관리자 경로의 모든 응답은 Cache-Control: no-store입니다.
- IdP 코드·패키지·DB 의존성, 신규 테이블·라이브러리·운영용 테스트 endpoint를 추가하지 않습니다.

## 실행 준비와 책임 분리

실행 시 using-git-worktrees로 `codex/reference-app-user-admin` 브랜치와 별도 worktree를 만듭니다. 기존 `.idea/` 파일과 Task 6 worktree는 보존합니다. 구현 실행은 병합·push를 포함하지 않습니다. 아래 Gradle 명령은 worktree의 `reference-app/backend`에서 실행합니다. 기존 283개 테스트는 기준값이며 최종 실행 결과로 재사용하지 않습니다.

| 작업 | 책임 | 주요 경계 |
|---|---|---|
| 1 | domain과 DB 저장 | AppUser, repository, JPA adapter/entity |
| 2 | 잠금·actor·마지막 관리자·timeout | AppUserAdminService, Exception, TransactionSettings |
| 3 | 일관된 목록·상세 조회 | QueryService, Query/Page records |
| 4 | HTTP·입력·인가·오류 | Controller/Requests/Responses/ExceptionHandler, HeadersFilter |
| 5 | 실제 HTTP·회귀·문서 | HTTP 통합 테스트, README, 검증 보고서 |

서비스 결과는 기존 AppUserView를 재사용하고 presentation에서 공개 DTO로 변환합니다. entity를 HTTP로 반환하지 않습니다. 작업 간 이름·타입은 아래 Interfaces를 따릅니다.

## Task 1: 상태·역할 변경과 DB 저장

**Files:**
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUser.java`
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUserRepository.java`
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppUserJpaEntity.java`
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppUserRepositoryAdapter.java`
- Modify: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserTest.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminPersistenceIntegrationTest.java`

**Interfaces:**
- AppUser: `changeStatus(AppUserStatus status, Instant now): AppUser`, `changeRoles(Set<AppRole> roles, Instant now): AppUser`.
- AppUserRepository 추가: `Optional<AppUser> findByIdForUpdate(UUID id)`, `long countActiveAdministrators()`, `AppUser updateAdministration(AppUser user)`.
- 기존 repository MANDATORY 계약을 유지합니다. updateAdministration은 version 검사 후 상태·역할·updatedAt만 적용하고 flush하여 새 version을 반환합니다.

- [x] **Step 1: domain RED 작성·실행**

기존 AppUserTest에서 아래 각각을 독립 사례로 작성합니다.

```java
var now = Instant.parse("2026-09-10T00:00:00Z");
var user = AppUser.create(UUID.randomUUID(), "https://issuer.test", "subject",
        new ExternalUserSnapshot("u@example.test", "User", null, null, Set.of()), now);
var next = user.changeRoles(Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), now.plusSeconds(1));
assertThat(next.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
assertThat(next.snapshot()).isEqualTo(user.snapshot());
assertThat(next.lastLoginAt()).isEqualTo(now);
assertThat(next.createdAt()).isEqualTo(now);
assertThat(next.updatedAt()).isEqualTo(now.plusSeconds(1));
assertThat(user.changeStatus(AppUserStatus.ACTIVE, now.plusSeconds(1))).isSameAs(user);
assertThatThrownBy(() -> user.changeRoles(Set.of(AppRole.APP_ADMIN), now))
        .isInstanceOf(IllegalArgumentException.class);
```

Run: `.\gradlew.bat test --tests '*AppUserTest' --console=plain`. 새 메서드가 없는 실패를 확인합니다.

- [x] **Step 2: domain·entity 구현**

```java
public AppUser changeStatus(AppUserStatus next, Instant now) {
    Objects.requireNonNull(next); Objects.requireNonNull(now);
    if (status == next) return this;
    return new AppUser(id, issuer, subject, snapshot, next, roles, createdAt, now, lastLoginAt, version);
}
public AppUser changeRoles(Set<AppRole> next, Instant now) {
    Objects.requireNonNull(next); Objects.requireNonNull(now);
    if (!next.contains(AppRole.APP_USER)) throw new IllegalArgumentException("APP_USER role is required");
    if (roles.equals(next)) return this;
    return new AppUser(id, issuer, subject, snapshot, status, next, createdAt, now, lastLoginAt, version);
}
```

entity는 status/roles/updatedAt만 변경하고 역할 collection은 기존 관리 collection에 차이만 반영합니다. 같은 값은 dirty하게 만들지 않습니다.

- [x] **Step 3: 실제 DB RED·adapter 구현**

BootstrapIntegrationSupport를 상속하고 provisioning(AppUserProvisioningService), users(AppUserRepository)를 주입합니다.

```java
var before = provisioning.provision(profile("admin-change", Set.of()));
var after = tx.execute(s -> {
    var current = users.findByIdForUpdate(before.id()).orElseThrow();
    return users.updateAdministration(current.changeRoles(
            Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), current.updatedAt().plusSeconds(1)));
});
assertThat(after.version()).isGreaterThan(before.version());
var stored = tx.execute(s -> users.findById(before.id()).orElseThrow());
assertThat(stored.roles()).contains(AppRole.APP_ADMIN);
```

UUID 잠금 조회는 EntityManager.find(entity class, id, PESSIMISTIC_WRITE)와 존재 시 refresh(entity, PESSIMISTIC_WRITE)로 최신화합니다. updateAdministration은 잠금 조회→기존 verifyVersion→변경 적용→flush→toDomain입니다. 집계 SQL은 다음과 같습니다.

```sql
select count(distinct u.id) from app_user u
join app_user_role r on r.app_user_id=u.id
where u.status='ACTIVE' and r.role='APP_ADMIN'
```

상태만 변경, 역할만 변경, stale version, no-op version 보존, 예외 후 상태·역할·version 전체 롤백도 별도 사례로 검증합니다.

- [x] **Step 4: GREEN·커밋**

Run: `.\gradlew.bat test --tests '*AppUserTest' --tests '*AppUserAdminPersistenceIntegrationTest' --tests '*AppUserPersistenceIntegrationTest' --console=plain`.

Files의6개 파일만 명시 stage합니다. Commit: `feat: persist app user administration changes`.

## Task 2: 현재 actor 재인가와 마지막 관리자 보호

**Files:**
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminException.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminService.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppAdminTransactionSettings.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminIntegrationTest.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes Task 1 repository, 기존 AppBootstrapStateRepository.findSingletonForUpdate(), Clock.
- Service: `changeStatus(UUID actorId, UUID targetId, AppUserStatus status, long version): AppUserView`, `changeRoles(UUID actorId, UUID targetId, Set<AppRole> roles, long version): AppUserView`.
- AppUserAdminException extends RuntimeException: nested `Code {INVALID_REQUEST, FORBIDDEN, APP_USER_NOT_FOUND, OPTIMISTIC_LOCK_CONFLICT, LAST_ACTIVE_ADMIN_REQUIRED, SERVICE_UNAVAILABLE}`, constructor(Code), `code()`. 메시지는 code명이며 원본 입력을 보관하지 않습니다.
- AppAdminTransactionSettings: 같은 DataSource의 JdbcTemplate과 `void apply()`.

- [x] **Step 1: 서비스 RED 작성·실행**

BootstrapIntegrationSupport를 상속하고 AppLocalLoginService(login), 새 서비스(admin)를 주입합니다.

```java
var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
assertThatThrownBy(() -> admin.changeStatus(actor.id(), actor.id(), AppUserStatus.DISABLED, actor.version()))
        .isInstanceOfSatisfying(AppUserAdminException.class,
                e -> assertThat(e.code()).isEqualTo(AppUserAdminException.Code.LAST_ACTIVE_ADMIN_REQUIRED));
```

두 관리자일 때 자기 강등·비활성화 성공, disabled 집계 제외, actor 비관리자/불존재/disabled 거절, 대상 없음, same-value 성공과 stale 거절, HR 필드·bootstrap 기록 보존을 추가합니다.

Run: `.\gradlew.bat test --tests '*AppUserAdminIntegrationTest' --console=plain`.

- [x] **Step 2: 공통 변경 처리 구현**

public 메서드에 `@Transactional(isolation=Isolation.READ_COMMITTED, timeout=10)`을 적용합니다. 입력 null/음수/APP_USER 누락은 INVALID_REQUEST입니다. 아래 mutation은 UnaryOperator<AppUser>, expectedVersion은 요청 version입니다.

```java
settings.apply();
states.findSingletonForUpdate();
var actor = users.findByIdForUpdate(actorId)
        .orElseThrow(() -> new AppUserAdminException(Code.FORBIDDEN));
if (actor.status() != AppUserStatus.ACTIVE || !actor.roles().contains(AppRole.APP_ADMIN))
    throw new AppUserAdminException(Code.FORBIDDEN);
var current = actorId.equals(targetId) ? actor : users.findByIdForUpdate(targetId)
        .orElseThrow(() -> new AppUserAdminException(Code.APP_USER_NOT_FOUND));
if (current.version() != expectedVersion) throw new AppUserAdminException(Code.OPTIMISTIC_LOCK_CONFLICT);
var next = mutation.apply(current);
if (next == current) return AppUserView.from(current);
boolean wasAdmin = current.status() == AppUserStatus.ACTIVE && current.roles().contains(AppRole.APP_ADMIN);
boolean remainsAdmin = next.status() == AppUserStatus.ACTIVE && next.roles().contains(AppRole.APP_ADMIN);
if (wasAdmin && !remainsAdmin && users.countActiveAdministrators() <= 1)
    throw new AppUserAdminException(Code.LAST_ACTIVE_ADMIN_REQUIRED);
return AppUserView.from(users.updateAdministration(next));
```

actor도 잠금 최신화하여 오래된 영속성 컨텍스트를 신뢰하지 않습니다. 순서는 공통→actor→target입니다. 두 public 메서드는 Clock.instant를 쓰는 lambda로 domain 변경을 호출합니다. settings.apply는 MANDATORY로 선언하고 다음만 수행합니다.

```java
jdbc.execute("SET LOCAL lock_timeout = '3s'");
jdbc.execute("SET LOCAL statement_timeout = '5s'");
```

DB 예외는 삼키거나 재시도하지 않고 Task 4에서 HTTP로 변환합니다.

- [x] **Step 3: 실제 DB 경쟁·timeout 검증**

전체 테스트를@Transactional로 감싸지 않습니다. 두 관리자를 fixture a/b로 만들고 시작 gate 뒤 각각 자기 비활성화를 호출합니다. 기존 awaitLatch를 사용하며 admin은 Spring proxy입니다.

```java
var start = new CountDownLatch(1);
try (var pool = Executors.newFixedThreadPool(2)) {
    var first = pool.submit(() -> {
        awaitLatch(start);
        try { admin.changeStatus(a.id(), a.id(), AppUserStatus.DISABLED, a.version()); return "OK"; }
        catch (AppUserAdminException e) { return e.code().name(); }
    });
    var second = pool.submit(() -> {
        awaitLatch(start);
        try { admin.changeStatus(b.id(), b.id(), AppUserStatus.DISABLED, b.version()); return "OK"; }
        catch (AppUserAdminException e) { return e.code().name(); }
    });
    start.countDown();
    assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder("OK", "LAST_ACTIVE_ADMIN_REQUIRED");
}
assertThat(tx.execute(s -> users.countActiveAdministrators())).isEqualTo(1L);
```

별도 사례에서는 선행 변경이 대기 actor 권한을 회수해 commit한 뒤 대기 요청이 FORBIDDEN이고 target은 불변인지 검사합니다. 기존 awaitDatabaseLock(pid)로 실제 대기를 관측하고 PID/latch는 테스트 전용 경계에서 확보합니다. production hook은 추가하지 않습니다.

다른 연결로 singleton을 유지하여3초 lock timeout과 전체 DB 불변을 검증합니다. gate는 finally에서 해제하고 같은 연결 재사용 후 SHOW lock_timeout/statement_timeout이 사전 값으로 복구되는지 확인합니다. 부여·활성화와 회수·비활성화, bootstrap/login과 관리 변경, snapshot refresh와 관리 변경도 검증합니다. 역할 덮어쓰기와 잠금 역전이 없어야 합니다.

- [x] **Step 4: GREEN·커밋**

Run: `.\gradlew.bat test --tests '*AppUserAdminIntegrationTest' --tests '*AppUserAdminConcurrencyIntegrationTest' --tests '*AppAdminBootstrap*' --tests '*AppLocalLoginIntegrationTest' --console=plain`.

Files의5개 파일을 stage합니다. Commit: `feat: guard app administrator mutations transactionally`.

## Task 3: 일관된 목록·상세 조회

**Files:**
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminQuery.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminPage.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/application/AppUserAdminQueryService.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUserPage.java`
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain/AppUserRepository.java`
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/infrastructure/AppUserRepositoryAdapter.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppUserAdminQueryIntegrationTest.java`

**Interfaces:**
- `AppUserAdminQuery(int page, int size, AppUserStatus status, AppRole role)` record: null 필터는 미지정, page>=0, size=1..100.
- `AppUserPage(List<AppUser> items, long totalElements)` domain record.
- `AppUserAdminPage(List<AppUserView> items, int page, int size, long totalElements, long totalPages)` application record.
- repository: `AppUserPage findPage(int page, int size, AppUserStatus status, AppRole role)`.
- service: `AppUserAdminPage list(AppUserAdminQuery query)`, `AppUserView detail(UUID id)`. 불존재는 APP_USER_NOT_FOUND입니다.

- [x] **Step 1: 조회 RED 작성·실행**

BootstrapIntegrationSupport를 상속하고 같은 createdAt의 여러 사용자, 두 role 사용자, disabled를 만듭니다. queries는 새 query service입니다.

```java
var result = queries.list(new AppUserAdminQuery(0, 1, AppUserStatus.ACTIVE, AppRole.APP_ADMIN));
assertThat(result.items()).hasSize(1);
assertThat(result.items().getFirst().roles()).contains(AppRole.APP_ADMIN);
assertThat(result.page()).isZero();
assertThatThrownBy(() -> new AppUserAdminQuery(0, 101, null, null)).isInstanceOf(AppUserAdminException.class);
```

AND 필터, 중복 없는 count, createdAt DESC/id ASC, 빈 결과/범위 밖 page, Integer.MAX_VALUE page의 overflow 방지, detail 불존재를 독립적으로 검증합니다. 기대 합계는 고정 fixture 수로 단언합니다.

Run: `.\gradlew.bat test --tests '*AppUserAdminQueryIntegrationTest' --console=plain`.

- [x] **Step 2: 포트와 조회 구현**

list는 `@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)`, detail은 readOnly입니다. 공통 배타 잠금을 잡지 않습니다. 역할 필터는 EXISTS로 중복을 피합니다.

```sql
select u.id from app_user u
where u.status = :status
  and exists (select 1 from app_user_role r where r.app_user_id=u.id and r.role=:role)
order by u.created_at desc, u.id asc
limit :size offset :offset
```

미지정 필터 조건은 고정 SQL 조각에서 제외하고 모든 값은 bind합니다. offset은 `(long) page * size`입니다. 같은 WHERE의 count와 선택 UUID의 roles 포함 entity 조회를 같은 트랜잭션에서 수행합니다. 결과를 선택 UUID 순서로 재배열합니다. collection fetch join에 pagination을 적용하지 않습니다. totalPages는 `total / size + (total % size == 0 ? 0 : 1)`입니다. 목록은 List.copyOf로 방어 복사합니다.

- [x] **Step 3: snapshot 검증·GREEN·커밋**

count/목록 사이의 테스트 전용 gate에서 별도 연결로 추가 commit하여 같은 호출의 count/items가 같은 snapshot인지 확인합니다. 다음 호출에서는 추가분이 보여야 합니다. production hook은 추가하지 않습니다.

Run: `.\gradlew.bat test --tests '*AppUserAdminQueryIntegrationTest' --tests '*AppUserBoundaryTest' --console=plain`.

Files의7개 파일을 stage합니다. Commit: `feat: query app users with consistent pagination`.

## Task 4: HTTP API·엄격한 입력·안전한 오류

**Files:**
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminController.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminRequests.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminResponses.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/user/presentation/AppUserAdminExceptionHandler.java`
- Create: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/AdminApiHeadersFilter.java`
- Modify: `reference-app/backend/src/main/java/com/sweet/referenceapp/security/OAuth2ClientSecurityConfig.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/presentation/AppUserAdminRequestsTest.java`
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/user/presentation/AppUserAdminControllerTest.java`

**Interfaces:**
- Requests nested records: `StatusChange(AppUserStatus status, long version)`, `RolesChange(Set<AppRole> roles, long version)`.
- static parsers: `status(JsonNode body)`, `roles(JsonNode body)`, `query(String page, String size, String status, String role)`. 오류는 INVALID_REQUEST입니다.
- Responses nested Summary/ExternalIdentity/Detail/Page는 spec의 JSON 필드·자료형을 사용합니다. static `detail(AppUserView)`, `page(AppUserAdminPage)`로 변환합니다.
- actor는 CurrentAppUser.find(request)의 UUID이며 JSON·헤더로 받지 않습니다.

- [x] **Step 1: 입력 RED·parser 구현**

```java
var mapper = new ObjectMapper();
assertThatThrownBy(() -> AppUserAdminRequests.status(
        mapper.readTree("{\"status\":\"DISABLED\",\"version\":\"3\"}")))
        .isInstanceOf(AppUserAdminException.class);
var roles = AppUserAdminRequests.roles(mapper.readTree(
        "{\"roles\":[\"APP_USER\",\"APP_ADMIN\",\"APP_USER\"],\"version\":3}"));
assertThat(roles.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
```

isObject/isTextual/isArray/isIntegralNumber/canConvertToLong과0 이상을 검사합니다. version 누락/null/문자열/소수/overflow, roles 자료형/null/잘못된 값/APP_USER 누락을 각각 테스트합니다. global ObjectMapper 설정은 바꾸지 않습니다. query 기본값 page=0,size=20이며 빈 enum·잘못된 숫자는400입니다.

Run RED→GREEN: `.\gradlew.bat test --tests '*AppUserAdminRequestsTest' --console=plain`.

- [x] **Step 2: MVC RED·controller/DTO 구현**

MockMvc standaloneSetup에서 실제 controller/advice와 service mock을 사용합니다. CurrentAppUser.set(request, actorView)로 actor를 주입하고 body의 actorId가 사용되지 않는지 verify합니다. DTO 키 집합, null snapshot, role 순서, version, no-store를 단언합니다.

```java
@PutMapping(value = "/{userId}/status", consumes = "application/json")
ResponseEntity<?> changeStatus(@PathVariable UUID userId, @RequestBody JsonNode body,
        HttpServletRequest request) {
    var input = AppUserAdminRequests.status(body);
    var actor = CurrentAppUser.find(request).orElseThrow(() -> new AppUserAdminException(Code.FORBIDDEN));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AppUserAdminResponses.detail(admin.changeStatus(actor.id(), userId, input.status(), input.version())));
}
```

클래스 RequestMapping은 `/bff/admin/users`입니다. 역할 PUT은 roles parser/service를, 목록 GET은 query service를, 상세 GET은 detail service를 사용합니다. 성공은200/no-store입니다. Instant는 ISO 표현, roles는 APP_USER→APP_ADMIN 순서입니다. 상세만 externalIdentity를 포함하고 raw principal을 직렬화하지 않습니다.

- [x] **Step 3: 오류와 security 구현**

Advice는 `@RestControllerAdvice(assignableTypes=AppUserAdminController.class)`로 제한합니다. spec의 code/status를 switch로 고정하고 다음 방식으로 응답합니다.

```java
var problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
problem.setType(URI.create("about:blank"));
problem.setTitle(status.getReasonPhrase());
problem.setInstance(URI.create(request.getRequestURI()));
problem.setProperty("code", code.name());
```

status/safeDetail은 고정 매핑입니다. 입력 바인딩/JSON parse는400, ObjectOptimisticLockingFailureException은409, 나머지 DataAccessException/transaction timeout은503, 잘못된 메서드/Content-Type은405/415입니다. 원본 예외·SQL·비밀값을 출력하지 않으며 예상하지 못한 예외를 일괄400/503으로 바꾸지 않습니다.

```java
.requestMatchers("/bff/admin/**").hasAuthority("APP_ADMIN")
.requestMatchers("/bff/**").authenticated()
```

위 순서로 matcher를 추가합니다. AdminApiHeadersFilter는 `/bff/admin` 또는 `/bff/admin/` 하위만 chain 전 `response.setHeader("Cache-Control", "no-store")`를 적용합니다. BffOriginGuard보다 먼저 등록하고 servlet 자동 등록은 하지 않습니다. 기존 필터의 상대 순서를 유지합니다.

- [x] **Step 4: GREEN·커밋**

Run: `.\gradlew.bat test --tests '*AppUserAdminRequestsTest' --tests '*AppUserAdminControllerTest' --tests '*BffSessionSecurityIntegrationTest' --console=plain`.

실제 advice에400/403/404/409/503 원인 예외를 보내고 가짜 비밀 문자열이 출력되지 않는지 확인합니다. 필터401/403은 Task 5에서 실제 HTTP로 검증합니다. Files의8개 파일을 stage합니다. Commit: `feat: expose secure app user administration api`.

## Task 5: 실제 HTTP·인증 회귀·완료 기록

**Files:**
- Create: `reference-app/backend/src/test/java/com/sweet/referenceapp/security/AppUserAdminHttpIntegrationTest.java`
- Modify if fixture extension required: `reference-app/backend/src/test/java/com/sweet/referenceapp/security/LocalLoginHttpTestSupport.java`
- Modify: `reference-app/backend/README.md`
- Modify: `docs/superpowers/plans/2026-08-21-oauth-oidc-reference-app.md` (Task 7 방식·principal·완료 상태만)
- Modify: `docs/superpowers/plans/2026-09-10-reference-app-user-admin.md` (체크와 실행 결과)
- Create: `docs/superpowers/reports/2026-09-10-reference-app-user-admin-verification.md`

**Interfaces:** package-private LocalLoginHttpTestSupport를 상속하므로 HTTP 테스트는 security 패키지입니다. `login(): String`, `csrfToken(String cookie): String`, `userId(String cookie): UUID`, `send(String method, String path, String cookie, String body, String... headers): HttpResponse<String>`를 사용합니다. send body는 null 대신 빈 문자열입니다.

- [x] **Step 1: 실제 로그인 fixture와 HTTP RED**

실제 OIDC callback으로 admin과 일반 사용자를 로그인시킵니다. mock issuer subject를 구분하고 초기 bootstrap은 기존 COMPANY_ADMIN claim을 사용합니다. 검증 대상 권한 변경은 실제 관리 API로 수행합니다.

```java
var denied = send("GET", "/bff/admin/users", null, "");
assertThat(denied.statusCode()).isEqualTo(401);
assertThat(denied.headers().firstValue("Cache-Control")).contains("no-store");
```

익명, APP_USER, HR 관리자 snapshot만 보유한 APP_USER, 로컬 APP_ADMIN을 구분합니다. fixture 변경은 공통 클래스에 모아 고정 포트의 추가 Spring context를 피합니다.

- [x] **Step 2: API와 다음 요청 반영 검증**

adminCookie는 login 반환값, targetId는 대상 cookie의 userId(), version은 직전 상세 GET에서 가져옵니다. 두 번째 관리자 존재 여부를 명시하여 다음 요청을 검증합니다.

```java
var body = JSON.createObjectNode().put("status", "DISABLED").put("version", version).toString();
var changed = send("PUT", "/bff/admin/users/" + targetId + "/status", adminCookie, body,
        "Content-Type", "application/json", "Origin", SPA, "X-CSRF-TOKEN", csrfToken(adminCookie));
assertThat(changed.statusCode()).isEqualTo(200);
assertThat(JSON.readTree(changed.body()).path("version").asLong()).isGreaterThan(version);
```

권한 회수 후 admin API403, 비활성화 후 profile401/session 익명200을 확인합니다. 자기 변경은 PUT 자체200 뒤 다음 요청부터 반영됩니다. CSRF 누락/잘못된 Origin은403이며 DB는 불변입니다. JSON 키 집합과 기존 assertNoTokenLeak을 함께 확인합니다. issuer/subject는 상세 externalIdentity에만 있습니다.409의 OPTIMISTIC_LOCK_CONFLICT와 LAST_ACTIVE_ADMIN_REQUIRED를 구분합니다.

- [x] **Step 3: 실제 DB 장애·갱신 version 충돌**

별도 연결로 singleton을 잡고 올바른 HTTP PUT을 보내503 SERVICE_UNAVAILABLE과 상태·역할·version 불변을 확인합니다. gate는 finally에서 해제합니다. 짧은 Access Token이 GET 전에 refresh될 수 있으므로 정상 변경은 직전 GET version을 사용합니다. 별도 사례에서는 snapshot refresh 뒤 이전 version PUT이409이고 외부 snapshot·roles를 덮어쓰지 않아야 합니다.

Run: `.\gradlew.bat test --tests '*AppUserAdminHttpIntegrationTest' --console=plain`. 실패 원인·RED 증거를 남기고 필요한 최소 수정만 합니다. 필터 DB 장애와 MVC service timeout 응답을 구분합니다.

- [x] **Step 4: 전체 회귀**

Run: `.\gradlew.bat test --console=plain`.

종료 코드와 `build/test-results/test/TEST-*.xml`의 tests/failures/errors/skipped 합계를 기록합니다. 안정화 뒤 전체 suite1회를 기본으로 하고 추가 변경·실패 없이 반복하지 않습니다. 기존 재로그인·갱신·로그아웃을 포함합니다. IdP 미변경 시 그 suite를 재실행하지 않으며 실제 SPA 브라우저 E2E 완료를 주장하지 않습니다.

- [x] **Step 5: 문서·완료 기록·커밋**

README에4개 API 예, 필요role, version 재조회,409 두 종류, 자기 변경, 다음 요청 반영,3/5/10초 제한과 로그인과의 공통 잠금 경합을 기록합니다. 상위 Task 7의 활성 관리자 행 전체 잠금과 옛 AppUserAuthentication은 승인 spec 및 실제 AppOidcUser 구조에 맞춰 정리합니다.

보고서 구성은 `구현 commit / RED 증거 / focused·전체 명령과 exit·XML 수 / 경쟁 결과 / 미실행 범위 / 독립 review 결과`입니다. 독립 review 결과는 실제 검토 뒤 controller가 기록합니다. scratch는 stage하지 않습니다.

Run (worktree root): `git diff --check`. Files에서 실제 변경한 파일만 stage합니다. Commit: `test: verify app user administration end to end`.

## 자체 검토와 실행 순서

Task 1→2→3→4→5 순서입니다. 각 작업에 RED/GREEN과 독립 검토가 있으며 repository/fixture 공유 때문에 병렬 구현하지 않습니다.

| Spec 요구 | 담당·검증 |
|---|---|
| 범위·독립성·domain 보존 |1,5 경계/JSON|
| 공통 잠금·actor·마지막 관리자 |2 실제 DB 경쟁|
| version/no-op/전체 rollback |1 저장,2 service,5 HTTP|
| 페이지·AND·같은 snapshot |3 실제 DB 조회|
| 입력·DTO·오류·no-store |4 단위/MVC,5 HTTP|
| CSRF/Origin/권한/다음 요청 |5 HTTP|
| bootstrap/login/refresh 상호작용 |2 경쟁,5 전체 회귀|
|3/5/10초·DB 설정 복원 |2 DB,5 HTTP503|
| 문서·실행 증거·SPA 후속 경계 |5|

자체 검토에서 승인 spec의 모든 요구를 위 표에 배정했고 작업 간 메서드·타입명을 대조했습니다. 기존 HTTP fixture의 csrfToken(cookie), userId(cookie), 빈 문자열 body 계약을 확인했습니다. actor 재조회도 UUID 잠금 최신화를 사용하도록 구체화해 기존 영속성 컨텍스트 값의 재사용을 피했습니다. 미정 항목은 없습니다.

2026-09-10: Task 1–5 구현 및 실행 검증을 마쳤습니다. 체크는 구현·실행 완료를 뜻하며 최종 독립 검토 승인을 뜻하지 않습니다. 전체 변경 검토에서 발견한 P2 트랜잭션 시작 실패 매핑과 P3 identity 조회 문서 오류를 수정했으며, 해당 범위 재검토는 대기 중입니다. 정확한 실행 결과와 한계는 [검증 보고서](../reports/2026-09-10-reference-app-user-admin-verification.md)에 기록합니다.

최종 수정 검증 결과: controller·HTTP focused 24/24, 전체 Reference App 384/384(41 suites), failures/errors/skipped 모두 0이며 exit 0입니다. 최종 전체 명령은 `.\gradlew.bat test --console=plain`이고 55초에 완료했습니다. IdP·실제 SPA 브라우저 E2E는 미실행입니다.
