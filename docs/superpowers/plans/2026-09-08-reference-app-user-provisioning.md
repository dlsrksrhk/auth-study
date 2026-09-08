# Reference App Task 2 User Provisioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검증된 외부 identity에서 독립 앱 사용자를 원자적으로 생성·갱신하고, 실제 PostgreSQL에서 중복 생성과 로컬 권한 유실을 방지합니다.

**Architecture:** 순수 사용자 도메인과 JPA 저장 모델을 분리합니다. 최초 삽입은 identity 한정 ON CONFLICT DO NOTHING, 기존 행 처리는 잠금 조회 후 외부 사본만 갱신합니다. 서비스는 REQUIRED 트랜잭션에 참여하여 후속 bootstrap과 함께 롤백할 수 있습니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Gradle 9.5.1, Spring Data JPA/Hibernate, Flyway, PostgreSQL 17, JUnit 5, AssertJ, Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-08-reference-app-user-provisioning-design.md`

## Global Constraints

- 상위 `docs/superpowers/plans/2026-08-21-oauth-oidc-reference-app.md`의 **Task 2만** 구현합니다. 아래 2A–2C는 그 작업의 하위 단위입니다.
- Task 1은 완료되었습니다. scaffold를 다시 만들거나 Gradle 8.x로 내리지 않습니다.
- 도메인 모델과 JPA 저장 모델을 분리합니다.
- 외부 사용자 식별키는 `(issuer, subject)`이며 DB 고유 제약을 둡니다. 이메일은 식별키가 아니고 중복을 허용합니다.
- 이름·이메일·회사·조직의 부재는 null, HR 역할의 부재는 빈 집합으로 표현합니다.
- 기존 DISABLED 사용자를 자동 활성화하거나 기존 APP_ADMIN을 제거하지 않습니다.
- 원본 UserInfo 전체를 저장하지 않으며 IdP 내부 숫자 account/user/company ID와 OAuth 토큰은 입력 계약·저장 모델·반환 모델에 포함하지 않습니다.
- Task 3는 bootstrap_state → app_user 순서로 잠금을 얻고, JIT와 최초 관리자 지정을 같은 트랜잭션에서 처리해야 합니다.
- HTTP 로그인·토큰 검증·관리 API·SPA·bootstrap 동작은 추가하지 않습니다. UserInfo sub 불일치로 호출을 막는 테스트는 Task 5입니다.
- `frontend/.idea/`와 무관한 변경은 건드리지 않습니다. main 병합·push는 실행 범위가 아닙니다.
- 각 하위 작업은 실패 테스트 작성 → 실제 실패 확인 → 구현 → 통과 확인 → 명시적 파일 커밋 순서입니다.

---

## 작업 위치와 사전 확인

기존 worktree `C:/dev/auth-study/.worktrees/oidc-reference-app`, branch `codex/oidc-reference-app`를 사용합니다. 설계 커밋은 `560d899`입니다. 실행 시작 시 status와 HEAD를 다시 확인하고 사용자 변경을 보존합니다. 문서의 경로는 worktree 기준입니다.

```powershell
git status --short
git log -3 --oneline
git check-ignore .superpowers
```

테스트 명령의 작업 디렉터리는 `reference-app/backend`입니다. 우선 `./gradlew.bat test`로 기존 smoke가 통과하는지 확인합니다. Docker unavailable 같은 환경 실패는 요구한 RED 증거로 취급하지 않습니다. 기존 `PostgresContainerConfiguration`을 재사용하고 로컬 5432/55433 DB 데이터는 수정하지 않습니다.

## 파일과 책임

Java 루트는 `reference-app/backend/src/main/java/com/sweet/referenceapp`, 테스트 루트는 `reference-app/backend/src/test/java/com/sweet/referenceapp`입니다. 아래 경로를 이 루트에 결합한 것이 정확한 생성 경로입니다.

| 하위 작업 | Java 파일 | 책임 |
|---|---|---|
| 2A | user/domain/AppUser.java | 불변 사용자, 생성 및 외부 사본 교체 |
| 2A | user/domain/AppRole.java, AppUserStatus.java | 로컬 enum |
| 2A | user/domain/ExternalUserSnapshot.java | 불변·허용 필드 기반 외부 사본 |
| 2A | user/application/ExternalIdentityProfile.java | 검증된 identity 입력 계약 |
| 2A | user/application/AppUserView.java | 토큰 없는 결과 매핑 |
| 2B | user/domain/AppUserRepository.java | 저장 port |
| 2B | user/infrastructure/AppUserJpaEntity.java | JPA/JSON/role 매핑 |
| 2B | user/infrastructure/AppUserJpaRepository.java | identity 잠금 조회 |
| 2B | user/infrastructure/AppUserRepositoryAdapter.java | 충돌 회피 삽입, 저장 변환 |
| 2C | user/application/AppUserProvisioningService.java | 트랜잭션과 유스케이스 |
| 2C | user/application/ProvisioningTimeConfiguration.java | 교체 가능한 UTC Clock bean |

추가 생성 파일:

- `reference-app/backend/src/main/resources/db/migration/V1__app_user.sql` (2B)
- 테스트 루트 `user/AppUserTest.java`, `user/ExternalIdentityProfileTest.java` (2A)
- 테스트 루트 `user/AppUserPersistenceIntegrationTest.java` (2B)
- 테스트 루트 `user/AppUserProvisioningIntegrationTest.java`, `user/AppUserProvisioningConcurrencyIntegrationTest.java`, `user/AppUserBoundaryTest.java` (2C)

기존 build/config 파일과 IdP 소스는 수정하지 않습니다. 테스트용 `ddl-auto: validate`가 이미 있습니다. JPA 클래스의 기본 생성자·필드 매핑·변환 접근자는 구현하되 엔티티를 서비스 반환형으로 노출하지 않습니다.

## 확정된 상세 계약

### 외부 사본과 입력

`ExternalUserSnapshot` record fields: `String email, String displayName, Map<String,Object> company, Map<String,Object> organization, Set<String> hrRoles`.

공개 JSON 구조는 현재 IdP `backend/src/main/java/com/sweet/authstudy/oauth/infrastructure/OidcUserInfoMapper.java`에서 읽기만 확인했습니다. 아래 구조를 Reference App 자체 코드로 정의하며 IdP 타입/상수를 import하지 않습니다.

```json
{
  "company": {"code": "DEMO", "name": "Demo"},
  "organization": {
    "position": {"code": "DEV", "name": "Developer"},
    "primary_department": {"code": "ENG", "name": "Engineering"},
    "secondary_departments": [{"code": "QA", "name": "Quality"}]
  }
}
```

company와 각 code/name 객체는 두 필드만 허용하고 둘 다 nonblank String이어야 합니다. organization은 위 세 key만 허용하며 position/primary_department는 생략할 수 있고 secondary_departments 생략은 빈 목록으로 정규화합니다. 알려지지 않은 key와 잘못된 타입은 `IllegalArgumentException`으로 거절합니다. 임의 key 삭제 후 성공시키지 않습니다. null company/organization은 그대로 null입니다. 역할은 null→빈 Set, null/blank 원소는 거절하되 외부 역할 이름을 로컬 enum으로 변환하지 않습니다. 오류 메시지와 로그에 입력값 전체를 넣지 않습니다.

`ExternalIdentityProfile`의 canonical constructor는 issuer의 absolute http/https URI, host 존재, user-info/query/fragment 부재, issuer 문자열 길이 ≤1024, subject nonblank 및 길이 ≤255를 검사합니다. dev HTTP는 허용하며 issuer 신뢰 판정은 하지 않습니다. identity는 trim/normalize하지 않고 issuer.toString() 원문 표현을 DB 비교에 사용합니다. URI.equals()로 identity를 합치지 않습니다. email/displayName은 null 또는 String 값을 보존하며 이메일 문법/유일성 검사를 추가하지 않습니다.

```java
public record ExternalIdentityProfile(URI issuer, String subject,
        String email, String displayName, Map<String, Object> company,
        Map<String, Object> organization, Set<String> hrRoles) {
    public ExternalUserSnapshot snapshot() {
        return new ExternalUserSnapshot(email, displayName, company, organization, hrRoles);
    }
}
```

constructor에서 snapshot을 한 번 생성해 복사·검증된 각 필드를 record component에 재할당합니다. `Map.copyOf`, `List.copyOf`, `Set.copyOf`를 각 중첩 단계에 사용합니다. 도메인은 application 타입을 import하지 않습니다.

### 사용자와 저장 port

`AppUser`는 record로 다음 필드를 순서대로 가집니다: `UUID id, String issuer, String subject, ExternalUserSnapshot snapshot, AppUserStatus status, Set<AppRole> roles, Instant createdAt, Instant updatedAt, Instant lastLoginAt, long version`.

```java
public static AppUser create(UUID id, String issuer, String subject,
        ExternalUserSnapshot snapshot, Instant now) {
    return new AppUser(id, issuer, subject, snapshot, AppUserStatus.ACTIVE,
            Set.of(AppRole.APP_USER), now, now, now, 0);
}
public AppUser replaceSnapshot(ExternalUserSnapshot next, Instant now) {
    return new AppUser(id, issuer, subject, next, status, roles,
            createdAt, now, now, version);
}
```

version 증가 책임은 저장 계층입니다. AppUser constructor는 required/null, nonnegative version, 역할 집합 불변 복사와 APP_USER 포함을 검사합니다. enum은 `AppRole { APP_USER, APP_ADMIN }`, `AppUserStatus { ACTIVE, DISABLED }`입니다. 기존 사용자 재구성은 record constructor를 사용합니다.

`AppUserView`는 AppUser와 같은 필드 타입/순서를 가지며 `static AppUserView from(AppUser user)`가 각각의 accessor를 복사합니다. 이 단계에서는 REST response나 principal을 만들지 않습니다.

```java
public interface AppUserRepository {
    boolean insertIfAbsent(AppUser candidate);
    Optional<AppUser> findByIdentityForUpdate(String issuer, String subject);
    AppUser updateSnapshot(AppUser user);
}
```

모든 port 메서드는 열린 트랜잭션 안에서 사용합니다. adapter는 `@Transactional(propagation = Propagation.MANDATORY)`로 이를 강제합니다. updateSnapshot은 identity/status/roles를 저장하지 않고 snapshot 및 기록만 반영하며 전달된 version과 일치하는지 확인합니다.

### SQL/트랜잭션 선택

READ_COMMITTED에서 아래 두 문장을 별도로 실행합니다. 삽입 충돌이면 다음 잠금 조회가 커밋된 승자 행을 읽습니다. DO NOTHING과 SELECT를 한 CTE로 합치지 않습니다. 근거: PostgreSQL 17 [INSERT](https://www.postgresql.org/docs/17/sql-insert.html), [Read Committed](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED).

```sql
INSERT INTO app_user
 (id,issuer,subject,email,display_name,company_snapshot,organization_snapshot,
  hr_roles_snapshot,status,created_at,updated_at,last_login_at,version)
VALUES (:id,:issuer,:subject,:email,:displayName,cast(:company as jsonb),
 cast(:organization as jsonb),cast(:hrRoles as jsonb),'ACTIVE',:now,:now,:now,0)
ON CONFLICT (issuer,subject) DO NOTHING;

SELECT * FROM app_user WHERE issuer=:issuer AND subject=:subject FOR UPDATE;
```

insert는 같은 EntityManager의 native query로 executeUpdate합니다. 1행이면 candidate UUID의 APP_USER를 native insert하고, 0행이면 role에 손대지 않습니다. JSON은 ObjectMapper로 인코딩하여 parameter binding하며 null snapshot은 SQL NULL입니다. UUID PK 충돌 등 identity 이외 오류는 전파·롤백합니다. 예외를 잡아 같은 트랜잭션에서 재시도하지 않습니다.

후속 JPA 조회는 `@Lock(PESSIMISTIC_WRITE)` query를 사용하며 entity를 `EntityManager.refresh(entity, PESSIMISTIC_WRITE)`로 갱신한 후 domain으로 변환합니다. 오래된 영속성 컨텍스트 값을 재사용하지 않기 위해서입니다. roles는 같은 트랜잭션 안에서 읽습니다. 관리 entity의 snapshot/updatedAt/lastLoginAt만 변경하고 flush한 뒤 @Version 증가를 반영한 domain을 반환합니다. role collection은 교체하지 않습니다.

로컬 관리 처리도 부모 app_user를 잠근 뒤 role을 변경한다는 계약을 Task 7에 전달합니다. 이번 테스트에서는 SQL로 그 순서를 재현합니다. Task 3 상위 트랜잭션은 READ_COMMITTED이며 bootstrap singleton 잠금을 먼저 얻고 provision을 호출합니다.

---

### Task 2A: 불변 사용자 도메인과 입력 경계

**Files:** 위 2A의 production 6개, test 2개 파일을 생성합니다.

**Interfaces:** Consumes: 검증된 외부 identity와 공개 claim. Produces: ExternalIdentityProfile, ExternalUserSnapshot, AppUser.create/replaceSnapshot, AppUserView.from.

- [ ] **Step 1: 외부 입력 샘플과 실패 테스트 작성**

`AppUserTest`에 다음을 작성합니다. 각 테스트는 자체 데이터를 만들고 IdP fixture에 의존하지 않습니다.

```java
@Test void replacementPreservesLocalPolicyAndIdentity() {
    var t = Instant.parse("2026-09-08T00:00:00Z");
    var old = new ExternalUserSnapshot("a@example.test", "A", null, null, Set.of("COMPANY_ADMIN"));
    var user = new AppUser(UUID.randomUUID(), "http://idp.localhost:8080", "opaque-1",
            old, AppUserStatus.DISABLED, Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), t, t, t, 7);
    var cleared = new ExternalUserSnapshot(null, null, null, null, null);
    var next = user.replaceSnapshot(cleared, t.plusSeconds(1));
    assertThat(next.id()).isEqualTo(user.id());
    assertThat(next.status()).isEqualTo(AppUserStatus.DISABLED);
    assertThat(next.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
    assertThat(next.snapshot()).isEqualTo(cleared);
    assertThat(next.createdAt()).isEqualTo(t);
    assertThat(next.lastLoginAt()).isEqualTo(t.plusSeconds(1));
    assertThat(next.version()).isEqualTo(7);
}
```

`ExternalIdentityProfileTest`에는 필수 identity/길이/URI 구조, 미지원 key, 잘못된 타입, null role 원소, null→빈 role 변환 및 깊은 복사 테스트를 추가합니다.

```java
@Test void rejectsInternalIdAndWrongClaimShape() {
    assertThatThrownBy(() -> new ExternalUserSnapshot(null, null,
            Map.of("code", "DEMO", "name", "Demo", "id", 42), null, Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExternalUserSnapshot(null, null, null,
            Map.of("secondary_departments", "not-a-list"), Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
}
@Test void freezesNestedClaims() {
    var department = new HashMap<String,Object>(Map.of("code", "ENG", "name", "Engineering"));
    var departments = new ArrayList<Map<String,Object>>();
    departments.add(department);
    var snapshot = new ExternalUserSnapshot(null, null, null,
            Map.of("secondary_departments", departments), Set.of());
    department.put("name", "Changed");
    departments.clear();
    assertThat(snapshot.organization().get("secondary_departments"))
            .isEqualTo(List.of(Map.of("code", "ENG", "name", "Engineering")));
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew.bat test --tests '*AppUserTest' --tests '*ExternalIdentityProfileTest'`

Expected: 새 타입 부재로 compilation FAIL. 실제 원인을 기록합니다.

- [ ] **Step 3: enum, record, 공개claim 복사 구현**

위 상세 계약의 create/replaceSnapshot 코드를 구현합니다. 허용key 검사는 아래 형태로 snapshot record의 private helper에 둡니다. company/codeName, organization, list 각각 shape를 검사한 후 immutable copy합니다.

```java
private static void requireKeys(Map<String, Object> value, Set<String> allowed) {
    if (!allowed.containsAll(value.keySet())) {
        throw new IllegalArgumentException("Unsupported snapshot field");
    }
}
```

AppUserView.from은 AppUser accessor 전체를 record constructor에 전달합니다. AppUser/domain에서 application/JPA/Spring을 import하지 않습니다.

- [ ] **Step 4: GREEN 확인과 생성·반환형 회귀 추가**

같은 테스트 명령을 실행해 통과시킵니다. 아래 assertion을 새 생성 테스트에 넣고 다시 실행합니다.

```java
var user = AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", "opaque-1",
        new ExternalUserSnapshot(null, null, null, null, Set.of()), Instant.EPOCH);
assertThat(user.status()).isEqualTo(AppUserStatus.ACTIVE);
assertThat(user.roles()).containsExactly(AppRole.APP_USER);
assertThat(AppUserView.from(user).id()).isEqualTo(user.id());
```

- [ ] **Step 5: 명시적 파일 커밋**

worktree root에서 위2A의6 production파일과2 test파일만 `git add` 후 `git diff --cached --check`, `git commit -m "feat: model independent reference app users"`를 실행합니다. wildcard/add-all은 사용하지 않습니다.

### Task 2B: Flyway 스키마와 충돌 안전 저장 어댑터

**Files:** 위2B의4 production파일, V1 migration, AppUserPersistenceIntegrationTest를 생성합니다.

**Interfaces:** Consumes: 2A AppUser/ExternalUserSnapshot. Produces: AppUserRepository의 insertIfAbsent/findByIdentityForUpdate/updateSnapshot, 세테이블과 singleton.

- [ ] **Step 1: 실제 PostgreSQL schema/port 테스트 작성**

테스트 클래스는 `@SpringBootTest`, `@Import(PostgresContainerConfiguration.class)`, `@ActiveProfiles("test")`를 사용합니다. 클래스 수준 `@Transactional`을 붙이지 않습니다. JdbcTemplate과 PlatformTransactionManager를 주입하고 TransactionTemplate은 직접 만듭니다. identity는 테스트마다 UUID 문자열로 격리합니다.

```java
@Autowired JdbcTemplate jdbc;
@Autowired PlatformTransactionManager transactionManager;
TransactionTemplate tx;
@BeforeEach void transactions() {
    tx = new TransactionTemplate(transactionManager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
}
```

```java
@Test void bootstrapStartsUnassigned() {
    var row = jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1");
    assertThat(row.get("bootstrapped_user_id")).isNull();
    assertThat(row.get("bootstrapped_at")).isNull();
    assertThat(jdbc.queryForObject("select count(*) from app_bootstrap_state", Long.class)).isEqualTo(1L);
}
```

port 구현 전에는schema 테스트만 먼저실행하여 table부재 RED를 확보합니다. 이후port 테스트를 추가하고 별도 RED를 확인합니다.

```java
@Test void duplicateIdentityDoesNotDuplicateUserOrRoles() {
    var sub = UUID.randomUUID().toString();
    var snapshot = new ExternalUserSnapshot("same@example.test", null, null, null, Set.of());
    var first = AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", sub, snapshot, Instant.EPOCH);
    var second = AppUser.create(UUID.randomUUID(), first.issuer(), sub, snapshot, Instant.EPOCH);
    tx.executeWithoutResult(status -> {
        assertThat(repository.insertIfAbsent(first)).isTrue();
        assertThat(repository.insertIfAbsent(second)).isFalse();
        assertThat(repository.findByIdentityForUpdate(first.issuer(), sub).orElseThrow().id()).isEqualTo(first.id());
    });
    assertThat(jdbc.queryForObject("select count(*) from app_user_role where app_user_id=?",
            Long.class, first.id())).isEqualTo(1L);
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew.bat test --tests '*AppUserPersistenceIntegrationTest'`

Expected: migration 전에는 테이블 부재, port 테스트 추가 후에는 타입/bean 부재입니다. 환경 오류는 RED가 아닙니다.

- [ ] **Step 3: V1 migration 작성**

```sql
CREATE TABLE app_user (
 id uuid PRIMARY KEY,
 issuer varchar(1024) COLLATE "C" NOT NULL CHECK (length(btrim(issuer)) > 0),
 subject varchar(255) COLLATE "C" NOT NULL CHECK (length(btrim(subject)) > 0),
 email text, display_name text,
 company_snapshot jsonb CHECK (jsonb_typeof(company_snapshot) = 'object'),
 organization_snapshot jsonb CHECK (jsonb_typeof(organization_snapshot) = 'object'),
 hr_roles_snapshot jsonb NOT NULL CHECK (jsonb_typeof(hr_roles_snapshot) = 'array'),
 status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 last_login_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 CONSTRAINT uk_app_user_identity UNIQUE (issuer,subject)
);
CREATE TABLE app_user_role (
 app_user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 role varchar(16) NOT NULL CHECK (role IN ('APP_USER','APP_ADMIN')),
 PRIMARY KEY (app_user_id,role)
);
CREATE TABLE app_bootstrap_state (
 singleton_key smallint PRIMARY KEY CHECK (singleton_key = 1),
 bootstrapped_user_id uuid REFERENCES app_user(id),
 bootstrapped_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 CHECK ((bootstrapped_user_id IS NULL) = (bootstrapped_at IS NULL))
);
INSERT INTO app_bootstrap_state(singleton_key) VALUES (1);
```

- [ ] **Step 4: JPA mapping과 adapter 구현**

상세 계약의 native insert와 role insert를 EntityManager로 실행합니다. JPA entity는 모든 app_user 열을 명시적으로 매핑하며 issuer/subject/createdAt/id는 갱신 불가, status는 STRING enum입니다. JSON은 `@JdbcTypeCode(SqlTypes.JSON)` 및 columnDefinition="jsonb", 시각은 Instant, version은 `@Version long version`입니다. roles는 다음과 같이 매핑합니다.

```java
@ElementCollection
@CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "app_user_id"))
@Column(name = "role")
@Enumerated(EnumType.STRING)
private Set<AppRole> roles = new HashSet<>();

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select u from AppUserJpaEntity u where u.issuer=:issuer and u.subject=:subject")
Optional<AppUserJpaEntity> findLocked(@Param("issuer") String issuer, @Param("subject") String subject);
```

위 repository 메서드는 `JpaRepository<AppUserJpaEntity, UUID>`에 둡니다. adapter의 updateSnapshot 순서는 findLocked→refresh→version 비교→외부 필드만 설정→flush→domain 변환입니다. version 불일치는 `ObjectOptimisticLockingFailureException(AppUserJpaEntity.class, user.id())`, 대상 부재는 IllegalStateException입니다. entity의 toDomain()은 2A constructor에 모든 필드를 전달합니다.

- [ ] **Step 5: DB 제약·갱신·롤백 검증 추가**

같은 테스트 클래스에서 다른 identity의 이메일 중복 허용, role FK/복합 PK, singleton 다른 key 거절, bootstrap 한쪽만 null 거절, 잘못된 status 거절을 확인합니다. 제약 위반은 각각 독립 트랜잭션에서 실행합니다. snapshot을 두 번 갱신하여 version 증가, createdAt 유지, null 사본/빈 HR 역할의 JSON 왕복을 확인합니다.

```java
assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
    repository.insertIfAbsent(first);
    throw new IllegalStateException("forced rollback");
})).isInstanceOf(IllegalStateException.class);
assertThat(jdbc.queryForObject("select count(*) from app_user where id=?", Long.class, first.id())).isZero();
assertThat(jdbc.queryForObject("select count(*) from app_user_role where app_user_id=?", Long.class, first.id())).isZero();
```

`first`는 각 테스트에서 아직 저장하지 않은 AppUser.create 결과입니다. role 삽입 자체의 실패도 테스트 전용 트랜잭션 안의 trigger로 주입하여 사용자 행이 남지 않는지 확인합니다. DDL도 같은 트랜잭션에서 롤백되므로 다른 테스트에 trigger를 남기지 않습니다.

```sql
CREATE FUNCTION fail_role_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'forced role failure'; END $$;
CREATE TRIGGER fail_role_insert BEFORE INSERT ON app_user_role
FOR EACH ROW EXECUTE FUNCTION fail_role_insert();
```

- [ ] **Step 6: GREEN 확인과 커밋**

Run: `./gradlew.bat test --tests '*AppUserPersistenceIntegrationTest'`

Expected: migration/JPA validate, 제약, JSON 왕복, 롤백 전부 PASS. 2B의 6개 파일만 stage하고 `git diff --cached --check` 후 `git commit -m "feat: persist reference users with conflict-safe identity creation"`을 실행합니다.

### Task 2C: 프로비저닝 서비스와 경쟁·경계 검증

**Files:** 위 2C의 production 2개, test 3개 파일을 생성합니다.

**Interfaces:** Consumes: ExternalIdentityProfile과 AppUserRepository. Produces: `AppUserView AppUserProvisioningService.provision(ExternalIdentityProfile profile)`. constructor는 `(AppUserRepository repository, Clock clock)`입니다.

- [ ] **Step 1: 서비스 계약 실패 테스트 작성**

IntegrationTest에 `@SpringBootTest`, `@Import(PostgresContainerConfiguration.class)`, `@ActiveProfiles("test")`를 적용하고 클래스 수준 트랜잭션은 사용하지 않습니다. helper `profile(String issuer,String sub,String email)`은 `new ExternalIdentityProfile(URI.create(issuer),sub,email,"Name",null,null,Set.of("COMPANY_ADMIN"))`을 반환합니다.

```java
@Test void repeatLoginKeepsIdentityWithoutBootstrapping() {
    var sub = UUID.randomUUID().toString();
    var a = service.provision(profile("http://idp.localhost:8080", sub, "a@example.test"));
    var b = service.provision(profile("http://idp.localhost:8080", sub, "b@example.test"));
    assertThat(b.id()).isEqualTo(a.id());
    assertThat(b.snapshot().email()).isEqualTo("b@example.test");
    assertThat(b.roles()).containsExactly(AppRole.APP_USER);
    assertThat(b.version()).isGreaterThan(a.version());
}
```

issuer 차이/sub 차이/이메일 중복의 별도 UUID, null 선택 정보의 이전 사본 전체 제거, 로컬 DISABLED+APP_ADMIN 보존을 테스트합니다. 로컬 정책 준비는 테스트 트랜잭션에서 부모 행을 FOR UPDATE한 뒤 status와 version을 UPDATE하고 APP_ADMIN을 INSERT하여 커밋합니다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew.bat test --tests '*AppUserProvisioningIntegrationTest'`

Expected: 서비스 타입/bean이 없어서 FAIL입니다.

- [ ] **Step 3: 서비스와 Clock 구현**

```java
@Bean
Clock provisioningClock() { return Clock.systemUTC(); }
```

위 bean은 `@Configuration(proxyBeanMethods=false)` ProvisioningTimeConfiguration에 둡니다. 서비스는 `@Service`이며 아래와 같이 구현합니다.

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public AppUserView provision(ExternalIdentityProfile profile) {
    Objects.requireNonNull(profile, "profile");
    var candidate = AppUser.create(UUID.randomUUID(), profile.issuer().toString(),
            profile.subject(), profile.snapshot(), clock.instant());
    boolean inserted = repository.insertIfAbsent(candidate);
    var current = repository.findByIdentityForUpdate(candidate.issuer(), candidate.subject())
            .orElseThrow(() -> new IllegalStateException("Provisioned identity missing"));
    if (inserted) return AppUserView.from(current);
    return AppUserView.from(repository.updateSnapshot(
            current.replaceSnapshot(profile.snapshot(), clock.instant())));
}
```

기본 propagation REQUIRED를 유지합니다. production에 sleep/retry/global lock을 넣지 않습니다. 입력 오류는 constructor에서 DB 접근 전에 거절하고, 서비스 실패를 빈 사본으로 변환하지 않습니다.

- [ ] **Step 4: 동시 최초 로그인 테스트와 실제 경쟁 강제**

ConcurrencyIntegrationTest는 ExecutorService, CyclicBarrier, Future.get(10, SECONDS)를 사용하며 finally에서 shutdownNow합니다. 서로 다른 identity로 20회 반복하며 각 worker는 독립 Spring 서비스 트랜잭션을 사용합니다.

```java
var barrier = new CyclicBarrier(2);
Callable<AppUserView> login = () -> {
    barrier.await(5, TimeUnit.SECONDS);
    return service.provision(profile("http://idp.localhost:8080", sub, "same@example.test"));
};
var left = executor.submit(login);
var right = executor.submit(login);
assertThat(left.get(10, TimeUnit.SECONDS).id()).isEqualTo(right.get(10, TimeUnit.SECONDS).id());
assertThat(jdbc.queryForObject("select count(*) from app_user where issuer=? and subject=?",
        Long.class, "http://idp.localhost:8080", sub)).isEqualTo(1L);
```

barrier만으로 실제 경쟁을 증명했다고 하지 않습니다. 별도 테스트에서 트랜잭션 A는 insertIfAbsent 후 커밋하지 않고 latch로 대기하며, B는 같은 identity로 service.provision을 시작합니다. B는 같은 TransactionTemplate connection의 `select pg_backend_pid()`를 공개합니다. 별도 connection에서 `pg_stat_activity.wait_event_type='Lock'`을 확인한 뒤 A를 해제합니다. B도 A의 UUID를 반환하고 role이 한 건인지 확인합니다. 폴링은 10ms 간격·5초 상한이며 timeout은 테스트 실패입니다. production 테스트 hook은 추가하지 않습니다.

아래 helper를 ConcurrencyIntegrationTest에 정의합니다. main 테스트 스레드는 트랜잭션 밖에서 호출하여 JDBC 조회가 worker connection을 공유하지 않게 합니다. A의 release latch는 실패 시에도 finally에서 countDown해야 합니다.

```java
private void awaitDatabaseLock(int pid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
        Boolean waiting = jdbc.queryForObject(
                "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                Boolean.class, pid);
        if (Boolean.TRUE.equals(waiting)) return;
        Thread.sleep(10);
    }
    throw new AssertionError("Expected database lock wait");
}
```

worker B의 PID 공개 및 결과 수집은 다음 형태입니다. `profile` helper는 이 테스트 클래스에도 동일한 정확한 constructor 계약으로 정의합니다. `sub`는 테스트별 UUID 문자열입니다.

```java
var backendPid = new CompletableFuture<Integer>();
var pending = executor.submit(() -> tx.execute(status -> {
    backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
    return service.provision(new ExternalIdentityProfile(
            URI.create("http://idp.localhost:8080"), sub,
            "same@example.test", "Name", null, null, Set.of("COMPANY_ADMIN")));
}));
awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
```

- [ ] **Step 5: 로컬 변경 경쟁과 외부 트랜잭션 롤백 검증**

트랜잭션 A는 부모 사용자를 FOR UPDATE하고 DISABLED로 변경, version 증가 및 APP_ADMIN 추가 후 커밋을 대기합니다. B는 같은 identity의 service.provision을 시작합니다. DB 잠금 대기를 확인한 뒤 A를 커밋하고, B 결과 및 fresh SQL이 DISABLED+APP_ADMIN과 새 snapshot을 보유하는지 확인합니다. 반대 순서도 실행합니다. B가 provision 후 외부 트랜잭션을 유지하는 동안 A를 대기시켜, B 커밋 뒤 A의 로컬 변경이 남는지 확인합니다.

```java
assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
    service.provision(profile("http://idp.localhost:8080", sub, "new@example.test"));
    throw new IllegalStateException("outer failure");
})).isInstanceOf(IllegalStateException.class);
```

위 assertion을 신규와 기존 사용자에 모두 적용합니다. 신규는 user/role 0건, 기존은 snapshot/기록/version이 실행 전과 같은지 fresh query로 확인합니다. Task 3의 singleton 잠금을 외부 트랜잭션에서 얻고 provision→예외 순서로도 같은 결과를 확인하여 REQUIRES_NEW가 아님을 증명합니다. bootstrap 값은 변경하지 않습니다.

- [ ] **Step 6: 경계 테스트와 전체 회귀 검증**

AppUserBoundaryTest는 ObjectMapper로 AppUserView를 직렬화하여 허용된 사본만 포함되는지 확인합니다. company와 중첩 department의 내부 key/토큰 key는 ExternalUserSnapshot에서 거절되는지 검증합니다. 실패 출력에도 실제 토큰을 넣지 않고 합성 값만 사용합니다.

```java
assertThatThrownBy(() -> new ExternalUserSnapshot(null, null,
        Map.of("code", "DEMO", "name", "Demo", "access_token", "synthetic"), null, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
```

다음 명령을 실행합니다.

```powershell
./gradlew.bat test --tests '*AppUserProvisioningIntegrationTest' --tests '*AppUserProvisioningConcurrencyIntegrationTest' --tests '*AppUserBoundaryTest'
./gradlew.bat clean test
```

Expected: 2A–2C 및 기존 ReferenceApplicationTest 모두 PASS입니다. 테스트 총수·실패 수와 실행 명령을 기록합니다. worktree root에서 의존 경계를 검사합니다.

```powershell
rg -n 'com\.sweet\.authstudy|jdbc:postgresql://localhost:5432' reference-app/backend/src/main
rg -n 'org\.springframework|jakarta\.persistence|user\.application' reference-app/backend/src/main/java/com/sweet/referenceapp/user/domain
git diff --check
git status --short
```

처음 두 검색은 no matches(exit 1), diff check는 exit 0을 기대합니다. JSON 열의 존재만으로 민감정보 차단을 입증할 수 없으므로 경계 테스트와 입력 허용 목록도 필수입니다.

- [ ] **Step 7: 커밋과 완료 기록**

2C의 5개 파일만 stage하고 `git diff --cached --check`, `git commit -m "feat: provision reference users transactionally"`를 실행합니다. 기존 `.superpowers/sdd/2026-08-21-oauth-oidc-reference-app/progress.md`에 상위 Task 2 완료와 본 계획 2A–2C의 commit/test/review 증거를 기록합니다. 상위 Tasks 3–10은 미구현으로 유지합니다. SDD 스킬이 별도 ledger를 요구하면 본 계획의 identity로 만들고 기존 ledger에서 참조합니다.

## 자체 검토와 완료 조건

- identity/email/claim 정책, 입력 불변성, 금지 필드: 2A 및 2C 경계 테스트.
- schema/singleton/JSON/roles/FK/version: 2B。
- JIT 재로그인/누락 제거/로컬 권한 보존: 2C.
- 동시 최초 로그인 유일성, 관리자 변경 보호, 전체 롤백, Task 3 참여: 2B–2C.
- OAuth 검증/세션 거절/마지막 관리자 보호는 Task 2에 포함하지 않습니다.
- 코드 단편은 구현 계약과 테스트의 핵심입니다. Java import, constructor injection, 필드 accessor는 명시된 타입과 이름에 맞추며 정의되지 않은 외부 helper에 의존하지 않습니다.

계획 작성 시점에 구현 코드나 테스트를 작성·실행한 것은 아닙니다. 실행 방식 선택 후 각 RED/GREEN을 기록합니다.
