package com.sweet.authstudy.identity;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.infrastructure.DevAdminSeeder;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("dev")
class DevAdminSeederIntegrationTest {

    @Autowired
    private DevAdminSeeder seeder;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void creates_idempotent_system_admin_without_company_or_user() {
        seeder.run();
        seeder.run();

        Account admin = accountRepository.findSystemByEmail("admin@auth-study.local").orElseThrow();
        assertThat(admin.companyId()).isNull();
        assertThat(admin.userId()).isNull();
        assertThat(admin.roles()).containsExactly(AccountRole.SYSTEM_ADMIN);
        assertThat(admin.mustChangePassword()).isFalse();
        assertThat(admin.passwordHash()).startsWith("$2a$12$");
        assertThat(passwordEncoder.matches("AuthStudy1234!", admin.passwordHash())).isTrue();
    }
}
