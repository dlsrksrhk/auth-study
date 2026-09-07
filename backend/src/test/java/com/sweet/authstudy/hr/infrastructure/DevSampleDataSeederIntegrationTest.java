package com.sweet.authstudy.hr.infrastructure;

import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.dev.sample-data.enabled=true")
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles({"dev", "test"})
@Transactional
class DevSampleDataSeederIntegrationTest {
    @Autowired
    private DevSampleDataSeeder seeder;
    @Autowired
    private CompanyRepository companies;
    @Autowired
    private DepartmentRepository departments;
    @Autowired
    private PositionRepository positions;
    @Autowired
    private UserRepository users;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private PasswordEncoder passwords;

    @Test
    void startup_creates_twenty_active_users_with_accounts_positions_and_primary_departments() {
        var company = companies.findByCode("DEMO").orElseThrow();
        assertThat(company.emailDomain()).isEqualTo("demo.example");
        assertThat(departments.findAllByCompanyId(company.id())).hasSize(5);
        assertThat(positions.findAllByCompanyId(company.id())).hasSize(5);
        var employees = users.findAllByCompanyId(company.id());
        assertThat(employees).hasSize(20);
        for (var user : employees) {
            assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(memberships.findAllByUserId(user.id())).hasSize(1);
            assertThat(memberships.existsActivePrimaryByUserId(user.id())).isTrue();
            var account = accounts.findByUserId(user.id()).orElseThrow();
            assertThat(account.loginEmail()).endsWith("@demo.example");
            assertThat(account.mustChangePassword()).isFalse();
            assertThat(passwords.matches("Demo1234!", account.passwordHash())).isTrue();
        }
    }

    @Test
    void rerun_preserves_existing_data_and_passwords() {
        var company = companies.findByCode("DEMO").orElseThrow();
        var user = users.findByCompanyIdAndCode(company.id(), "U001").orElseThrow();
        user.changeStatus(UserStatus.LOCKED, Instant.now());
        users.save(user);
        var account = accounts.findByUserId(user.id()).orElseThrow();
        account.changePassword(passwords.encode("Changed1234!"), Instant.now());
        accounts.save(account);
        var before = users.findAllByCompanyId(company.id()).stream().map(item -> item.id()).toList();

        seeder.run();

        assertThat(users.findAllByCompanyId(company.id()).stream().map(item -> item.id()).toList())
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(users.findById(user.id()).orElseThrow().status()).isEqualTo(UserStatus.LOCKED);
        assertThat(passwords.matches("Changed1234!", accounts.findByUserId(user.id()).orElseThrow().passwordHash()))
                .isTrue();
        assertThat(departments.findAllByCompanyId(company.id())).hasSize(5);
        assertThat(positions.findAllByCompanyId(company.id())).hasSize(5);
    }
}
