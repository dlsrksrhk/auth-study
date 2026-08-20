package com.sweet.authstudy.audit;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.ChangeDepartmentStatusCommand;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipCommands.UpdateMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserCommands.UpdateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuditIntegrationTest {

    @Autowired CompanyService companyService;
    @Autowired DepartmentService departmentService;
    @Autowired UserService userService;
    @Autowired PositionService positionService;
    @Autowired MembershipService membershipService;
    @Autowired AuditService auditService;
    @Autowired CompanyRepository companyRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void records_actor_target_company_result_and_trace_without_secrets() {
        Fixture fixture = fixture();
        var created = userService.create(SYSTEM_ADMIN, new CreateUserCommand(
                fixture.code(), "U001", "E-1001", "Kim", "kim@" + fixture.domain(),
                "010-0000-0000", LocalDate.of(2026, 8, 20), "Seoul", null, "EMPLOYEE"));

        String temporaryPassword = userService.resetTemporaryPassword(
                SYSTEM_ADMIN, fixture.code(), created.user().code());

        var page = auditService.list(SYSTEM_ADMIN, fixture.code(),
                AuditActions.USER_TEMPORARY_PASSWORD_RESET, true, 0, 20, "occurredAt");
        assertThat(page.content()).hasSize(1);
        var log = page.content().getFirst();
        assertThat(log.actorAccountId()).isEqualTo(SYSTEM_ADMIN.accountId());
        assertThat(log.action()).isEqualTo(AuditActions.USER_TEMPORARY_PASSWORD_RESET);
        assertThat(log.targetType()).isEqualTo("USER");
        assertThat(log.targetId()).isEqualTo(created.user().id());
        assertThat(log.companyId()).isEqualTo(fixture.id());
        assertThat(log.success()).isTrue();
        assertThat(log.traceId()).isNotBlank();
        assertThat(log.details().keySet()).allSatisfy(key -> assertThat(key.toLowerCase())
                .doesNotContain("password", "token", "request"));
        String storedDetails = jdbc.queryForObject(
                "select details::text from audit_logs where id = ?", String.class, log.id());
        assertThat(storedDetails).doesNotContain(temporaryPassword)
                .doesNotContain("temporaryPassword", "passwordHash", "accessToken", "refreshToken");
    }

    @Test
    void records_identified_business_failure_after_the_business_transaction_rolls_back() {
        Fixture fixture = fixture();
        var department = departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand(fixture.code(), "DEV", "Development", null));

        assertThatThrownBy(() -> departmentService.changeStatus(SYSTEM_ADMIN,
                new ChangeDepartmentStatusCommand(
                        fixture.code(), department.code(), DepartmentStatus.INACTIVE, department.version() + 1)))
                .isInstanceOf(ApiException.class);

        var failures = auditService.list(SYSTEM_ADMIN, fixture.code(),
                AuditActions.DEPARTMENT_STATUS_CHANGE, false, 0, 20, "occurredAt");
        assertThat(failures.content()).singleElement().satisfies(log -> {
            assertThat(log.targetId()).isEqualTo(department.id());
            assertThat(log.success()).isFalse();
            assertThat(log.details()).containsKey("errorCode");
        });
    }

    @Test
    void successful_audit_rolls_back_with_its_business_change() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "T" + suffix.toUpperCase();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand(
                    code, "Rollback Co", suffix + ".rollback.example"));
            throw new ApiException(com.sweet.authstudy.shared.error.ErrorCode.INVALID_STATE, "rollback");
        })).isInstanceOf(ApiException.class);

        assertThat(companyRepository.findByCode(code)).isEmpty();
        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_logs where details ->> 'code' = ?", Integer.class, code);
        assertThat(audits).isZero();
    }

    @Test
    void company_admin_can_only_list_its_company_and_filters_are_server_side() {
        Fixture own = fixture();
        Fixture other = fixture();
        AuthenticatedAccount companyAdmin = new AuthenticatedAccount(
                991_001, own.id(), null, Set.of(AccountRole.COMPANY_ADMIN), false);

        var ownPage = auditService.list(companyAdmin, own.code(),
                AuditActions.COMPANY_CREATE, true, 0, 10, "action");
        assertThat(ownPage.content()).allMatch(log -> log.companyId() == own.id());
        assertThatThrownBy(() -> auditService.list(companyAdmin, other.code(),
                null, null, 0, 10, "occurredAt"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void every_administrative_mutation_uses_a_stable_action() {
        Fixture fixture = fixture();
        var company = companyService.find(SYSTEM_ADMIN, fixture.code());
        companyService.update(SYSTEM_ADMIN, fixture.code(),
                new UpdateCompanyCommand("Renamed", CompanyStatus.ACTIVE, company.version()));

        var position = positionService.create(SYSTEM_ADMIN, fixture.code(),
                new CreatePositionCommand("LEAD", "Lead", 60, 60));
        position = positionService.update(SYSTEM_ADMIN, fixture.code(), position.code(),
                new UpdatePositionCommand("Lead II", 60, 60, true, position.version()));
        positionService.update(SYSTEM_ADMIN, fixture.code(), position.code(),
                new UpdatePositionCommand("Lead II", 60, 60, false, position.version()));

        var root = departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand(fixture.code(), "ROOT", "Root", null));
        root = departmentService.update(SYSTEM_ADMIN,
                new com.sweet.authstudy.hr.department.application.DepartmentCommands.UpdateDepartmentCommand(
                        fixture.code(), root.code(), "Root II", null,
                        DepartmentStatus.ACTIVE, root.version()));
        var dev = departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand(fixture.code(), "DEV2", "Development", null));
        dev = departmentService.move(SYSTEM_ADMIN,
                new com.sweet.authstudy.hr.department.application.DepartmentCommands.MoveDepartmentCommand(
                        fixture.code(), dev.code(), root.code(), dev.version()));

        var user = userService.create(SYSTEM_ADMIN, new CreateUserCommand(
                fixture.code(), "U002", "E-1002", "Lee", "lee@" + fixture.domain(),
                "010-1111-1111", LocalDate.of(2026, 8, 20), "Seoul", null, "EMPLOYEE"));
        var updatedUser = userService.update(SYSTEM_ADMIN, fixture.code(), user.user().code(),
                new UpdateUserCommand("Lee II", "010-2222-2222", LocalDate.of(2026, 8, 20),
                        "Busan", null, "EMPLOYEE", user.user().version()));
        var membership = membershipService.assign(SYSTEM_ADMIN, new AssignMembershipCommand(
                fixture.code(), updatedUser.code(), root.code(), DepartmentRole.MEMBER,
                true, Instant.parse("2026-08-20T00:00:00Z")));
        membership = membershipService.update(SYSTEM_ADMIN, new UpdateMembershipCommand(
                fixture.code(), updatedUser.code(), membership.id(), DepartmentRole.HEAD,
                true, membership.version()));
        membershipService.end(SYSTEM_ADMIN, fixture.code(), updatedUser.code(),
                membership.id(), membership.version());
        departmentService.changeStatus(SYSTEM_ADMIN,
                new ChangeDepartmentStatusCommand(fixture.code(), dev.code(),
                        DepartmentStatus.INACTIVE, dev.version()));
        var locked = userService.changeStatus(SYSTEM_ADMIN, fixture.code(), updatedUser.code(),
                com.sweet.authstudy.hr.user.domain.UserStatus.LOCKED, updatedUser.version());
        userService.resetTemporaryPassword(SYSTEM_ADMIN, fixture.code(), locked.code());
        userService.assignCompanyAdmin(SYSTEM_ADMIN, fixture.code(), locked.code());
        userService.revokeCompanyAdmin(SYSTEM_ADMIN, fixture.code(), locked.code());

        company = companyService.find(SYSTEM_ADMIN, fixture.code());
        companyService.update(SYSTEM_ADMIN, fixture.code(),
                new UpdateCompanyCommand(company.name(), CompanyStatus.INACTIVE, company.version()));

        var actions = auditService.list(SYSTEM_ADMIN, fixture.code(),
                null, null, 0, 100, "occurredAt").content().stream()
                .map(log -> log.action()).collect(java.util.stream.Collectors.toSet());
        assertThat(actions).contains(
                AuditActions.COMPANY_CREATE, AuditActions.COMPANY_UPDATE,
                AuditActions.COMPANY_STATUS_CHANGE, AuditActions.POSITION_CREATE,
                AuditActions.POSITION_UPDATE, AuditActions.POSITION_STATUS_CHANGE,
                AuditActions.DEPARTMENT_CREATE, AuditActions.DEPARTMENT_UPDATE,
                AuditActions.DEPARTMENT_MOVE, AuditActions.DEPARTMENT_STATUS_CHANGE,
                AuditActions.USER_CREATE, AuditActions.USER_UPDATE, AuditActions.USER_STATUS_CHANGE,
                AuditActions.USER_TEMPORARY_PASSWORD_RESET, AuditActions.MEMBERSHIP_CREATE,
                AuditActions.MEMBERSHIP_UPDATE, AuditActions.MEMBERSHIP_END,
                AuditActions.COMPANY_ADMIN_GRANT, AuditActions.COMPANY_ADMIN_REVOKE);
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "A" + suffix.toUpperCase();
        String domain = suffix + ".audit.example";
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand(code, "Audit Co", domain));
        long id = companyRepository.findByCode(code).orElseThrow().id();
        return new Fixture(code, domain, id);
    }

    private record Fixture(String code, String domain, long id) {}
}
