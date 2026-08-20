package com.sweet.authstudy.hr.user;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.position.application.PositionView;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.application.UserViews.CreatedUserView;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.application.AccountService;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@Transactional
class UserCreationIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private PositionService positionService;

    @Autowired
    private UserService userService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createCompany() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("ACME", "Acme", "acme.example"));
    }

    @Test
    void creates_pending_user_account_and_one_time_temporary_password() {
        CreatedUserView result = userService.create(SYSTEM_ADMIN, command(
                "U001", "E-1001", "kim@acme.example", "EMPLOYEE"));

        Account account = accountRepository.findByUserId(result.user().id()).orElseThrow();
        assertThat(result.user().status()).isEqualTo(UserStatus.PENDING);
        assertThat(result.user().code()).isEqualTo("U001");
        assertThat(result.user().employeeNumber()).isEqualTo("E-1001");
        assertThat(result.user().loginEmail()).isEqualTo("kim@acme.example");
        assertThat(result.temporaryPassword()).hasSize(16)
                .containsPattern("[A-Z]")
                .containsPattern("[a-z]")
                .containsPattern("[0-9]")
                .containsPattern("[^A-Za-z0-9]");
        assertThat(account.mustChangePassword()).isTrue();
        assertThat(account.roles()).containsExactly(AccountRole.USER);
        assertThat(account.passwordHash()).startsWith("$2a$12$")
                .doesNotContain(result.temporaryPassword());
        assertThat(passwordEncoder.matches(result.temporaryPassword(), account.passwordHash())).isTrue();
    }

    @Test
    void normalizes_user_identity_and_login_email() {
        CreatedUserView result = userService.create(SYSTEM_ADMIN, new CreateUserCommand(
                "acme", "u001", " E-1001 ", " Kim ", " KIM@ACME.EXAMPLE ",
                " 010-0000-0000 ", LocalDate.parse("2026-08-20"), " Seoul ", null, "employee"));

        assertThat(result.user().code()).isEqualTo("U001");
        assertThat(result.user().employeeNumber()).isEqualTo("E-1001");
        assertThat(result.user().name()).isEqualTo("Kim");
        assertThat(result.user().loginEmail()).isEqualTo("kim@acme.example");
    }

    @Test
    void resets_temporary_password_and_only_returns_the_plaintext_once() {
        CreatedUserView created = userService.create(SYSTEM_ADMIN, command(
                "U001", "E-1001", "kim@acme.example", "EMPLOYEE"));
        Account before = accountRepository.findByUserId(created.user().id()).orElseThrow();

        String resetPassword = accountService.resetTemporaryPassword(before.id());

        Account after = accountRepository.findById(before.id()).orElseThrow();
        assertThat(resetPassword).hasSize(16).isNotEqualTo(created.temporaryPassword());
        assertThat(after.passwordHash()).isNotEqualTo(before.passwordHash()).doesNotContain(resetPassword);
        assertThat(after.mustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches(resetPassword, after.passwordHash())).isTrue();
        assertThat(passwordEncoder.matches(created.temporaryPassword(), after.passwordHash())).isFalse();
    }

    @Test
    void rejects_login_email_outside_company_domain() {
        assertFailure(
                command("U001", "E-1001", "kim@other.example", "EMPLOYEE"),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejects_duplicate_user_code_within_company_case_insensitively() {
        userService.create(SYSTEM_ADMIN, command("U001", "E-1001", "kim@acme.example", "EMPLOYEE"));

        assertFailure(
                command("u001", "E-1002", "lee@acme.example", "EMPLOYEE"),
                ErrorCode.DUPLICATE_CODE);
    }

    @Test
    void rejects_duplicate_login_email_within_company_case_insensitively() {
        userService.create(SYSTEM_ADMIN, command("U001", "E-1001", "kim@acme.example", "EMPLOYEE"));

        assertFailure(
                command("U002", "E-1002", " KIM@ACME.EXAMPLE ", "EMPLOYEE"),
                ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void rejects_duplicate_employee_number_within_company() {
        userService.create(SYSTEM_ADMIN, command("U001", "E-1001", "kim@acme.example", "EMPLOYEE"));

        assertFailure(
                command("U002", "E-1001", "lee@acme.example", "EMPLOYEE"),
                ErrorCode.DUPLICATE_EMPLOYEE_NUMBER);
    }

    @Test
    void rejects_position_owned_by_another_company() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("BETA", "Beta", "beta.example"));
        positionService.create(SYSTEM_ADMIN, "BETA", new CreatePositionCommand("ARCHITECT", "Architect", 60, 60));

        assertFailure(
                command("U001", "E-1001", "kim@acme.example", "ARCHITECT"),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void rejects_user_creation_for_inactive_company() {
        CompanyView company = companyService.find(SYSTEM_ADMIN, "ACME");
        companyService.update(SYSTEM_ADMIN,
                "ACME", new UpdateCompanyCommand(company.name(), CompanyStatus.INACTIVE, company.version()));

        assertFailure(
                command("U001", "E-1001", "kim@acme.example", "EMPLOYEE"),
                ErrorCode.INVALID_STATE);
    }

    @Test
    void rejects_user_creation_for_inactive_position() {
        PositionView position = positionService.list(SYSTEM_ADMIN, "ACME").stream()
                .filter(view -> view.code().equals("EMPLOYEE"))
                .findFirst()
                .orElseThrow();
        positionService.update(SYSTEM_ADMIN,
                "ACME",
                "EMPLOYEE",
                new UpdatePositionCommand(
                        position.name(), position.level(), position.displayOrder(), false, position.version()));

        assertFailure(
                command("U001", "E-1001", "kim@acme.example", "EMPLOYEE"),
                ErrorCode.INVALID_STATE);
    }

    private CreateUserCommand command(String code, String employeeNumber, String email, String positionCode) {
        return new CreateUserCommand(
                "ACME", code, employeeNumber, "Kim", email,
                "010-0000-0000", LocalDate.parse("2026-08-20"), "Seoul", null, positionCode);
    }

    private void assertFailure(CreateUserCommand command, ErrorCode errorCode) {
        assertThatThrownBy(() -> userService.create(SYSTEM_ADMIN, command))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }
}
