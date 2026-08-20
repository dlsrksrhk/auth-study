# HR Admin Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Docker PostgreSQL, Spring Boot API, Next.js 관리자 웹으로 회사·직위·부서·사용자와 복수 부서 소속을 관리하고 JWT 인증과 회사 격리를 검증할 수 있는 로컬 HR 시스템을 구축합니다.

**Architecture:** 하나의 Spring Boot 애플리케이션을 `identity`, `authorization`, `hr`, `audit`, `shared` 기능 패키지로 나눈 모듈형 모놀리스로 구현합니다. Next.js 앱은 동일 출처 `/api` 프록시를 통해 백엔드에 접근하고 Access Token은 메모리, Refresh Token은 HttpOnly 쿠키에 보관합니다. PostgreSQL 스키마와 제약은 Flyway가 소유합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Gradle Kotlin DSL, Spring Web/Security/Data JPA/Validation/OAuth2 Resource Server, Flyway, PostgreSQL 17, Testcontainers, Next.js App Router, TypeScript, shadcn/ui, Vitest, Testing Library, Playwright

**Spec:** `docs/superpowers/specs/2026-08-20-hr-admin-foundation-design.md`

## Global Constraints

- 로컬 개발 전용이며 운영 프로필과 환경변수 기반 비밀 관리는 만들지 않습니다.
- PostgreSQL 이미지는 `postgres:17-alpine`, DB/사용자/비밀번호는 모두 `auth_study`입니다.
- 개발 프로필, JWT HS256 키, 쿠키 설정과 고정 관리자 자격 증명은 Git에 커밋되는 YAML로 관리합니다.
- 고정 시스템 관리자는 `admin@auth-study.local` / `AuthStudy1234!`이며 최초 비밀번호 변경 대상이 아닙니다.
- Access Token 수명은 15분, Refresh Token 수명은 7일입니다.
- Refresh 쿠키 이름은 `AUTH_STUDY_REFRESH`, Path는 `/api/v1/auth`, HttpOnly=true, SameSite=Lax, Secure=false입니다.
- 사용자 비밀번호는 12~64자이며 영문 대문자·소문자·숫자·특수문자를 각각 포함하고 BCrypt cost 12로 해시합니다.
- 로그인은 연속 5회 실패하면 15분 동안 잠급니다.
- 주요 업무 code는 trim 후 대문자로, 이메일과 도메인은 trim 후 소문자로 정규화하며 code는 생성 후 변경하지 않습니다.
- 외부 API는 code, 내부 관계는 Long id를 사용합니다.
- Hibernate는 `ddl-auto=validate`만 사용하고 모든 DDL은 Flyway에 둡니다.
- 다른 회사의 데이터는 URL 입력과 무관하게 인증 컨텍스트의 companyId로 차단합니다.
- 사용하지 않는 OAuth/OIDC/SAML 모듈이나 비어 있는 관리 메뉴는 만들지 않습니다.
- 각 작업은 실패 테스트 → 최소 구현 → 통과 테스트 → 명시적 경로만 스테이징한 커밋 순서를 따릅니다.

---

## File Structure

```text
backend/
├─ build.gradle.kts
├─ src/main/java/com/sweet/authstudy/
│  ├─ shared/
│  │  ├─ config/{ClockConfig,AppSecurityProperties}.java
│  │  ├─ error/{ApiException,ErrorCode,FieldViolation,GlobalExceptionHandler}.java
│  │  └─ security/{ActorContext,TenantGuard}.java
│  ├─ hr/company/
│  │  ├─ domain/{Company,CompanyStatus,CompanyRepository}.java
│  │  ├─ application/{CompanyService,CompanyCommands,CompanyView}.java
│  │  ├─ infrastructure/{CompanyJpaEntity,CompanyJpaRepository,CompanyRepositoryAdapter}.java
│  │  └─ presentation/{CompanyAdminController,CompanyRequests}.java
│  ├─ hr/position/
│  │  ├─ domain/{Position,PositionRepository}.java
│  │  ├─ application/{PositionService,PositionCommands,PositionView}.java
│  │  ├─ infrastructure/{PositionJpaEntity,PositionJpaRepository,PositionRepositoryAdapter}.java
│  │  └─ presentation/{PositionAdminController,PositionRequests}.java
│  ├─ hr/user/
│  │  ├─ domain/{HrUser,UserStatus,UserRepository}.java
│  │  ├─ application/{UserService,UserCommands,UserViews}.java
│  │  ├─ infrastructure/{UserJpaEntity,UserJpaRepository,UserRepositoryAdapter}.java
│  │  └─ presentation/{UserAdminController,UserRequests}.java
│  ├─ hr/department/
│  │  ├─ domain/{Department,DepartmentStatus,DepartmentRepository}.java
│  │  ├─ application/{DepartmentService,DepartmentCommands,DepartmentView}.java
│  │  ├─ infrastructure/{DepartmentJpaEntity,DepartmentJpaRepository,DepartmentRepositoryAdapter}.java
│  │  └─ presentation/{DepartmentAdminController,DepartmentRequests}.java
│  ├─ hr/membership/
│  │  ├─ domain/{DepartmentMembership,DepartmentRole,MembershipRepository}.java
│  │  ├─ application/{MembershipService,MembershipCommands,MembershipView}.java
│  │  ├─ infrastructure/{MembershipJpaEntity,MembershipJpaRepository,MembershipRepositoryAdapter}.java
│  │  └─ presentation/{MembershipAdminController,MembershipRequests}.java
│  ├─ identity/
│  │  ├─ domain/{Account,AccountRole,AccountStatus,AccountRepository,RefreshToken,RefreshTokenRepository}.java
│  │  ├─ application/{AuthenticationService,AccountService,AuthCommands,AuthTokens,PasswordGenerator}.java
│  │  ├─ infrastructure/{AccountJpaEntity,AccountJpaRepository,AccountRepositoryAdapter,RefreshTokenJpaEntity,RefreshTokenJpaRepository,RefreshTokenRepositoryAdapter,NimbusJwtTokenService,SecureRandomPasswordGenerator,PasswordConfiguration}.java
│  │  └─ presentation/{AuthController,AuthRequests,AuthResponses}.java
│  ├─ authorization/{AuthenticatedAccount,SecurityConfig,JwtAuthenticationConverter,SameOriginRequestGuard}.java
│  ├─ shared/security/{ActorContext,SpringSecurityActorContext,TenantGuard}.java
│  └─ audit/
│     ├─ domain/{AuditLog,AuditLogRepository}.java
│     ├─ application/{AuditService,AuditCommand,AuditView}.java
│     ├─ infrastructure/{AuditLogJpaEntity,AuditLogJpaRepository,AuditLogRepositoryAdapter}.java
│     └─ presentation/AuditAdminController.java
├─ src/main/resources/{application.yaml,application-dev.yaml}
├─ src/main/resources/db/migration/{V1__company_position.sql,V2__user_account.sql,V3__refresh_token.sql,V4__department_membership.sql,V5__audit_log.sql}
└─ src/test/java/com/sweet/authstudy/{support,shared,hr,identity,authorization,audit,acceptance,architecture}/

frontend/
├─ src/app/login/page.tsx
├─ src/app/change-password/page.tsx
├─ src/app/(admin)/{layout,page}.tsx
├─ src/app/(admin)/companies/[companyCode]/{positions,departments,users,audit-logs}/
├─ src/components/{layout,companies,positions,departments,users,audit}/
├─ src/features/auth/{auth-provider.tsx,auth-api.ts,single-flight-refresh.ts}
├─ src/lib/api/{client,problem}.ts
└─ src/test/setup.ts

infrastructure/docker-compose.yml
README.md
```

JPA Entity와 Spring Data Repository는 각 기능의 infrastructure 패키지에만 둡니다. domain 객체는 JPA annotation을 갖지 않으며 Repository port는 domain 객체를 주고받습니다. 각 Adapter가 domain과 JPA Entity 변환을 담당합니다.

---

### Task 1: 개발 DB, 백엔드 의존성과 테스트 기반

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/resources/application.yaml`
- Create: `backend/src/main/resources/application-dev.yaml`
- Create: `backend/src/test/resources/application-test.yaml`
- Modify: `backend/src/main/java/com/sweet/authstudy/AuthStudyApplication.java`
- Create: `backend/src/main/java/com/sweet/authstudy/shared/config/AppSecurityProperties.java`
- Create: `backend/src/main/java/com/sweet/authstudy/shared/config/ClockConfig.java`
- Create: `infrastructure/docker-compose.yml`
- Modify: `backend/src/test/java/com/sweet/authstudy/AuthStudyApplicationTests.java`
- Create: `backend/src/test/java/com/sweet/authstudy/support/PostgresContainerConfiguration.java`

**Interfaces:**
- Consumes: 기존 `AuthStudyApplication` 진입점
- Produces: `AppSecurityProperties`, 주입 가능한 `Clock`, PostgreSQL Testcontainers 기반 Spring 통합 테스트 환경

- [ ] **Step 1: 컨텍스트가 실제 PostgreSQL에서 실행돼야 한다는 실패 테스트 작성**

```java
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthStudyApplicationTests {
    @Test void contextLoads() {}
}

@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }
}
```

- [ ] **Step 2: 테스트 의존성이 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*AuthStudyApplicationTests"`

Expected: FAIL because `spring-boot-starter-test`, `spring-boot-testcontainers`, and Testcontainers classes are unavailable.

- [ ] **Step 3: 필요한 의존성과 개발 설정 추가**

`build.gradle.kts`에 다음 의존성을 정확히 추가합니다.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("org.springframework.security:spring-security-test")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:postgresql")
```

`application.yaml`은 `dev`를 기본 프로필로 활성화하고 `ddl-auto: validate`를 설정합니다. `application-dev.yaml`은 DB URL `jdbc:postgresql://localhost:5432/auth_study`, YAML에 커밋되는 최소 32바이트 Base64 JWT 키, 토큰 수명, 쿠키, 브라우저 origin `http://localhost:3000`, 잠금과 부트스트랩 관리자 설정을 포함합니다. `application-test.yaml`은 같은 토큰·쿠키·origin·잠금 값을 사용하되 datasource는 `@ServiceConnection`에 맡기고 dev seeder 설정은 활성화하지 않습니다. `AppSecurityProperties`는 prefix `app.security`의 `Jwt`, `RefreshCookie`, `LoginLock`, `BootstrapAdmin` record와 `browserOrigin`을 노출합니다. `AuthStudyApplication`에는 `@ConfigurationPropertiesScan`을 추가합니다.

- [ ] **Step 4: Docker Compose 작성 및 구성 검증**

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: auth_study
      POSTGRES_USER: auth_study
      POSTGRES_PASSWORD: auth_study
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U auth_study -d auth_study"]
      interval: 5s
      timeout: 3s
      retries: 10
    volumes:
      - auth-study-postgres:/var/lib/postgresql/data
volumes:
  auth-study-postgres:
```

Run: `docker compose -f infrastructure/docker-compose.yml config`

Expected: exit 0 and one `postgres` service with one named volume.

- [ ] **Step 5: 백엔드 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*AuthStudyApplicationTests"`

Expected: PASS with Spring context using PostgreSQL Testcontainers.

- [ ] **Step 6: 커밋**

```powershell
git add -- backend/build.gradle.kts backend/src/main/resources/application.yaml backend/src/main/resources/application-dev.yaml backend/src/test/resources/application-test.yaml backend/src/main/java/com/sweet/authstudy/AuthStudyApplication.java backend/src/main/java/com/sweet/authstudy/shared/config backend/src/test/java/com/sweet/authstudy/AuthStudyApplicationTests.java backend/src/test/java/com/sweet/authstudy/support infrastructure/docker-compose.yml
git commit -m "build: configure local PostgreSQL development"
```

---

### Task 2: 공통 오류 계약과 입력 검증

**Files:**
- Create: `backend/src/main/java/com/sweet/authstudy/shared/error/ErrorCode.java`
- Create: `backend/src/main/java/com/sweet/authstudy/shared/error/ApiException.java`
- Create: `backend/src/main/java/com/sweet/authstudy/shared/error/FieldViolation.java`
- Create: `backend/src/main/java/com/sweet/authstudy/shared/error/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/sweet/authstudy/shared/error/ErrorContractIntegrationTest.java`

**Interfaces:**
- Consumes: Spring MVC `ProblemDetail`, Jakarta Validation
- Produces: `ApiException(ErrorCode, String)`, `ErrorCode` HTTP 매핑, `ProblemDetail` properties `code`, `traceId`, `fieldErrors`

- [ ] **Step 1: Problem Details 실패 테스트 작성**

```java
@WebMvcTest(controllers = ErrorContractIntegrationTest.ProbeController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ErrorContractIntegrationTest {
    @Test void validation_error_contains_machine_code_and_field_errors() throws Exception {
        mvc.perform(post("/probe").contentType(APPLICATION_JSON).content("{\"code\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
    }
}
```

- [ ] **Step 2: 처리기가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*ErrorContractIntegrationTest"`

Expected: FAIL because the error classes and response properties do not exist.

- [ ] **Step 3: 오류 타입과 전역 처리기 구현**

```java
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_CODE(HttpStatus.CONFLICT),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    DUPLICATE_EMPLOYEE_NUMBER(HttpStatus.CONFLICT),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT);

    private final HttpStatus status;
    ErrorCode(HttpStatus status) { this.status = status; }
    public HttpStatus status() { return status; }
}

public final class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    public ErrorCode errorCode() { return errorCode; }
}
```

`GlobalExceptionHandler`는 `MethodArgumentNotValidException`, `ApiException`, `ObjectOptimisticLockingFailureException`, DB 유일 제약 예외를 처리합니다. 요청의 `X-Trace-Id`가 없으면 UUID를 생성하고 응답과 로그 MDC에 같은 값을 사용합니다. 비밀번호와 토큰 값은 예외 메시지에 포함하지 않습니다.

- [ ] **Step 4: 오류 계약 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*ErrorContractIntegrationTest"`

Expected: PASS with status, code, traceId, and fieldErrors assertions.

- [ ] **Step 5: 전체 테스트로 회귀 확인**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 6: 커밋**

```powershell
git add -- backend/src/main/java/com/sweet/authstudy/shared/error backend/src/test/java/com/sweet/authstudy/shared/error
git commit -m "feat: add API problem details contract"
```

---

### Task 3: 회사와 기본 직위

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__company_position.sql`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/domain/Company.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/domain/CompanyStatus.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/domain/CompanyRepository.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyCommands.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyService.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyView.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/infrastructure/CompanyJpaEntity.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/infrastructure/CompanyJpaRepository.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/infrastructure/CompanyRepositoryAdapter.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/domain/Position.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/domain/PositionRepository.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/application/PositionCommands.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/application/PositionService.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/application/PositionView.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/infrastructure/PositionJpaEntity.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/infrastructure/PositionJpaRepository.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/infrastructure/PositionRepositoryAdapter.java`
- Create: `backend/src/test/java/com/sweet/authstudy/hr/company/CompanyServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `ApiException`, `Clock`
- Produces: `CompanyService.create(CreateCompanyCommand)`, `CompanyService.update(String, UpdateCompanyCommand)`, `PositionService` CRUD, 기본 직위 5개

- [ ] **Step 1: 회사 생성과 기본 직위 실패 테스트 작성**

```java
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
class CompanyServiceIntegrationTest {
    @Test void creates_company_with_normalized_identity_and_five_positions() {
        CompanyView company = companyService.create(
            new CreateCompanyCommand(" acme ", "Acme", " ACME.EXAMPLE "));
        assertThat(company.code()).isEqualTo("ACME");
        assertThat(company.emailDomain()).isEqualTo("acme.example");
        assertThat(positionService.list("ACME")).extracting(PositionView::code)
            .containsExactly("EMPLOYEE", "ASSISTANT_MANAGER", "MANAGER",
                             "DEPUTY_GENERAL_MANAGER", "GENERAL_MANAGER");
    }

    @Test void rejects_duplicate_domain_case_insensitively() {
        companyService.create(new CreateCompanyCommand("ACME", "Acme", "acme.example"));
        assertThatThrownBy(() -> companyService.create(
            new CreateCompanyCommand("BETA", "Beta", "ACME.EXAMPLE")))
            .isInstanceOf(ApiException.class);
    }
    @Test void rejects_reserved_auth_study_local_domain() {
        assertThatThrownBy(() -> companyService.create(
            new CreateCompanyCommand("LOCAL", "Local", "auth-study.local")))
            .isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }
}
```

- [ ] **Step 2: 스키마와 서비스가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*CompanyServiceIntegrationTest"`

Expected: FAIL because Company and Position types do not exist.

- [ ] **Step 3: V1 마이그레이션 작성**

`companies`와 `positions`에 BIGSERIAL PK, version, timestamptz 감사 시각을 추가합니다. `upper(code)` 및 `lower(email_domain)` 전역 유일 인덱스, `positions(company_id, upper(code))` 유일 인덱스와 FK를 생성합니다. `auth-study.local` 예약 도메인은 CompanyService에서 거부합니다.

- [ ] **Step 4: domain, repository adapter와 서비스 구현**

```java
public record CreateCompanyCommand(String code, String name, String emailDomain) {}
public record UpdateCompanyCommand(String name, CompanyStatus status, long version) {}

@Transactional
public CompanyView create(CreateCompanyCommand command) {
    String code = normalizeCode(command.code());
    String domain = normalizeDomain(command.emailDomain());
    rejectReservedDomain(domain);
    Company saved = companyRepository.save(Company.create(code, command.name(), domain, clock.instant()));
    positionService.createDefaults(saved.id());
    return CompanyView.from(saved);
}
```

기본 직위 level/displayOrder는 사원 10, 대리 20, 과장 30, 차장 40, 부장 50으로 고정합니다. Position 수정은 name, level, displayOrder, active만 허용합니다.

- [ ] **Step 5: 회사/직위 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*CompanyServiceIntegrationTest"`

Expected: PASS including normalization, uniqueness, reserved domain, five defaults.

- [ ] **Step 6: 전체 테스트와 Flyway 검증**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS and Hibernate schema validation succeeds after Flyway V1.

- [ ] **Step 7: 커밋**

```powershell
git add -- backend/src/main/resources/db/migration/V1__company_position.sql backend/src/main/java/com/sweet/authstudy/hr/company backend/src/main/java/com/sweet/authstudy/hr/position backend/src/test/java/com/sweet/authstudy/hr/company
git commit -m "feat: add company and position domains"
```

---

### Task 4: HR 사용자, 인증 계정과 개발 관리자

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__user_account.sql`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/user/domain/{HrUser,UserStatus,UserRepository}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/user/application/{UserService,UserCommands,UserViews}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/user/infrastructure/{UserJpaEntity,UserJpaRepository,UserRepositoryAdapter}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/domain/{Account,AccountRole,AccountStatus,AccountRepository}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/application/{AccountService,PasswordGenerator}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/infrastructure/{AccountJpaEntity,AccountJpaRepository,AccountRepositoryAdapter,SecureRandomPasswordGenerator,PasswordConfiguration,DevAdminSeeder}.java`
- Create: `backend/src/test/java/com/sweet/authstudy/hr/user/UserCreationIntegrationTest.java`
- Create: `backend/src/test/java/com/sweet/authstudy/identity/DevAdminSeederIntegrationTest.java`

**Interfaces:**
- Consumes: `CompanyRepository`, `PositionRepository`, `AppSecurityProperties`, `PasswordEncoder`
- Produces: `UserService.create(CreateUserCommand) -> CreatedUserView`, `AccountService.resetTemporaryPassword(long)`, dev system administrator

- [ ] **Step 1: 사용자와 계정 원자적 생성 실패 테스트 작성**

```java
@Test void creates_pending_user_account_and_one_time_temporary_password() {
    CreatedUserView result = userService.create(new CreateUserCommand(
        "ACME", "U001", "E-1001", "Kim", "kim@acme.example",
        "010-0000-0000", LocalDate.parse("2026-08-20"), "Seoul", null, "EMPLOYEE"));
    assertThat(result.user().status()).isEqualTo(UserStatus.PENDING);
    assertThat(result.temporaryPassword()).hasSize(16);
    assertThat(accountRepository.findByUserId(result.user().id()).orElseThrow().mustChangePassword()).isTrue();
    assertThat(accountRepository.findByUserId(result.user().id()).orElseThrow().passwordHash())
        .doesNotContain(result.temporaryPassword());
}
```

별도 테스트로 회사 도메인 불일치 이메일, 회사 내 code/email/employeeNumber 중복, 다른 회사 Position 사용을 각각 거부하는지 검증합니다.

- [ ] **Step 2: 타입과 V2 스키마가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*UserCreationIntegrationTest"`

Expected: FAIL because User and Account types do not exist.

- [ ] **Step 3: V2 마이그레이션 작성**

`users`, `accounts`, `account_roles`를 생성합니다. `users(company_id, upper(code))`, `users(company_id, employee_number)`, `accounts(company_id, lower(login_email))` 유일 인덱스와 `accounts(user_id)` nullable unique 인덱스를 추가합니다. system Account는 company_id/user_id null을 허용하고 SYSTEM_ADMIN role은 서비스에서만 부여합니다.

- [ ] **Step 4: 비밀번호 생성기와 사용자/계정 서비스 구현**

```java
public record CreateUserCommand(
    String companyCode, String code, String employeeNumber, String name,
    String loginEmail, String phone, LocalDate hiredAt, String workplace,
    String profileImageUrl, String positionCode) {}

public record CreatedUserView(UserView user, String temporaryPassword) {}

public interface PasswordGenerator {
    String generateTemporaryPassword(); // 16 chars, all four character groups
}
```

UserService의 하나의 `@Transactional` 메서드에서 회사/직위/도메인을 검증하고 PENDING User와 USER role Account를 생성합니다. PasswordGenerator에는 `SecureRandom`을 사용하고 Account에는 BCrypt cost 12 해시만 저장합니다. 재설정은 새 해시와 mustChangePassword=true를 저장하고 임시 비밀번호를 한 번 반환합니다.

`SecureRandomPasswordGenerator`가 PasswordGenerator를 구현합니다. `PasswordConfiguration`은 `new BCryptPasswordEncoder(12)`를 PasswordEncoder bean으로 제공합니다.

- [ ] **Step 5: dev 관리자 시드 실패 테스트와 구현**

```java
@Test void creates_idempotent_system_admin_without_company_or_user() {
    seeder.run();
    seeder.run();
    Account admin = accountRepository.findSystemByEmail("admin@auth-study.local").orElseThrow();
    assertThat(admin.companyId()).isNull();
    assertThat(admin.userId()).isNull();
    assertThat(admin.roles()).containsExactly(AccountRole.SYSTEM_ADMIN);
    assertThat(admin.mustChangePassword()).isFalse();
    assertThat(passwordEncoder.matches("AuthStudy1234!", admin.passwordHash())).isTrue();
}
```

`DevAdminSeeder`는 `@Profile("dev")`에서만 실행하고 동일 이메일 계정이 있으면 아무 작업도 하지 않습니다.

- [ ] **Step 6: 사용자와 시드 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*UserCreationIntegrationTest" --tests "*DevAdminSeederIntegrationTest"`

Expected: PASS for one-time password, constraints, BCrypt, and idempotent seed.

- [ ] **Step 7: 전체 테스트 후 커밋**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

```powershell
git add -- backend/src/main/resources/db/migration/V2__user_account.sql backend/src/main/java/com/sweet/authstudy/hr/user backend/src/main/java/com/sweet/authstudy/identity backend/src/test/java/com/sweet/authstudy/hr/user backend/src/test/java/com/sweet/authstudy/identity
git commit -m "feat: add HR users and accounts"
```

---

### Task 5: JWT 로그인, Refresh 회전과 비밀번호 변경

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__refresh_token.sql`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/domain/{RefreshToken,RefreshTokenRepository}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/application/{AuthenticationService,AuthCommands,AuthTokens,JwtTokenService}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/infrastructure/{RefreshTokenJpaEntity,RefreshTokenJpaRepository,RefreshTokenRepositoryAdapter,NimbusJwtTokenService}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/identity/presentation/{AuthController,AuthRequests,AuthResponses}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/authorization/{AuthenticatedAccount,SecurityConfig,JwtAuthenticationConverter,SameOriginRequestGuard}.java`
- Create: `backend/src/test/java/com/sweet/authstudy/identity/AuthenticationIntegrationTest.java`
- Create: `backend/src/test/java/com/sweet/authstudy/identity/RefreshTokenRotationIntegrationTest.java`

**Interfaces:**
- Consumes: `AccountRepository`, `CompanyRepository`, `UserRepository`, `AppSecurityProperties`, `Clock`
- Produces: `AuthenticationService.login`, `refresh`, `logout`, `changePassword`; `/api/v1/auth/*`; Resource Server principal `AuthenticatedAccount`

- [ ] **Step 1: 로그인과 최초 비밀번호 제한 실패 테스트 작성**

```java
@Test void temporary_password_issues_password_change_only_access_token() {
    LoginResult result = authenticationService.login(
        new LoginCommand("kim@acme.example", temporaryPassword, "127.0.0.1"));
    assertThat(result.mustChangePassword()).isTrue();
    assertThat(jwtDecoder.decode(result.accessToken()).getClaimAsString("purpose"))
        .isEqualTo("PASSWORD_CHANGE");
}

@Test void fifth_bad_password_locks_login_for_fifteen_minutes() {
    IntStream.range(0, 5).forEach(i -> assertThatThrownBy(() ->
        authenticationService.login(new LoginCommand("kim@acme.example", "wrong", "127.0.0.1"))));
    assertThat(accountRepository.findCompanyAccount(companyId, "kim@acme.example").orElseThrow().lockedUntil())
        .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
}
```

- [ ] **Step 2: 인증 서비스가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*AuthenticationIntegrationTest"`

Expected: FAIL because authentication and JWT types do not exist.

- [ ] **Step 3: Refresh Token 스키마와 repository 구현**

V3는 `refresh_tokens`에 token_hash unique, family_id, account_id, issued_at, expires_at, used_at, revoked_at을 생성합니다. 원문은 저장하지 않습니다. `RefreshTokenRepository`는 다음 메서드를 제공합니다.

```java
Optional<RefreshToken> findByHash(String sha256Hex);
RefreshToken save(RefreshToken token);
void revokeFamily(UUID familyId, Instant revokedAt);
void revokeAllByAccountId(long accountId, Instant revokedAt);
```

- [ ] **Step 4: HS256 토큰과 Spring Security 구현**

```java
public record AuthTokens(String accessToken, Instant accessTokenExpiresAt) {}
public record LoginResult(AuthTokens tokens, boolean mustChangePassword, String refreshToken) {}
public record RefreshResult(AuthTokens tokens, String refreshToken) {}
public record ChangePasswordCommand(String currentPassword, String newPassword) {}
public interface JwtTokenService {
    AuthTokens issue(AuthenticatedAccount account, boolean passwordChangeOnly);
}
public record AuthenticatedAccount(
    long accountId, Long companyId, Long userId, Set<AccountRole> roles,
    boolean passwordChangeOnly) {}
```

`NimbusJwtTokenService`는 `sub`, `company_id`, `user_id`, `roles`, `jti`, 선택적 `purpose=PASSWORD_CHANGE`를 넣고 15분 만료 HS256 JWT를 만듭니다. `JwtAuthenticationConverter`는 이 claim들을 `AuthenticatedAccount`와 `ROLE_*` authority로 변환합니다.

`SecurityConfig`는 `/api/v1/auth/login`, `/api/v1/auth/refresh`만 anonymous 허용하고, password-change-only principal은 `/api/v1/auth/password` 외 요청을 403으로 거부합니다. 세션은 STATELESS이고 일반 API는 Bearer Token을 요구합니다. `SameOriginRequestGuard`는 Refresh와 logout POST의 Origin이 설정된 `http://localhost:3000`과 정확히 일치하지 않으면 403을 반환합니다.

- [ ] **Step 5: Refresh 회전과 재사용 탐지 실패 테스트 작성**

```java
@Test void refresh_rotates_token_and_reuse_revokes_family() {
    LoginResult login = loginActiveUser();
    RefreshResult rotated = authenticationService.refresh(login.refreshToken());
    assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
    assertThatThrownBy(() -> authenticationService.refresh(login.refreshToken()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> authenticationService.refresh(rotated.refreshToken()))
        .isInstanceOf(ApiException.class);
}
```

- [ ] **Step 6: 로그인, 갱신, 로그아웃과 비밀번호 변경 구현**

로그인은 시스템 관리자 exact email 조회 후 회사 도메인 조회 순서로 처리합니다. Refresh 원문은 32바이트 SecureRandom 값을 Base64URL로 인코딩하고 SHA-256 hex만 DB에 저장합니다. 갱신은 기존 행 usedAt 기록과 새 행 생성을 한 트랜잭션에서 수행합니다. 사용된 토큰 재제출 시 family 전체를 폐기합니다.

PENDING User는 mustChangePassword=true인 임시 비밀번호 로그인만 허용합니다. 비밀번호 변경 뒤에도 PENDING이면 다음 로그인과 Refresh를 거부하며, 관리자가 활성 Position과 주 소속을 지정해 ACTIVE로 전환한 뒤 정상 로그인을 허용합니다.

AuthController는 Refresh 쿠키를 성공 응답에 설정하고 logout에서 Max-Age=0으로 삭제합니다. 비밀번호 변경은 currentPassword 재검증, 새 비밀번호 정책 검증, BCrypt 저장, mustChangePassword=false, 모든 Refresh 폐기를 하나의 트랜잭션에서 수행합니다. `/api/v1/auth/me`는 Account 식별자·이메일·역할과 연결된 User의 code/name/companyCode를 반환하되 passwordHash나 토큰 정보는 반환하지 않습니다.

- [ ] **Step 7: 인증 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*AuthenticationIntegrationTest" --tests "*RefreshTokenRotationIntegrationTest"`

Expected: PASS for system/company login, domain resolution, lockout, password-change token, rotation, reuse, logout, and password change.

- [ ] **Step 8: MockMvc 쿠키 속성 검증 추가 및 실행**

```java
mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
    .header("Origin", "http://localhost:3000")
    .andExpect(status().isOk())
    .andExpect(header().string("Set-Cookie", allOf(
        containsString("AUTH_STUDY_REFRESH="),
        containsString("HttpOnly"), containsString("SameSite=Lax"),
        containsString("Path=/api/v1/auth"))));
```

Run: `cd backend; .\gradlew.bat test --tests "*AuthenticationIntegrationTest"`

Expected: PASS.

같은 테스트에서 Origin을 `http://evil.example`로 보낸 Refresh 요청이 403이고 Set-Cookie를 반환하지 않는지도 검증합니다.

- [ ] **Step 9: 전체 테스트 후 커밋**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

```powershell
git add -- backend/src/main/resources/db/migration/V3__refresh_token.sql backend/src/main/java/com/sweet/authstudy/identity backend/src/main/java/com/sweet/authstudy/authorization backend/src/test/java/com/sweet/authstudy/identity
git commit -m "feat: add JWT authentication and token rotation"
```

---

### Task 6: 부서 트리와 복수 부서 소속

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__department_membership.sql`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/department/domain/{Department,DepartmentStatus,DepartmentRepository}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/department/application/{DepartmentService,DepartmentCommands,DepartmentView}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/department/infrastructure/{DepartmentJpaEntity,DepartmentJpaRepository,DepartmentRepositoryAdapter}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/membership/domain/{DepartmentMembership,DepartmentRole,MembershipRepository}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/membership/application/{MembershipService,MembershipCommands,MembershipView}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/membership/infrastructure/{MembershipJpaEntity,MembershipJpaRepository,MembershipRepositoryAdapter}.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/user/application/UserService.java`
- Create: `backend/src/test/java/com/sweet/authstudy/hr/department/DepartmentTreeIntegrationTest.java`
- Create: `backend/src/test/java/com/sweet/authstudy/hr/membership/MembershipIntegrationTest.java`

**Interfaces:**
- Consumes: `CompanyRepository`, `UserRepository`, `Clock`, `ApiException`
- Produces: `DepartmentService.create/move/changeStatus/tree`, `MembershipService.assign/update/end/listByUser`

- [ ] **Step 1: 부서 트리 순환 실패 테스트 작성**

```java
@Test void rejects_moving_department_below_its_descendant() {
    createDepartment("HQ", null);
    createDepartment("DEV", "HQ");
    createDepartment("API", "DEV");
    assertThatThrownBy(() -> departmentService.move(
        new MoveDepartmentCommand("ACME", "HQ", "API", 0)))
        .isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
}
```

- [ ] **Step 2: 소속 제약 실패 테스트 작성**

```java
@Test void allows_multiple_memberships_but_only_one_active_primary_and_head() {
    membershipService.assign(command("U001", "DEV", MEMBER, true));
    membershipService.assign(command("U001", "TF", MEMBER, false));
    assertThatThrownBy(() -> membershipService.assign(command("U001", "SALES", MEMBER, true)))
        .isInstanceOf(ApiException.class);
    membershipService.assign(command("U002", "DEV", HEAD, false));
    assertThatThrownBy(() -> membershipService.assign(command("U003", "DEV", HEAD, false)))
        .isInstanceOf(ApiException.class);
}
```

- [ ] **Step 3: 스키마와 서비스가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*DepartmentTreeIntegrationTest" --tests "*MembershipIntegrationTest"`

Expected: FAIL because department and membership types do not exist.

- [ ] **Step 4: V4 마이그레이션 작성**

`departments`와 `department_memberships`를 생성합니다. 다음 인덱스를 정확히 포함합니다.

```sql
CREATE UNIQUE INDEX uq_department_company_code
  ON departments(company_id, upper(code));
CREATE UNIQUE INDEX uq_active_user_department
  ON department_memberships(user_id, department_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX uq_active_primary_membership
  ON department_memberships(user_id) WHERE ended_at IS NULL AND is_primary;
CREATE UNIQUE INDEX uq_active_department_head
  ON department_memberships(department_id) WHERE ended_at IS NULL AND role = 'HEAD';
```

- [ ] **Step 5: 트리와 소속 서비스 구현**

```java
public record CreateDepartmentCommand(
    String companyCode, String code, String name, String parentCode) {}
public record MoveDepartmentCommand(
    String companyCode, String code, String newParentCode, long version) {}
public record AssignMembershipCommand(
    String companyCode, String userCode, String departmentCode,
    DepartmentRole role, boolean primary, Instant startedAt) {}
```

DepartmentService는 이동 전 새 부모에서 시작해 parent chain을 위로 탐색하고 대상 department id가 나타나면 거부합니다. MembershipService는 user/department 회사 일치, 활성 상태, 주 소속과 HEAD 제약을 사전 검증하고 DB 충돌도 409로 변환합니다. 활성 User 전환은 활성 Position과 활성 primary membership이 있을 때만 허용합니다.

- [ ] **Step 6: 비활성화와 소속 종료 테스트 추가**

활성 하위 부서 또는 활성 소속이 있는 Department 비활성화 거부, DELETE 의미의 `end`가 endedAt만 기록하고 이력을 유지하는 테스트를 추가합니다.

- [ ] **Step 7: 조직 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*DepartmentTreeIntegrationTest" --tests "*MembershipIntegrationTest"`

Expected: PASS for hierarchy, cycle, tenant match, multiple memberships, primary/head uniqueness, status rules, and history.

- [ ] **Step 8: 전체 테스트 후 커밋**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

```powershell
git add -- backend/src/main/resources/db/migration/V4__department_membership.sql backend/src/main/java/com/sweet/authstudy/hr/department backend/src/main/java/com/sweet/authstudy/hr/membership backend/src/main/java/com/sweet/authstudy/hr/user/application/UserService.java backend/src/test/java/com/sweet/authstudy/hr/department backend/src/test/java/com/sweet/authstudy/hr/membership
git commit -m "feat: add department hierarchy and memberships"
```

---

### Task 7: ActorContext, 회사 격리와 관리자 API

**Files:**
- Create: `backend/src/main/java/com/sweet/authstudy/shared/security/{ActorContext,SpringSecurityActorContext,TenantGuard}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/company/presentation/{CompanyAdminController,CompanyRequests}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/position/presentation/{PositionAdminController,PositionRequests}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/user/presentation/{UserAdminController,UserRequests}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/department/presentation/{DepartmentAdminController,DepartmentRequests}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/hr/membership/presentation/{MembershipAdminController,MembershipRequests}.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/position/application/PositionService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/user/application/UserService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/department/application/DepartmentService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/membership/application/MembershipService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/identity/application/AccountService.java`
- Create: `backend/src/test/java/com/sweet/authstudy/authorization/TenantAuthorizationIntegrationTest.java`
- Create: `backend/src/test/java/com/sweet/authstudy/presentation/AdminApiContractIntegrationTest.java`

**Interfaces:**
- Consumes: 모든 HR application service, `AuthenticatedAccount`
- Produces: 명세 8장의 `/api/v1/admin/**` 엔드포인트, `ActorContext.current()`, `TenantGuard.requireCompanyAccess`

- [ ] **Step 1: 회사 격리 실패 테스트 작성**

```java
@Test void company_admin_cannot_read_or_mutate_another_company() throws Exception {
    String token = accessToken(companyAdminOf(acmeId));
    mvc.perform(get("/api/v1/admin/companies/BETA/users")
            .header(AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
}

@Test void only_system_admin_can_assign_company_admin_role() throws Exception {
    mvc.perform(put("/api/v1/admin/companies/ACME/users/U001/admin-role")
            .header(AUTHORIZATION, "Bearer " + companyAdminToken))
        .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: 관리자 Controller가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*TenantAuthorizationIntegrationTest"`

Expected: FAIL with 404 for missing endpoints.

- [ ] **Step 3: ActorContext와 TenantGuard 구현**

```java
public interface ActorContext {
    AuthenticatedAccount current();
}

public final class TenantGuard {
    public void requireCompanyAccess(AuthenticatedAccount actor, long targetCompanyId) {
        if (!actor.roles().contains(SYSTEM_ADMIN)
            && !Objects.equals(actor.companyId(), targetCompanyId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "다른 회사에 접근할 수 없습니다.");
        }
    }
}
```

`SpringSecurityActorContext`는 SecurityContextHolder의 Authentication principal이 AuthenticatedAccount인지 확인해 반환하고, 없거나 다른 타입이면 UNAUTHENTICATED ApiException을 던집니다.

System admin 전용 회사 생성/상태 변경/관리자 역할 API에는 method security를 적용합니다. 회사 관리자 API는 companyCode를 Company id로 해석한 직후 TenantGuard를 실행하고 service에도 ActorContext를 전달해 같은 검사를 반복합니다. TenantGuard는 회사 관리자의 Company가 ACTIVE인지도 확인해 비활성 회사의 기존 Access Token으로 관리자 API를 호출하지 못하게 합니다.

Task 3~6에서 만든 mutation service signature에는 첫 인자로 `AuthenticatedAccount actor`를 추가합니다. `UserService.resetTemporaryPassword(actor, companyCode, userCode)`는 대상 Account를 찾은 뒤 `AccountService.resetTemporaryPassword(accountId)`를 호출합니다. `AccountService.assignCompanyAdmin(actor, accountId)`와 `revokeCompanyAdmin(actor, accountId)`는 SYSTEM_ADMIN을 검사하고 자기 자신의 SYSTEM_ADMIN role을 변경하지 않습니다.

- [ ] **Step 4: 요청 DTO와 Controller 구현**

모든 String에는 `@NotBlank`, `@Size`를, 이메일에는 `@Email`, 날짜에는 명시적인 ISO 형식을 사용합니다. 수정 요청은 `version`을 필수로 받습니다. 사용자 생성 응답만 `temporaryPassword`를 포함하고 목록/상세 응답에서는 절대 반환하지 않습니다.

목록 API 공통 규칙은 0-based page, 기본 size 20, 최대 size 100, 허용된 sort key whitelist입니다. 검색은 이름/code/사번의 부분 검색이고 status filter를 지원합니다.

- [ ] **Step 5: API 계약 테스트 작성**

`AdminApiContractIntegrationTest`에 다음 상태를 각각 고정합니다: create 201 + Location, list 200 + page metadata, update 200, validation 400, absent 404, duplicate 409, stale version 409, cross-tenant 403.

검색어 `'%27 OR 1=1 --`를 사용자 목록에 전달했을 때 200과 빈 결과를 반환하고 다른 회사 데이터나 SQL 오류를 노출하지 않는 테스트도 추가해 JPA parameter binding 경계를 고정합니다.

- [ ] **Step 6: 관리자 API와 격리 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*TenantAuthorizationIntegrationTest" --tests "*AdminApiContractIntegrationTest"`

Expected: PASS for all roles, tenant isolation, validation, pagination, and optimistic lock mapping.

- [ ] **Step 7: 전체 테스트 후 커밋**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

```powershell
git add -- backend/src/main/java/com/sweet/authstudy/shared/security backend/src/main/java/com/sweet/authstudy/hr/company backend/src/main/java/com/sweet/authstudy/hr/position backend/src/main/java/com/sweet/authstudy/hr/user backend/src/main/java/com/sweet/authstudy/hr/department backend/src/main/java/com/sweet/authstudy/hr/membership backend/src/main/java/com/sweet/authstudy/identity/application/AccountService.java backend/src/test/java/com/sweet/authstudy/authorization backend/src/test/java/com/sweet/authstudy/presentation
git commit -m "feat: expose tenant-safe admin APIs"
```

---

### Task 8: 감사 로그와 토큰 폐기 연계

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__audit_log.sql`
- Create: `backend/src/main/java/com/sweet/authstudy/audit/domain/{AuditLog,AuditLogRepository}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/audit/application/{AuditService,AuditCommand,AuditView}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/audit/infrastructure/{AuditLogJpaEntity,AuditLogJpaRepository,AuditLogRepositoryAdapter}.java`
- Create: `backend/src/main/java/com/sweet/authstudy/audit/presentation/AuditAdminController.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/company/application/CompanyService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/position/application/PositionService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/user/application/UserService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/department/application/DepartmentService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/hr/membership/application/MembershipService.java`
- Modify: `backend/src/main/java/com/sweet/authstudy/identity/application/AccountService.java`
- Create: `backend/src/test/java/com/sweet/authstudy/audit/AuditIntegrationTest.java`
- Create: `backend/src/test/java/com/sweet/authstudy/identity/AccountRevocationIntegrationTest.java`

**Interfaces:**
- Consumes: `ActorContext`, `RefreshTokenRepository`, HR application services
- Produces: `AuditService.record(AuditCommand)`, `recordFailure(AuditCommand)`, company-scoped audit list API, role/status/password changes linked to refresh revocation

- [ ] **Step 1: 감사 로그 실패 테스트 작성**

```java
@Test void records_actor_target_company_result_and_trace_without_secrets() {
    userService.resetTemporaryPassword(actor, "ACME", "U001");
    AuditView log = auditService.latestFor("ACME");
    assertThat(log.action()).isEqualTo("USER_TEMPORARY_PASSWORD_RESET");
    assertThat(log.actorAccountId()).isEqualTo(actor.accountId());
    assertThat(log.details()).doesNotContainKeys("temporaryPassword", "passwordHash", "token");
    assertThat(log.traceId()).isNotBlank();
}

@Test void records_identified_business_failure_in_a_separate_transaction() {
    assertThatThrownBy(() -> departmentService.deactivate(actor, "ACME", "DEV", 0))
        .isInstanceOf(ApiException.class);
    AuditView log = auditService.latestFor("ACME");
    assertThat(log.action()).isEqualTo("DEPARTMENT_STATUS_CHANGE");
    assertThat(log.success()).isFalse();
}
```

- [ ] **Step 2: 감사 스키마와 서비스가 없어 실패하는지 확인**

Run: `cd backend; .\gradlew.bat test --tests "*AuditIntegrationTest"`

Expected: FAIL because audit types and table do not exist.

- [ ] **Step 3: V5와 AuditService 구현**

`audit_logs`는 actor_account_id, action, target_type, target_id, company_id nullable, success, occurred_at, trace_id, details jsonb를 갖습니다. update/delete API는 제공하지 않습니다. details는 허용 목록으로 조립하고 요청 DTO 전체 직렬화를 금지합니다.

```java
public record AuditCommand(
    long actorAccountId, String action, String targetType, long targetId,
    Long companyId, boolean success, String traceId, Map<String, Object> safeDetails) {}
```

- [ ] **Step 4: 각 mutation에 명시적 감사 기록 추가**

회사/직위/부서/사용자/소속 생성·수정·상태 변경, 임시 비밀번호 재발급, 관리자 역할 지정·회수에 action 상수를 지정합니다. 같은 트랜잭션에서 업무 변경과 성공 감사 로그를 저장합니다. 대상이 식별된 뒤 발생한 업무 검증 실패는 `AuditService.recordFailure`를 `REQUIRES_NEW` 트랜잭션으로 저장하고 원래 ApiException을 다시 던집니다. 인증되지 않은 요청과 대상 식별 전 권한 거부는 일반 보안 로그로만 남기고 업무 AuditLog에 넣지 않습니다.

- [ ] **Step 5: 상태와 역할 변경의 Refresh 폐기 테스트 작성**

User를 LOCKED/RESIGNED로 변경, Account role 변경, 임시 비밀번호 재발급 후 기존 Refresh Token이 401이 되는지 각각 검증합니다. Company INACTIVE 후 해당 회사의 모든 계정 Refresh가 거부되는지도 검증합니다.

- [ ] **Step 6: 감사와 폐기 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*AuditIntegrationTest" --tests "*AccountRevocationIntegrationTest"`

Expected: PASS with immutable audit rows, safe details, tenant-filtered listing, and revocation rules.

- [ ] **Step 7: 전체 테스트 후 커밋**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

```powershell
git add -- backend/src/main/resources/db/migration/V5__audit_log.sql backend/src/main/java/com/sweet/authstudy/audit backend/src/main/java/com/sweet/authstudy/hr backend/src/main/java/com/sweet/authstudy/identity/application backend/src/test/java/com/sweet/authstudy/audit backend/src/test/java/com/sweet/authstudy/identity/AccountRevocationIntegrationTest.java
git commit -m "feat: add audit logging and account revocation"
```

---

### Task 9: 백엔드 수용 테스트와 모듈 경계 검증

**Files:**
- Create: `backend/src/test/java/com/sweet/authstudy/acceptance/HrAdminAcceptanceTest.java`
- Create: `backend/src/test/java/com/sweet/authstudy/architecture/ModuleBoundaryTest.java`
- Modify: `backend/build.gradle.kts`

**Interfaces:**
- Consumes: Tasks 1~8의 공개 HTTP API와 패키지
- Produces: 백엔드 전체 사용자 여정 테스트와 ArchUnit 모듈 경계 회귀 검사

- [ ] **Step 1: ArchUnit 의존성과 실패 테스트 추가**

`testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")`를 추가하고 다음 규칙을 작성합니다.

```java
@AnalyzeClasses(packages = "com.sweet.authstudy")
class ModuleBoundaryTest {
    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring_or_jpa =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule repositories_are_only_used_by_same_feature_infrastructure_or_application =
        noClasses().that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");
}
```

- [ ] **Step 2: 위반이 있으면 경계가 통과할 때까지 의존 이동**

Run: `cd backend; .\gradlew.bat test --tests "*ModuleBoundaryTest"`

Expected: PASS with no domain Spring/JPA dependency and no presentation-to-infrastructure dependency.

- [ ] **Step 3: 전체 백엔드 여정 테스트 작성**

`HrAdminAcceptanceTest`는 HTTP로 다음 순서를 실행합니다.

1. dev system admin login
2. ACME Company 생성과 기본 직위 5개 확인
3. 회사 관리자 User 생성과 일회성 임시 비밀번호 확인
4. 시스템 관리자가 HQ를 만들고 회사 관리자를 primary HEAD로 배치한 뒤 ACTIVE 전환
5. admin role 지정과 기존 Refresh 폐기 확인
6. 회사 관리자 최초 비밀번호 변경
7. 회사 관리자가 HQ 아래 DEV → API 부서 트리 생성
8. 일반 사용자 생성, DEV primary MEMBER와 API secondary MEMBER 배치 후 ACTIVE 전환
9. BETA 회사 생성 후 ACME 관리자의 BETA API 접근 403 확인
10. 사용자 LOCKED 후 로그인/Refresh 거부 확인
11. 감사 로그에 각 mutation이 있고 비밀 값이 없는지 확인

응답에 저장된 이름 `<img src=x onerror=alert(1)>`이 그대로 문자열 값으로 직렬화되고 HTML 변환을 위한 별도 필드가 생기지 않는 API 계약도 포함합니다.

- [ ] **Step 4: 백엔드 수용 테스트 통과 확인**

Run: `cd backend; .\gradlew.bat test --tests "*HrAdminAcceptanceTest"`

Expected: PASS for the complete HTTP journey.

- [ ] **Step 5: 전체 백엔드 검증과 커밋**

Run: `cd backend; .\gradlew.bat clean test`

Expected: BUILD SUCCESSFUL with all unit, integration, security, architecture, and acceptance tests passing.

```powershell
git add -- backend/build.gradle.kts backend/src/test/java/com/sweet/authstudy/acceptance backend/src/test/java/com/sweet/authstudy/architecture
git commit -m "test: cover HR admin backend acceptance flow"
```

---

### Task 10: Next.js 기반과 인증 상태 관리

**Files:**
- Create: `frontend/` using create-next-app
- Create: `frontend/vitest.config.ts`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/lib/api/problem.ts`
- Create: `frontend/src/lib/api/client.ts`
- Create: `frontend/src/features/auth/auth-api.ts`
- Create: `frontend/src/features/auth/single-flight-refresh.ts`
- Create: `frontend/src/features/auth/auth-provider.tsx`
- Create: `frontend/src/app/login/page.tsx`
- Create: `frontend/src/app/change-password/page.tsx`
- Create: `frontend/src/app/account/page.tsx`
- Modify: `frontend/src/app/layout.tsx`
- Modify: `frontend/next.config.ts`
- Modify: `frontend/package.json`
- Create: `frontend/src/features/auth/auth-provider.test.tsx`
- Create: `frontend/src/app/login/login-page.test.tsx`

**Interfaces:**
- Consumes: `/api/v1/auth/login|refresh|logout|password|me`
- Produces: `useAuth()`, `apiClient.request<T>()`, single-flight refresh, login and forced-password pages

- [ ] **Step 1: Next.js와 shadcn/ui 프로젝트 생성**

Run:

```powershell
npx create-next-app@latest frontend --ts --tailwind --eslint --app --src-dir --import-alias "@/*" --use-npm --yes
cd frontend
npx shadcn@latest init -d
npx shadcn@latest add -y button input label card form alert dialog sidebar breadcrumb table select badge sonner skeleton
npm install -D vitest @vitejs/plugin-react jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event msw
```

Expected: `npm run build` succeeds before application-specific code.

- [ ] **Step 2: Vitest 설정과 인증 복구 실패 테스트 작성**

```tsx
it("restores authentication once and exposes the current actor", async () => {
  let refreshCalls = 0;
  server.use(
    http.post("/api/v1/auth/refresh", () => {
      refreshCalls += 1;
      return HttpResponse.json({ accessToken: "access-1", expiresAt: "2026-08-20T01:00:00Z" });
    }),
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      accountId: 1, companyId: null, userId: null,
      email: "admin@auth-study.local", roles: ["SYSTEM_ADMIN"],
    })),
  );
  render(<AuthProvider><Probe /></AuthProvider>);
  expect(await screen.findByText("admin@auth-study.local")).toBeInTheDocument();
  expect(refreshCalls).toBe(1);
});
```

`vitest.config.ts`는 jsdom, `@/*` alias, `src/test/setup.ts`를 사용합니다. `src/test/setup.ts`는 `setupServer()`를 생성하고 beforeAll/afterEach/afterAll에서 listen/resetHandlers/close를 실행합니다. 테스트 API mock은 MSW를 설치해 HTTP 경계에서 동작시킵니다. package.json에는 `"test": "vitest"` script를 추가합니다.

- [ ] **Step 3: 테스트 실행으로 미구현 실패 확인**

Run: `cd frontend; npm test -- --run src/features/auth/auth-provider.test.tsx`

Expected: FAIL because AuthProvider and API client do not exist.

- [ ] **Step 4: Problem Details와 API client 구현**

```ts
export type ApiProblem = {
  type: string;
  title: string;
  status: number;
  code: string;
  traceId: string;
  fieldErrors: Array<{ field: string; message: string }>;
};

export type AuthSnapshot = {
  accessToken: string;
  actor: {
    accountId: number; companyId: number | null; userId: number | null;
    email: string; roles: string[];
  };
  mustChangePassword: boolean;
};
```

`apiClient`는 Authorization 헤더를 메모리 토큰으로 설정합니다. 401이면 공유된 Promise 하나로 Refresh를 수행하고 원 요청을 한 번만 재시도합니다. 재시도도 401이면 AuthProvider를 signed-out 상태로 만들고 추가 갱신을 시도하지 않습니다.

`next.config.ts`는 브라우저의 `/api/:path*` 요청을 `http://localhost:8080/api/:path*`로 rewrite합니다.

```ts
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: "/api/:path*", destination: "http://localhost:8080/api/:path*" }];
  },
};
```

- [ ] **Step 5: AuthProvider, 로그인과 비밀번호 변경 구현**

AuthProvider 상태는 `loading | authenticated | passwordChangeRequired | anonymous` 네 가지입니다. 최초 mount에서 Refresh 후 `/auth/me`를 호출합니다. 로그인 응답이 mustChangePassword=true이면 `/change-password`로 이동합니다. 로그아웃은 서버 호출 성공 여부와 무관하게 메모리 토큰을 제거합니다.

로그인 폼은 email/password, submit loading, field/server error, traceId를 표시합니다. 비밀번호 변경 폼은 현재 비밀번호, 새 비밀번호/확인과 네 가지 문자군 규칙을 클라이언트에서 동일하게 검증하되 서버 검증을 최종 기준으로 사용합니다. `/account`는 모든 인증 사용자가 이메일·역할과 연결된 HR 사용자 code/name을 읽고 비밀번호 변경 화면으로 이동할 수 있게 합니다.

- [ ] **Step 6: 인증 테스트와 빌드 통과 확인**

Run:

```powershell
cd frontend
npm test -- --run src/features/auth/auth-provider.test.tsx src/app/login/login-page.test.tsx
npm run lint
npm run build
```

Expected: all tests PASS, lint has zero errors, Next.js production build succeeds.

- [ ] **Step 7: 커밋**

```powershell
git add -- frontend/package.json frontend/package-lock.json frontend/next.config.ts frontend/tsconfig.json frontend/eslint.config.mjs frontend/postcss.config.mjs frontend/components.json frontend/public frontend/src frontend/vitest.config.ts
git commit -m "feat: add Next.js authentication shell"
```

---

### Task 11: 역할 기반 관리자 레이아웃과 회사·직위 화면

**Files:**
- Create: `frontend/src/app/(admin)/layout.tsx`
- Create: `frontend/src/app/(admin)/page.tsx`
- Create: `frontend/src/components/layout/admin-sidebar.tsx`
- Create: `frontend/src/components/layout/admin-header.tsx`
- Create: `frontend/src/components/layout/company-switcher.tsx`
- Create: `frontend/src/features/companies/company-api.ts`
- Create: `frontend/src/features/companies/company-table.tsx`
- Create: `frontend/src/features/companies/company-form.tsx`
- Create: `frontend/src/features/positions/position-api.ts`
- Create: `frontend/src/features/positions/position-table.tsx`
- Create: `frontend/src/features/positions/position-form.tsx`
- Create: `frontend/src/app/(admin)/companies/page.tsx`
- Create: `frontend/src/app/(admin)/companies/[companyCode]/positions/page.tsx`
- Create: `frontend/src/components/layout/admin-sidebar.test.tsx`
- Create: `frontend/src/features/companies/company-page.test.tsx`

**Interfaces:**
- Consumes: `useAuth`, company/position admin API and page metadata
- Produces: role-filtered navigation, system-admin company switcher, company and position CRUD UI

- [ ] **Step 1: 역할별 메뉴 실패 테스트 작성**

```tsx
it.each([
  [["SYSTEM_ADMIN"], ["대시보드", "회사", "직위", "부서", "사용자", "감사 로그"]],
  [["COMPANY_ADMIN"], ["대시보드", "직위", "부서", "사용자", "감사 로그"]],
  [["USER"], ["내 계정"]],
])("shows only allowed navigation", (roles, labels) => {
  renderSidebar({ roles, companyCode: roles[0] === "SYSTEM_ADMIN" ? null : "ACME" });
  labels.forEach(label => expect(screen.getByRole("link", { name: label })).toBeVisible());
  if (roles[0] !== "SYSTEM_ADMIN") expect(screen.queryByRole("link", { name: "회사" })).toBeNull();
  if (roles[0] === "USER") expect(screen.queryByRole("link", { name: "사용자" })).toBeNull();
});
```

- [ ] **Step 2: 화면이 없어 실패하는지 확인**

Run: `cd frontend; npm test -- --run src/components/layout/admin-sidebar.test.tsx`

Expected: FAIL because the sidebar does not exist.

- [ ] **Step 3: 관리자 shell과 접근 guard 구현**

좌측 sidebar, 상단 breadcrumb/header, 본문 영역을 구현합니다. SYSTEM_ADMIN은 company switcher로 현재 companyCode를 URL에서 선택하고 COMPANY_ADMIN은 AuthSnapshot의 회사로 고정합니다. 클라이언트 guard가 허용되지 않은 route를 `/`로 보내더라도 백엔드 403 처리를 유지합니다.

- [ ] **Step 4: 회사 목록·폼 실패 테스트와 구현**

```tsx
it("creates a company and renders the five default positions", async () => {
  render(<CompanyPage />);
  await user.click(screen.getByRole("button", { name: "회사 생성" }));
  await user.type(screen.getByLabelText("코드"), "ACME");
  await user.type(screen.getByLabelText("회사명"), "Acme");
  await user.type(screen.getByLabelText("이메일 도메인"), "acme.example");
  await user.click(screen.getByRole("button", { name: "저장" }));
  expect(await screen.findByText("ACME")).toBeVisible();
});
```

회사 화면은 검색, status filter, 서버 pagination, create/edit dialog, inactive 확인 dialog를 제공합니다. 409 field/global error와 traceId를 표시합니다.

- [ ] **Step 5: 직위 화면 구현**

직위 표에는 code, name, level, displayOrder, active를 표시합니다. code는 수정 폼에서 read-only입니다. 비활성화는 확인 dialog를 거치고 stale version 409는 “다른 사용자가 수정했습니다” 메시지와 재조회 버튼을 표시합니다.

- [ ] **Step 6: 화면 테스트, lint와 build 확인**

Run:

```powershell
cd frontend
npm test -- --run src/components/layout/admin-sidebar.test.tsx src/features/companies/company-page.test.tsx
npm run lint
npm run build
```

Expected: PASS and role navigation/company workflows compile without errors.

- [ ] **Step 7: 커밋**

```powershell
git --literal-pathspecs add -- 'frontend/src/app/(admin)' frontend/src/components/layout frontend/src/features/companies frontend/src/features/positions
git commit -m "feat: add company and position admin UI"
```

---

### Task 12: 부서 트리, 사용자와 복수 소속 화면

**Files:**
- Create: `frontend/src/features/departments/department-api.ts`
- Create: `frontend/src/features/departments/department-tree.tsx`
- Create: `frontend/src/features/departments/department-form.tsx`
- Create: `frontend/src/features/users/user-api.ts`
- Create: `frontend/src/features/users/user-table.tsx`
- Create: `frontend/src/features/users/user-form.tsx`
- Create: `frontend/src/features/users/user-detail.tsx`
- Create: `frontend/src/features/users/membership-editor.tsx`
- Create: `frontend/src/app/(admin)/companies/[companyCode]/departments/page.tsx`
- Create: `frontend/src/app/(admin)/companies/[companyCode]/users/page.tsx`
- Create: `frontend/src/app/(admin)/companies/[companyCode]/users/[userCode]/page.tsx`
- Create: `frontend/src/features/departments/department-tree.test.tsx`
- Create: `frontend/src/features/users/membership-editor.test.tsx`

**Interfaces:**
- Consumes: department tree, user page, membership APIs
- Produces: accessible recursive department tree, user lifecycle UI, primary/role-aware membership editor

- [ ] **Step 1: 부서 트리 실패 테스트 작성**

```tsx
it("renders hierarchy and blocks choosing self or descendants as parent", async () => {
  render(<DepartmentTree nodes={hqWithDevAndApi} onMove={onMove} />);
  await user.click(screen.getByRole("button", { name: "HQ 이동" }));
  expect(screen.getByRole("option", { name: "HQ" })).toBeDisabled();
  expect(screen.getByRole("option", { name: "DEV" })).toBeDisabled();
  expect(screen.getByRole("option", { name: "API" })).toBeDisabled();
});
```

- [ ] **Step 2: 소속 editor 실패 테스트 작성**

```tsx
it("requires exactly one primary membership before activation", async () => {
  render(<MembershipEditor memberships={twoSecondaryMemberships} userStatus="PENDING" />);
  await user.click(screen.getByRole("button", { name: "사용자 활성화" }));
  expect(screen.getByText("주 소속을 하나 지정해 주세요.")).toBeVisible();
  expect(activateUser).not.toHaveBeenCalled();
});
```

- [ ] **Step 3: 컴포넌트가 없어 실패하는지 확인**

Run: `cd frontend; npm test -- --run src/features/departments/department-tree.test.tsx src/features/users/membership-editor.test.tsx`

Expected: FAIL because department and membership components do not exist.

- [ ] **Step 4: 부서 트리와 폼 구현**

재귀 treeitem 구조와 키보드 접근 가능한 expand/collapse를 구현합니다. 선택한 부서의 상세/수정/이동 패널을 오른쪽에 표시합니다. 이동 parent 후보에서 자기 자신과 descendants를 disabled 처리하되 서버 검증을 최종 기준으로 유지합니다. 활성 하위/소속 때문에 비활성화가 409이면 영향을 설명합니다.

- [ ] **Step 5: 사용자 목록, 생성과 일회성 비밀번호 dialog 구현**

목록은 name/code/employeeNumber 검색, status filter, pagination을 제공합니다. 생성 폼은 position 목록을 사용하고 성공 응답의 temporaryPassword를 복사 가능한 dialog에 한 번 표시합니다. dialog를 닫으면 React state에서 문자열을 즉시 지우고 다시 열 수 없게 합니다.

- [ ] **Step 6: 사용자 상세와 소속 editor 구현**

상세 화면에서 프로필, status, position, Account email, memberships를 구분해 표시합니다. 소속 추가/수정/종료, primary 전환, HEAD/DEPUTY_HEAD/MEMBER 선택을 지원합니다. SYSTEM_ADMIN만 회사 관리자 지정/회수 버튼을 보고, COMPANY_ADMIN은 일반 사용자 임시 비밀번호 재발급 버튼만 봅니다.

사용자 이름 `<img src=x onerror=alert(1)>`을 API fixture로 렌더링했을 때 태그가 생성되지 않고 화면에 텍스트로 보이며 `document.querySelector("img")`가 null인 Testing Library 테스트를 추가합니다. 컴포넌트에서는 `dangerouslySetInnerHTML`을 사용하지 않습니다.

- [ ] **Step 7: 화면 테스트, lint와 build 확인**

Run:

```powershell
cd frontend
npm test -- --run src/features/departments/department-tree.test.tsx src/features/users/membership-editor.test.tsx
npm run lint
npm run build
```

Expected: PASS with accessible tree, one-time password behavior, role controls, and membership validation.

- [ ] **Step 8: 커밋**

```powershell
git --literal-pathspecs add -- frontend/src/features/departments frontend/src/features/users 'frontend/src/app/(admin)/companies/[companyCode]/departments' 'frontend/src/app/(admin)/companies/[companyCode]/users'
git commit -m "feat: add department and user admin UI"
```

---

### Task 13: 대시보드, 감사 로그, E2E와 실행 문서

**Files:**
- Create: `frontend/src/features/dashboard/dashboard-api.ts`
- Create: `frontend/src/features/dashboard/summary-cards.tsx`
- Create: `frontend/src/features/audit/audit-api.ts`
- Create: `frontend/src/features/audit/audit-table.tsx`
- Create: `frontend/src/app/(admin)/companies/[companyCode]/audit-logs/page.tsx`
- Modify: `frontend/src/app/(admin)/page.tsx`
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/hr-admin-flow.spec.ts`
- Modify: `frontend/package.json`
- Create: `README.md`

**Interfaces:**
- Consumes: 모든 백엔드/프론트엔드 공개 흐름
- Produces: 관리자 summary, 읽기 전용 감사 로그, 브라우저 수용 테스트, 재현 가능한 로컬 실행 안내

- [ ] **Step 1: 대시보드와 감사 로그 화면 구현**

대시보드는 선택 회사의 활성 사용자 수, 부서 수, 잠금/퇴사 사용자 수를 기존 목록 API의 totalElements로 표시합니다. 감사 로그는 occurredAt, actor, action, target, success, traceId를 서버 pagination으로 표시하고 편집 동작을 제공하지 않습니다. SYSTEM_ADMIN은 회사를 선택하고 COMPANY_ADMIN은 자기 회사만 조회합니다.

- [ ] **Step 2: Playwright 설치와 설정**

Run:

```powershell
cd frontend
npm install -D @playwright/test
npx playwright install chromium
```

`playwright.config.ts`는 다음처럼 프론트엔드와 백엔드를 별도 webServer로 실행합니다. 백엔드 readiness URL은 인증되지 않은 요청에 401을 반환해도 서버 준비 상태로 인정됩니다.

```ts
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: [
    {
      command: ".\\gradlew.bat bootRun",
      cwd: "../backend",
      url: "http://localhost:8080/api/v1/auth/me",
      timeout: 120_000,
      reuseExistingServer: true,
    },
    {
      command: "npm run dev",
      url: "http://localhost:3000",
      timeout: 120_000,
      reuseExistingServer: true,
    },
  ],
});
```

package.json에는 `"test:e2e": "playwright test"` script를 추가합니다.

- [ ] **Step 3: 전체 관리자 여정 E2E 작성**

```ts
test("system admin provisions a company and company admin manages organization", async ({ page }) => {
  const suffix = Date.now().toString().slice(-8);
  const companyCode = `ACME${suffix}`;
  const companyDomain = `acme-${suffix}.example`;
  const companyAdminEmail = `admin01@${companyDomain}`;
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@auth-study.local");
  await page.getByLabel("비밀번호").fill("AuthStudy1234!");
  await page.getByRole("button", { name: "로그인" }).click();
  await page.getByRole("link", { name: "회사" }).click();
  await createCompany(page, { code: companyCode, name: "Acme", domain: companyDomain });
  const temporaryPassword = await createCompanyAdminAndCaptureTemporaryPassword(
    page, companyCode, "ADMIN01", companyAdminEmail);
  await createDepartmentHierarchy(page, ["HQ"]);
  await assignPrimaryMembershipAndActivate(page, "ADMIN01", "HQ", "HEAD");
  await grantCompanyAdmin(page, "ADMIN01");
  await logout(page);
  await loginAndChangeTemporaryPassword(page, companyAdminEmail, temporaryPassword);
  await createDepartmentHierarchy(page, ["HQ/DEV", "HQ/DEV/API"]);
  await createUserWithMemberships(page, "U001", ["DEV", "API"]);
  await expect(page.getByText("DEV · 주 소속")).toBeVisible();
});
```

helper 함수는 같은 spec 파일 아래에 실제 UI label과 route를 사용해 구현합니다. 테스트 종료 시 생성한 code에 timestamp suffix를 사용해 재실행 충돌을 피합니다.

- [ ] **Step 4: README 작성**

README에는 다음 순서와 값을 그대로 기록합니다.

```powershell
docker compose -f infrastructure/docker-compose.yml up -d
cd backend
.\gradlew.bat bootRun
cd ..\frontend
npm install
npm run dev
```

접속 URL `http://localhost:3000`, 관리자 `admin@auth-study.local` / `AuthStudy1234!`, 테스트 명령, DB 중지 명령 `docker compose -f infrastructure/docker-compose.yml down`을 포함합니다. 개발용 자격 증명과 JWT 키를 다른 환경에서 재사용하지 말라는 경고를 첫 실행 절에 표시합니다.

- [ ] **Step 5: 전체 검증 실행**

Run:

```powershell
docker compose -f infrastructure/docker-compose.yml up -d
cd backend
.\gradlew.bat clean test
cd ..\frontend
npm test -- --run
npm run lint
npm run build
npm run test:e2e
```

Expected: backend BUILD SUCCESSFUL; frontend unit tests, lint, build, and Playwright E2E all exit 0.

- [ ] **Step 6: 작업 트리와 비밀값 검사**

Run:

```powershell
git status --short
rg -n "BEGIN (RSA |EC )?PRIVATE KEY|sk_live_|AKIA[0-9A-Z]{16}" backend frontend infrastructure README.md
```

Expected: only intended Task 13 files are modified; secret scan has no matches. Committed dev credentials are limited to the explicitly approved YAML/README values.

- [ ] **Step 7: 최종 커밋**

```powershell
git --literal-pathspecs add -- frontend/src/features/dashboard frontend/src/features/audit 'frontend/src/app/(admin)/page.tsx' 'frontend/src/app/(admin)/companies/[companyCode]/audit-logs' frontend/playwright.config.ts frontend/e2e frontend/package.json frontend/package-lock.json README.md
git commit -m "feat: complete HR admin study environment"
```

- [ ] **Step 8: 최종 커밋 범위와 상태 확인**

Run:

```powershell
git log --oneline --decorate -15
git status --short --branch
```

Expected: Tasks 1~13 commits are present in order and the worktree is clean. Do not push unless the user separately authorizes push.
