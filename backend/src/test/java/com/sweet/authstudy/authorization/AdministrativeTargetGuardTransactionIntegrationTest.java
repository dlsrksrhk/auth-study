package com.sweet.authstudy.authorization;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AdministrativeTargetGuardTransactionIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private UserService userService;

    @Autowired
    private AdministrativeTargetGuard targetGuard;

    @Test
    void refuses_to_lock_an_administrative_target_without_an_active_transaction() {
        companyService.create(SYSTEM_ADMIN,
                new CreateCompanyCommand("GUARDTX", "Guard Tx", "guard-tx.example"));
        long userId = userService.create(SYSTEM_ADMIN, new CreateUserCommand(
                "GUARDTX", "U001", "E-1001", "Kim", "kim@guard-tx.example",
                "010-0000-0000", LocalDate.parse("2026-08-20"), "Seoul", null, "EMPLOYEE"))
                .user().id();

        assertThatThrownBy(() -> targetGuard.requireMayMutateUser(SYSTEM_ADMIN, userId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
