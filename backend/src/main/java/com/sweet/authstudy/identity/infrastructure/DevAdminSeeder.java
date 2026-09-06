package com.sweet.authstudy.identity.infrastructure;

import java.time.Clock;
import java.util.Locale;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@Order(0)
public class DevAdminSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppSecurityProperties securityProperties;
    private final Clock clock;

    public DevAdminSeeder(
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            AppSecurityProperties securityProperties,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        AppSecurityProperties.BootstrapAdmin bootstrapAdmin = securityProperties.bootstrapAdmin();
        String email = bootstrapAdmin.email().trim().toLowerCase(Locale.ROOT);
        if (accountRepository.findSystemByEmail(email).isPresent()) {
            return;
        }
        accountRepository.save(Account.createSystemAdmin(
                email,
                passwordEncoder.encode(bootstrapAdmin.password()),
                bootstrapAdmin.mustChangePassword(),
                clock.instant()));
    }
}
