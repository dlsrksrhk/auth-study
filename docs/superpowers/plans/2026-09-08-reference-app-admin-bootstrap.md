# Reference App Task 3 Admin Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ACTIVE + 현재 COMPANY_ADMIN인 후보 한 명만 최초 APP_ADMIN으로 지정하고 JIT·권한·완료 기록의 원자성을 보장합니다.

**Architecture:** AppLoginProvisioningService가 singleton 잠금 → 기존 JIT → bootstrap 정책을 하나의 READ_COMMITTED 트랜잭션으로 조율합니다. bootstrap 상태와 사용자 역할 저장은 별도 도메인/저장 포트로 분리하며 실제 PostgreSQL에서 경쟁과 실패를 검증합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Gradle 9.5.1, Spring Data JPA, Flyway, PostgreSQL 17, Testcontainers, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-09-08-reference-app-admin-bootstrap-design.md` (설계 커밋 `abe14fa`)

## Global Constraints

- 상위 Reference App 계획의 Task 3만 구현합니다. 아래 3A–3C는 하위 작업입니다.
- 앱 전체 bootstrap 상태가 미완료입니다.
- 사용자 생성·갱신 후 로컬 상태가 `ACTIVE`입니다.
- 이번 호출에서 검증된 외부 정보의 HR 역할 집합에 정확히 `COMPANY_ADMIN`이 있습니다.
- 위 세 항목을 모두 충족해야 최초 관리자 후보입니다. 회사별/issuer별 bootstrap은 아닙니다.
- `DISABLED` 사용자에게는 APP_ADMIN을 추가하거나 ACTIVE로 바꾸지 않습니다.
- 모든 경로에서 잠금 순서는 **bootstrap_state → app_user**입니다. 개별 bootstrap/JIT 저장에 REQUIRES_NEW를 사용하지 않습니다.
- 기존 V1의 `app_bootstrap_state`와 최초 singleton 행을 재사용합니다. 이번 정책에는 스키마 변경이 필요하지 않으며, 적용된 V1을 다시 쓰지 않습니다.
- 완료 상태는 두 값이 모두 존재하며 다른 사용자로 덮어쓰거나 초기화하지 않습니다.
- 트랜잭션 안에서 IdP를 호출하지 않습니다.
- 로그인/토큰/세션/관리 API/SPA/reset 기능은 구현하지 않습니다. IdP 코드·DB·내부 숫자 ID·OAuth 토큰을 공유하거나 저장하지 않습니다.
- 기존 `frontend/.idea/`와 다른 계획의 SDD 기록을 건드리지 않습니다. main 병합·push는 별도 요청 전 수행하지 않습니다.

---

## 실행 위치와 기준 상태

기존 `C:/dev/auth-study/.worktrees/oidc-reference-app`, branch `codex/oidc-reference-app`를 검증 후 재사용합니다. Task 2 구현 HEAD는 `09f2f86`, 그 위에 Task 3 설계·계획 문서가 있습니다. 작업 시작 시 git status/log와 실제 파일을 확인합니다.

```powershell
git status --short
git log -3 --oneline
```

`reference-app/backend`에서 `./gradlew.bat clean test`로 baseline을 확인합니다. Task 2 완료 기준은 36개 테스트입니다. Docker 환경 오류를 RED 증거로 취급하지 않습니다. 모든 새 테스트는 전용 Testcontainers DB만 사용합니다.

## 파일 구조와 공통 인터페이스

아래의 `J/`는 `reference-app/backend/src/main/java/com/sweet/referenceapp/`, `T/`는 `reference-app/backend/src/test/java/com/sweet/referenceapp/`를 뜻합니다. 실제 경로는 해당 루트와 상대 경로를 결합합니다.

| 작업 | 생성/변경 파일 | 책임 |
|---|---|---|
| 3A | Create J/user/domain/AppBootstrapState.java | singleton 상태와 일회성 완료 |
| 3A | Create J/user/domain/AppBootstrapStateRepository.java | 잠금 조회·완료 저장 port |
| 3A | Create J/user/infrastructure/AppBootstrapStateJpaEntity.java | 기존 테이블 JPA mapping |
| 3A | Create J/user/infrastructure/AppBootstrapStateJpaRepository.java | singleton 잠금 조회 |
| 3A | Create J/user/infrastructure/AppBootstrapStateRepositoryAdapter.java | 필수 transaction, refresh/version/완료 검사 |
| 3A | Modify J/user/domain/AppUser.java | ACTIVE 사용자 관리자 역할 추가 |
| 3A | Modify J/user/domain/AppUserRepository.java | 관리자 추가 저장 메서드 |
| 3A | Modify J/user/infrastructure/AppUserJpaEntity.java | role 추가와 수정 시각 |
| 3A | Modify J/user/infrastructure/AppUserRepositoryAdapter.java | 잠금·version 검사 후 역할 저장 |
| 3A | Create T/user/AppBootstrapStateTest.java | 상태·도메인 단위 검증 |
| 3A | Create T/support/BootstrapIntegrationSupport.java | 별도 Spring context/Testcontainer, test reset 및 fixture |
| 3A | Create T/user/AppBootstrapPersistenceIntegrationTest.java | 기존 스키마 및 저장 동작 검증 |
| 3B | Create J/user/application/AppAdminBootstrapService.java | 후보 판정과 관리자/완료 기록 저장 |
| 3B | Create J/user/application/AppLoginProvisioningService.java | 외부 입력→bootstrap 잠금→JIT→최신 결과 |
| 3B | Create T/user/AppAdminBootstrapIntegrationTest.java | 후보 정책·일회성·최종 반환 검증 |
| 3C | Create T/user/AppAdminBootstrapConcurrencyIntegrationTest.java | 실제 잠금 대기 및 동시 후보 |
| 3C | Create T/user/AppAdminBootstrapRollbackIntegrationTest.java | 저장 실패·외부 rollback·상태 누락 |
| 3C | Modify T/support/BootstrapIntegrationSupport.java | 여러 경쟁 테스트에서 재사용하는 bounded wait 도우미 |

기존 AppUserProvisioningService의 `AppUserView provision(ExternalIdentityProfile)`와 UTC Clock bean은 변경하지 않습니다. Task 2의 역할/상태 보존 계약을 유지합니다.

### 공통 타입/메서드 계약

```java
public record AppBootstrapState(short singletonKey, UUID bootstrappedUserId,
        Instant bootstrappedAt, long version) {
    public boolean completed() { return bootstrappedUserId != null; }
    public AppBootstrapState complete(UUID userId, Instant now) {
        if (completed()) throw new IllegalStateException("Bootstrap already completed");
        return new AppBootstrapState(singletonKey, Objects.requireNonNull(userId),
                Objects.requireNonNull(now), version);
    }
}
public interface AppBootstrapStateRepository {
    AppBootstrapState findSingletonForUpdate();
    void complete(AppBootstrapState completed);
}
```

record constructor는 singletonKey==1, version>=0, UUID/시각이 모두 null이거나 모두 nonnull임을 검사하고 아니면 IllegalArgumentException을 던집니다. complete는 저장 전 상태 전이이므로 version을 직접 증가시키지 않습니다.

AppUser에 `AppUser withAdministrator(Instant now)`를 추가합니다. DISABLED이면 IllegalStateException, 이미 APP_ADMIN이면 this, 그 외는 기존 roles에 APP_ADMIN을 추가하고 updatedAt만 now로 바꾼 새 record입니다. id/issuer/subject/snapshot/status/createdAt/lastLoginAt/version은 유지합니다.

AppUserRepository에 `AppUser addAdministrator(AppUser user, Instant now)`를 추가합니다. 잠근 현재 행의 id와 전달된 id, version을 확인한 후 도메인의 withAdministrator로 자격을 검증합니다. 이미 APP_ADMIN이면 추가 저장 없이 현재 값을 반환합니다. 실제 추가 시 관리 entity의 roles에 APP_ADMIN을 더하고 updatedAt을 바꿔 flush한 뒤 최신 version을 반환합니다. 기존 APP_USER는 보존하며 status/snapshot/lastLoginAt을 갱신하지 않습니다.

### 통합 테스트 격리 계약

BootstrapIntegrationSupport는 아래 annotation을 가진 abstract class로 정의합니다. 새 bootstrap 테스트만 상속합니다. 별도 property가 기존 Task 2 Spring context와 Testcontainer를 분리하고, 새 테스트끼리는 context를 재사용합니다. 클래스 병렬 실행을 활성화하지 않습니다.

```java
@SpringBootTest(properties = "spring.application.name=reference-bootstrap-test")
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
public abstract class BootstrapIntegrationSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    protected TransactionTemplate tx;
    @BeforeEach void resetBootstrapDatabase() {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from app_bootstrap_state");
            jdbc.update("delete from app_user");
            jdbc.update("insert into app_bootstrap_state(singleton_key) values (1)");
        });
    }
    protected ExternalIdentityProfile profile(String sub, Set<String> roles) {
        return new ExternalIdentityProfile(URI.create("http://idp.localhost:8080"),
                sub, "user@example.test", "User", null, null, roles);
    }
}
```

위 삭제는 전용 Testcontainer에 한정합니다. test reset은 production reset 기능이 아닙니다. singleton이 없는 테스트 다음에도 초기화가 복구하며, app_user_role은 기존 FK cascade로 제거됩니다. 새 테스트에 클래스 수준 @Transactional을 붙이지 않습니다.

---

### Task 3A: bootstrap 상태와 원자적 권한 저장

**Files:** 파일 표의 3A 12개 파일. 기존 V1/build/config는 변경하지 않습니다.

**Interfaces:** Consumes: 기존 AppUser/AppRole/AppUserStatus와 AppUserRepository의 잠금 조회. Produces: 공통 계약의 AppBootstrapState, findSingletonForUpdate/complete, withAdministrator/addAdministrator 및 BootstrapIntegrationSupport.

- [ ] **Step 1: 도메인·저장 실패 테스트 작성**

AppBootstrapStateTest에 다음을 작성하고, 잘못된 singleton/version/UUID-시각 쌍도 parameterized test로 검사합니다.

```java
@Test void bootstrapCompletionCannotBeReassigned() {
    var state = new AppBootstrapState((short)1, null, null, 0);
    var id = UUID.randomUUID();
    var done = state.complete(id, Instant.EPOCH);
    assertThat(done.completed()).isTrue();
    assertThat(done.bootstrappedUserId()).isEqualTo(id);
    assertThatThrownBy(() -> done.complete(UUID.randomUUID(), Instant.EPOCH))
            .isInstanceOf(IllegalStateException.class).hasMessage("Bootstrap already completed");
}
```

같은 클래스에 AppUser.withAdministrator의 ACTIVE 승격, 기존 역할 보존, DISABLED 거절, 기존 ADMIN의 무변경을 각각 별도 테스트로 작성합니다.

AppBootstrapPersistenceIntegrationTest는 BootstrapIntegrationSupport를 상속하고, AppUserRepository.insertIfAbsent로 사용자를 준비합니다. state와 user 저장은 다음 순서로 수행합니다.

```java
tx.executeWithoutResult(status -> {
    var state = bootstrapRepository.findSingletonForUpdate();
    var user = AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", "candidate",
            profile("candidate", Set.of()).snapshot(), Instant.EPOCH);
    userRepository.insertIfAbsent(user);
    var current = userRepository.findByIdentityForUpdate(user.issuer(), user.subject()).orElseThrow();
    var promoted = userRepository.addAdministrator(current, Instant.EPOCH.plusSeconds(1));
    bootstrapRepository.complete(state.complete(promoted.id(), Instant.EPOCH.plusSeconds(1)));
    assertThat(promoted.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
    assertThat(promoted.version()).isGreaterThan(current.version());
    assertThat(promoted.lastLoginAt()).isEqualTo(current.lastLoginAt());
});
```

테스트 클래스에 두 repository를 @Autowired합니다. 트랜잭션 밖 fresh JDBC에서 role과 bootstrap UUID를 다시 확인합니다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew.bat test --tests '*AppBootstrapStateTest' --tests '*AppBootstrapPersistenceIntegrationTest'`

Expected: 신규 타입/메서드 부재에 따른 compilation 실패. 실패 출력과 명령을 기록합니다.

- [ ] **Step 3: 도메인 전이와 JPA mapping 구현**

위 타입 계약을 구현합니다. AppBootstrapStateJpaEntity는 기존 테이블에 singletonKey(short @Id), bootstrappedUserId(UUID), bootstrappedAt(Instant), version(long @Version)을 명시 mapping하며 entity constructor에서 singleton을 insert하지 않습니다.

AppBootstrapStateJpaRepository는 `JpaRepository<AppBootstrapStateJpaEntity, Short>`를 확장하며 다음 메서드를 선언합니다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from AppBootstrapStateJpaEntity s where s.singletonKey=1")
Optional<AppBootstrapStateJpaEntity> findSingletonLocked();
```

adapter는 @Repository, @Transactional(propagation=MANDATORY)이며 조회 없음은 `IllegalStateException("Bootstrap state missing")`입니다. 잠금 조회 후 EntityManager.refresh(entity,PESSIMISTIC_WRITE)를 호출하고 domain으로 매핑합니다. complete도 같은 순서로 최신 상태를 확인하며, 입력은 completed 상태여야 하고 현재 상태는 미완료여야 합니다. version 불일치는 ObjectOptimisticLockingFailureException입니다. UUID/시각만 설정하고 flush합니다. 이미 완료된 상태를 덮어쓰지 않습니다.

AppUser.withAdministrator 핵심은 다음과 같습니다.

```java
public AppUser withAdministrator(Instant now) {
    Objects.requireNonNull(now, "now");
    if (status != AppUserStatus.ACTIVE) throw new IllegalStateException("Active user required");
    if (roles.contains(AppRole.APP_ADMIN)) return this;
    var nextRoles = new HashSet<>(roles);
    nextRoles.add(AppRole.APP_ADMIN);
    return new AppUser(id, issuer, subject, snapshot, status, nextRoles,
            createdAt, now, lastLoginAt, version);
}
```

- [ ] **Step 4: 사용자 저장 확장과 stale/완료 보호 테스트**

AppUserRepositoryAdapter.addAdministrator는 기존 updateSnapshot과 같은 identity 잠금/refresh 방식으로 현재 entity를 얻습니다. 전달 user.id와 현재 domain.id가 다르면 IllegalArgumentException, version 불일치는 ObjectOptimisticLockingFailureException입니다. 도메인의 withAdministrator로 ACTIVE 조건을 검증한 뒤 실제 role이 추가될 때만 entity를 변경합니다.

```java
void addAdministrator(Instant now) {
    if (roles.add(AppRole.APP_ADMIN)) updatedAt = now;
}
```

위 메서드는 AppUserJpaEntity 내부에 두며 adapter가 검증 후 호출합니다. flush 후 toDomain으로 최신 version을 반환합니다. 공통 잠금/refresh 코드가 반복되면 adapter 내부 private helper로만 추출하고 다른 저장 정책을 바꾸지 않습니다.

저장 테스트에는 MANDATORY의 transaction 밖 호출 거절, stale user/state version 거절, 이미 완료된 state 재저장 거절, DISABLED 직접 승격 거절을 추가합니다. 예외 타입을 구체적으로 단언하고 실패 후 별도 query로 DB 불변을 확인합니다. 동일 관리자 추가는 role 중복이나 불필요한 version 증가가 없어야 합니다.

- [ ] **Step 5: GREEN과 커밋**

동일 focused 명령 후 `./gradlew.bat test`로 기존 36개 회귀까지 통과시킵니다. 정확한 명령/총수/실패 수를 기록합니다. 표의 3A 파일만 stage하고 `git diff --cached --check`, `git commit -m "feat: persist one-time reference admin bootstrap state"`를 실행합니다.

### Task 3B: 후보 정책과 상위 조율 서비스

**Files:** Create J/user/application/AppAdminBootstrapService.java, J/user/application/AppLoginProvisioningService.java, T/user/AppAdminBootstrapIntegrationTest.java.

**Interfaces:** Consumes: 3A 저장 port와 기존 `AppUserView AppUserProvisioningService.provision(ExternalIdentityProfile)`. Produces: `AppUser AppAdminBootstrapService.bootstrap(AppBootstrapState state, AppUser current)` 및 `AppUserView AppLoginProvisioningService.provision(ExternalIdentityProfile profile)`.

- [ ] **Step 1: 후보·반환 결과 실패 테스트 작성**

AppAdminBootstrapIntegrationTest는 BootstrapIntegrationSupport를 상속하고 새 조율 서비스를 @Autowired합니다.

```java
@Test void firstEligibleUserGetsLocalAdminAndMatchingState() {
    var result = service.provision(profile("eligible", Set.of("COMPANY_ADMIN")));
    assertThat(result.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
    assertThat(result.version()).isGreaterThan(0);
    assertThat(jdbc.queryForObject("select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
            UUID.class)).isEqualTo(result.id());
    assertThat(jdbc.queryForObject("select bootstrapped_at from app_bootstrap_state where singleton_key=1",
            java.sql.Timestamp.class)).isNotNull();
}
```

일반 사용자→적격 후보, DISABLED COMPANY_ADMIN→다른 ACTIVE 후보, 기존 일반 사용자→현재 COMPANY_ADMIN 자격 획득, 완료 후 다른 후보, 최초 관리자의 HR 역할 제거를 각각 테스트합니다. DISABLED 준비는 기존 JIT로 먼저 만든 뒤 test transaction에서 사용자 FOR UPDATE→status/version UPDATE를 수행합니다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew.bat test --tests '*AppAdminBootstrapIntegrationTest'`

Expected: 신규 서비스 타입 부재. 환경 실패는 RED로 인정하지 않습니다.

- [ ] **Step 3: MANDATORY 정책 서비스 구현**

AppAdminBootstrapService는 @Service와 constructor injection(AppUserRepository users, AppBootstrapStateRepository states, Clock clock)을 사용합니다.

```java
@Transactional(propagation = Propagation.MANDATORY)
public AppUser bootstrap(AppBootstrapState state, AppUser current) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(current, "current");
    if (state.completed() || current.status() != AppUserStatus.ACTIVE
            || !current.snapshot().hrRoles().contains("COMPANY_ADMIN")) return current;
    var now = clock.instant();
    var promoted = users.addAdministrator(current, now);
    states.complete(state.complete(promoted.id(), now));
    return promoted;
}
```

이 메서드는 조율 서비스가 같은 트랜잭션에서 잠근 state/current만 전달하는 내부 계약입니다. 외부 HTTP endpoint나 검증되지 않은 입력을 연결하지 않습니다.

- [ ] **Step 4: 상위 조율 서비스 구현**

constructor dependencies는 AppBootstrapStateRepository states, AppUserProvisioningService provisioning, AppUserRepository users, AppAdminBootstrapService bootstrap입니다.

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public AppUserView provision(ExternalIdentityProfile profile) {
    Objects.requireNonNull(profile, "profile");
    var state = states.findSingletonForUpdate();
    var provisioned = provisioning.provision(profile);
    var current = users.findByIdentityForUpdate(provisioned.issuer(), provisioned.subject())
            .orElseThrow(() -> new IllegalStateException("Provisioned user missing"));
    return AppUserView.from(bootstrap.bootstrap(state, current));
}
```

@Service를 적용하고 기본 REQUIRED를 유지합니다. 완료 시에도 singleton 잠금 순서를 유지합니다. 기존 JIT 반환을 그대로 돌려주어 APP_ADMIN/최종 version을 누락하지 않습니다.

- [ ] **Step 5: 완료 기록 불변과 상태 유지 검증**

최초 관리자 생성 후 bootstrap row 전체를 `jdbc.queryForMap`으로 보관합니다. test SQL로 사용자 비활성화 또는 APP_ADMIN 제거를 각각 수행하고 새 적격 후보를 provision한 뒤 아래를 확인합니다.

```java
assertThat(next.roles()).containsExactly(AppRole.APP_USER);
assertThat(jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1"))
        .isEqualTo(completedBefore);
```

`next`는 새 후보 반환값, `completedBefore`는 정책 변경 전 저장한 row입니다. 실제 관리 API나 마지막 관리자 보호는 추가하지 않습니다. DISABLED 사용자의 재호출은 상태 보존 및 미승격을 확인합니다.

- [ ] **Step 6: GREEN과 커밋**

focused 명령과 `./gradlew.bat test`를 실행합니다. 표의 3B 3개 파일만 stage, `git diff --cached --check`, `git commit -m "feat: coordinate jit and initial administrator bootstrap"`를 실행합니다.

### Task 3C: 실제 경쟁과 실패 원자성 검증

**Files:** Create T/user/AppAdminBootstrapConcurrencyIntegrationTest.java, T/user/AppAdminBootstrapRollbackIntegrationTest.java; Modify T/support/BootstrapIntegrationSupport.java.

**Interfaces:** Consumes: `AppLoginProvisioningService.provision`, 전용 DB, TransactionTemplate. Produces: 동시 후보 단일 승자, 실패 승자 교체, 신규/기존 rollback의 재현 가능한 검증. 기본적으로 production 변경은 없습니다. 결함이 발견되면 최소 수정 범위를 controller와 정리합니다.

- [ ] **Step 1: 경쟁 도우미와 두 후보 테스트 작성**

기존 AppUserProvisioningConcurrencyIntegrationTest의 deadline/latch 패턴을 읽고, 새 support에 아래 helper를 둡니다. 기존 Task 2 테스트를 불필요하게 리팩터링하지 않습니다.

```java
protected void awaitDatabaseLock(int pid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                Boolean.class, pid))) return;
        Thread.sleep(10);
    }
    throw new AssertionError("Expected database lock wait");
}
```

두 worker는 각자 독립 트랜잭션에서 조율 서비스를 호출합니다. 한 테스트는 CyclicBarrier(2) 뒤 서로 다른 subject, 다른 테스트는 같은 subject를 사용합니다. Future.get(10,SECONDS), barrier.await(5,SECONDS), finally shutdownNow 및 awaitTermination(5,SECONDS)를 적용합니다. 완료 뒤 APP_ADMIN role count=1, bootstrap UUID가 승자와 일치하는지, 같은 subject면 사용자 UUID도 같은지 확인합니다.

- [ ] **Step 2: 실제 singleton 잠금 대기 검증**

A는 외부 tx에서 singleton FOR UPDATE를 얻고 ready latch를 알린 뒤 release latch를 기다립니다. B는 PID 공개 후 조율 서비스를 호출합니다. 테스트 main 스레드는 tx 밖에서 실제 잠금 대기를 확인합니다.

```java
var backendPid = new CompletableFuture<Integer>();
var pending = executor.submit(() -> tx.execute(status -> {
    backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
    return service.provision(profile("second", Set.of("COMPANY_ADMIN")));
}));
awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
```

A는 release 후 같은 tx에서 첫 후보를 provision하고 커밋합니다. B는 APP_USER만 반환해야 합니다. 모든 release latch는 finally에서도 해제하며 모든 Future 결과를 수집합니다. 잠금 대기 확인 전에 임의 sleep으로 A/B 순서를 추정하지 않습니다.

- [ ] **Step 3: 먼저 잠근 후보 실패 후 다음 후보 성공 검증**

Step 2의 A가 provision까지 수행한 뒤 latch로 커밋을 보류하게 합니다. B의 실제 잠금 대기를 확인한 후 A를 해제하면서 `IllegalStateException("forced winner rollback")`을 발생시킵니다. A Future의 ExecutionException 원인이 해당 예외/메시지인지 확인하고, B는 APP_ADMIN이어야 합니다. A의 user/role은 없고 bootstrap은 B를 가리켜야 합니다.

- [ ] **Step 4: 저장 실패 주입과 외부 rollback 테스트 작성**

RollbackIntegrationTest에서 신규/기존 사용자 각각에 대해 역할 저장 실패와 bootstrap 완료 저장 실패를 @ParameterizedTest/MethodSource로 조합합니다. setup용 DDL은 아래 의도한 예외 assertion 밖에서 실행합니다. trigger는 같은 외부 tx에 만들어 rollback으로 제거합니다.

```sql
CREATE FUNCTION fail_bootstrap_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'forced bootstrap role failure'; END $$;
CREATE TRIGGER fail_bootstrap_role BEFORE INSERT ON app_user_role
FOR EACH ROW WHEN (NEW.role = 'APP_ADMIN') EXECUTE FUNCTION fail_bootstrap_role();

CREATE FUNCTION fail_bootstrap_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'forced bootstrap completion failure'; END $$;
CREATE TRIGGER fail_bootstrap_completion BEFORE UPDATE ON app_bootstrap_state
FOR EACH ROW WHEN (NEW.bootstrapped_user_id IS NOT NULL) EXECUTE FUNCTION fail_bootstrap_completion();
```

각 케이스에 필요한 function/trigger 한 쌍만 생성합니다. tx 내부에서 assertThatThrownBy(service.provision)를 해당 root-cause 메시지까지 검사하고 status.setRollbackOnly()를 설정합니다. tx 종료 후 fresh query로 검증합니다. 기존 사용자 준비는 JIT만 사용하여 bootstrap은 미완료로 두고, 새 profile에서는 email/name/company/organization/hrRoles를 모두 변경하여 각 사본 원복을 확인합니다.

전체 행 비교는 다음 형태이며 역할 목록과 bootstrap 행도 별도로 비교합니다.

```java
var before = jdbc.queryForMap("select * from app_user where id=?", existing.id());
assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
    service.provision(changedProfile);
    throw new IllegalStateException("forced outer rollback");
})).isInstanceOf(IllegalStateException.class).hasMessage("forced outer rollback");
assertThat(jdbc.queryForMap("select * from app_user where id=?", existing.id())).isEqualTo(before);
```

`existing`은 미리 JIT로 만든 AppUserView, `changedProfile`은 같은 identity로 선택 정보와 COMPANY_ADMIN이 들어 있는 ExternalIdentityProfile입니다. 신규 케이스는 user/role이 0건, 기존 케이스는 모든 snapshot/시각/version/역할이 이전과 같은지 확인합니다. 외부 rollback도 신규/기존 각각 수행합니다.

- [ ] **Step 5: singleton 누락 검증**

신규/기존 두 케이스에서 전용 test DB의 singleton을 제거한 뒤 provision이 정확히 `IllegalStateException("Bootstrap state missing")`으로 실패하는지 확인합니다. 신규 user가 없고 기존 user 행은 변하지 않아야 합니다. 자동 singleton 재생성도 없어야 합니다. 다음 테스트는 support의 초기화로 복구합니다.

- [ ] **Step 6: 검증 실행과 테스트 민감도 증거 확보**

Run: `./gradlew.bat test --tests '*AppAdminBootstrapConcurrencyIntegrationTest' --tests '*AppAdminBootstrapRollbackIntegrationTest'`

3C는 이미 구현된 정책을 검증하므로 최초 실행부터 통과할 수 있습니다. 이를 가짜 RED로 기록하지 않습니다. 테스트 민감도는 bootstrap 정책의 완료 여부 검사를 잠시 제거하여 확인합니다. 완료 후 새 후보가 정상 APP_USER로 반환되어야 하는 테스트가 예외 또는 잘못된 결과로 실패해야 합니다. 임시 변경은 apply_patch로 적용/원복하고 해당 focused 테스트만 실행합니다. git restore/reset으로 사용자 변경을 지우지 않습니다. mutation 실패 원인은 의도한 동작 변화여야 하며 compilation/환경 오류는 인정하지 않습니다. commit 전에 임시 production 변경이 남지 않았음을 diff로 확인합니다.

전체 검증:

```powershell
./gradlew.bat clean test
```

Expected: 새 bootstrap 테스트와 기존 36개 회귀 모두 PASS, 실패/오류/스킵 0. 실행 명령, 실제 총수, RED 또는 mutation 증거를 보고서에 기록합니다. 기존 JVM CDS warning은 기준 환경 경고로 명시하고 기능 실패로 오인하지 않습니다.

- [ ] **Step 7: 독립성 검사와 커밋**

worktree root에서 실행합니다.

```powershell
rg -n 'com\.sweet\.authstudy|jdbc:postgresql://localhost:5432' reference-app/backend/src/main
rg -n 'org\.springframework|jakarta\.persistence|user\.application' reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain
git diff --check
git status --short
```

검색은 no matches, diff check는 성공이어야 합니다. 3C 두 테스트와 support 변경만 stage하고 `git commit -m "test: verify admin bootstrap concurrency and rollback"`를 실행합니다. 실제 결함 수정이 필요했다면 승인된 최소 production 수정도 명시적으로 포함하고 보고합니다.

## 자체 검토 및 인수 기준 매핑

| 설계 검증 항목 | 구현/검증 위치 |
|---|---|
| 1 최종 관리자/UUID/시각/version | 3A 저장, 3B 최초 후보 |
| 2–4 부적격/비활성/기존 사용자 자격 | 3B 정책 테스트 |
| 5–7 다른 후보·같은 후보·실패 후 승계 | 3C 경쟁 테스트 |
| 8–9 역할·완료 저장 실패, 외부 rollback | 3C 신규/기존 조합 |
| 10–11 완료 후 HR/로컬 정책 변경 독립성 | 3B 완료 기록 불변 테스트 |
| 12 singleton 누락 | 3C 신규/기존 무변경 |
| 13 회귀·독립성 | 3C 전체 clean test 및 검색 |

상위 Task 3의 파일 목록에 조율 서비스와 기존 역할 저장 확장을 보완했습니다. Task 2 JIT 구현과 V1은 유지하고, public 호출 계약은 AppLoginProvisioningService.provision 하나로 명확히 했습니다. 현재 문서는 구현 계획이며 아직 Task 3 코드나 테스트를 실행한 결과가 아닙니다.
