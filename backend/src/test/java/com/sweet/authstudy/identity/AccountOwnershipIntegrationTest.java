package com.sweet.authstudy.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@Transactional
class AccountOwnershipIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private PositionService positionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private Clock clock;

    @Test
    void database_rejects_account_linking_a_company_to_another_companys_user() {
        CompanyView acme = companyService.create(
                new CreateCompanyCommand("ACME", "Acme", "acme.example"));
        CompanyView beta = companyService.create(
                new CreateCompanyCommand("BETA", "Beta", "beta.example"));
        long betaPositionId = positionService.list("BETA").stream()
                .filter(position -> position.code().equals("EMPLOYEE"))
                .findFirst()
                .orElseThrow()
                .id();
        HrUser betaUser = userRepository.save(HrUser.create(
                beta.id(), "U001", "E-1001", "Kim", "010-0000-0000",
                LocalDate.parse("2026-08-20"), "Seoul", null, betaPositionId, clock.instant()));

        Account crossCompanyAccount = Account.createCompanyAccount(
                acme.id(), betaUser.id(), "cross@acme.example", "hash", clock.instant());

        assertThatThrownBy(() -> accountRepository.save(crossCompanyAccount))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
