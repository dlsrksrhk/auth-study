package com.sweet.authstudy.hr.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@Transactional
class UserPositionOwnershipMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void migrates_an_empty_database_through_the_position_ownership_constraint() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .contains("6");
        assertThat(constraintExists("uk_positions_id_company")).isTrue();
        assertThat(constraintExists("fk_users_position_company")).isTrue();
        assertThat(constraintExists("users_position_id_fkey")).isFalse();
    }

    @Test
    void database_accepts_a_user_and_position_from_the_same_company() {
        Fixture fixture = fixture();

        assertThatCode(() -> insertUser(fixture.firstCompanyId(), fixture.firstPositionId()))
                .doesNotThrowAnyException();
    }

    @Test
    void database_rejects_a_user_with_a_position_from_another_company() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> insertUser(fixture.firstCompanyId(), fixture.secondPositionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_rejects_moving_a_referenced_position_to_another_company() {
        Fixture fixture = fixture();
        insertUser(fixture.firstCompanyId(), fixture.firstPositionId());

        assertThatThrownBy(() -> jdbc.update(
                "update positions set company_id = ? where id = ?",
                fixture.secondCompanyId(), fixture.firstPositionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private boolean constraintExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pg_constraint where conname = ?)",
                Boolean.class, name));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long firstCompanyId = insertCompany("A" + suffix, "a" + suffix + ".example");
        long secondCompanyId = insertCompany("B" + suffix, "b" + suffix + ".example");
        long firstPositionId = insertPosition(firstCompanyId, "FIRST" + suffix);
        long secondPositionId = insertPosition(secondCompanyId, "SECOND" + suffix);
        return new Fixture(firstCompanyId, secondCompanyId, firstPositionId, secondPositionId);
    }

    private long insertCompany(String code, String domain) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                insert into companies (code, name, email_domain, status, created_at, updated_at)
                values (?, ?, ?, 'ACTIVE', ?, ?)
                returning id
                """, Long.class, code, code, domain, now, now);
    }

    private long insertPosition(long companyId, String code) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                insert into positions
                    (company_id, code, name, level, display_order, active, created_at, updated_at)
                values (?, ?, ?, 1, 1, true, ?, ?)
                returning id
                """, Long.class, companyId, code, code, now, now);
    }

    private void insertUser(long companyId, long positionId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into users
                    (company_id, code, employee_number, name, phone, hired_at, workplace,
                     position_id, status, created_at, updated_at)
                values (?, ?, ?, 'User', '010-0000-0000', ?, 'Seoul', ?, 'PENDING', ?, ?)
                """, companyId, "U" + suffix, "E" + suffix, LocalDate.parse("2026-08-20"),
                positionId, now, now);
    }

    private record Fixture(
            long firstCompanyId,
            long secondCompanyId,
            long firstPositionId,
            long secondPositionId) {
    }
}
