package com.sweet.authstudy.audit;

import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.UpdateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=1000"
})
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuditPoolStarvationIntegrationTest {
    @Autowired
    CompanyService companyService;
    @Autowired
    DepartmentService departmentService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void two_identified_failures_with_a_two_connection_pool_keep_original_errors_and_both_audits()
            throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        installDelayedUniqueFailureTrigger();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFailure = executor.submit(() -> updateAndCapture(first, start));
            var secondFailure = executor.submit(() -> updateAndCapture(second, start));

            assertOriginalFailure(firstFailure.get(10, TimeUnit.SECONDS));
            assertOriginalFailure(secondFailure.get(10, TimeUnit.SECONDS));
        } finally {
            removeDelayedUniqueFailureTrigger();
        }

        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_logs where success = false and action = ? "
                        + "and target_id in (?, ?)",
                Integer.class, AuditActions.DEPARTMENT_UPDATE, first.departmentId(), second.departmentId());
        assertThat(audits).isEqualTo(2);
    }

    private Throwable updateAndCapture(Fixture fixture, CyclicBarrier start) throws Exception {
        start.await();
        try {
            departmentService.update(SYSTEM_ADMIN, new UpdateDepartmentCommand(
                    fixture.companyCode(), "DEV", "Changed", null,
                    DepartmentStatus.ACTIVE, fixture.version()));
            throw new AssertionError("Update unexpectedly succeeded.");
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void assertOriginalFailure(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(ApiException.class,
                api -> assertThat(api.errorCode()).isEqualTo(ErrorCode.DUPLICATE_CODE));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "P" + suffix.toUpperCase();
        companyService.create(SYSTEM_ADMIN,
                new CreateCompanyCommand(code, "Pool Co", suffix + ".pool.example"));
        var department = departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand(code, "DEV", "Development", null));
        return new Fixture(code, department.id(), department.version());
    }

    private void installDelayedUniqueFailureTrigger() {
        jdbc.execute("CREATE OR REPLACE FUNCTION task8_delayed_department_failure() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(2); "
                + "RAISE unique_violation USING MESSAGE = 'forced unique violation', "
                + "CONSTRAINT = 'uk_departments_company_code_upper'; END $$");
        jdbc.execute("CREATE TRIGGER task8_delayed_department_failure_trigger BEFORE UPDATE ON departments "
                + "FOR EACH ROW EXECUTE FUNCTION task8_delayed_department_failure()");
    }

    private void removeDelayedUniqueFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS task8_delayed_department_failure_trigger ON departments");
        jdbc.execute("DROP FUNCTION IF EXISTS task8_delayed_department_failure()");
    }

    private record Fixture(String companyCode, long departmentId, long version) {
    }
}
