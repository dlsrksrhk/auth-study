package com.sweet.authstudy.hr.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevSampleDataSeederConditionTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DevSampleDataSeeder.class)
            .withBean(CompanyRepository.class, () -> mock(CompanyRepository.class))
            .withBean(CompanyService.class, () -> mock(CompanyService.class))
            .withBean(DepartmentService.class, () -> mock(DepartmentService.class))
            .withBean(UserService.class, () -> mock(UserService.class))
            .withBean(MembershipService.class, () -> mock(MembershipService.class))
            .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(AppSecurityProperties.class, () -> mock(AppSecurityProperties.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void dev_does_not_seed_without_explicit_opt_in() {
        context.withPropertyValues("spring.profiles.active=dev")
                .run(result -> assertThat(result).doesNotHaveBean(DevSampleDataSeeder.class));
        context.withPropertyValues("spring.profiles.active=dev", "app.dev.sample-data.enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(DevSampleDataSeeder.class));
    }

    @Test
    void flag_cannot_enable_seeding_outside_dev() {
        context.withPropertyValues("spring.profiles.active=prod", "app.dev.sample-data.enabled=true")
                .run(result -> assertThat(result).doesNotHaveBean(DevSampleDataSeeder.class));
    }

    @Test
    void dev_and_explicit_opt_in_enable_seeder() {
        context.withPropertyValues("spring.profiles.active=dev", "app.dev.sample-data.enabled=true")
                .run(result -> assertThat(result).hasSingleBean(DevSampleDataSeeder.class));
    }
}
