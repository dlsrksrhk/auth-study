package com.sweet.authstudy.hr.company;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.position.application.PositionView;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@Transactional
class CompanyServiceIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private PositionService positionService;

    @Test
    void creates_company_with_normalized_identity_and_five_positions() {
        CompanyView company = companyService.create(SYSTEM_ADMIN,
                new CreateCompanyCommand("acme", "Acme", " ACME.EXAMPLE "));

        assertThat(company.code()).isEqualTo("ACME");
        assertThat(company.emailDomain()).isEqualTo("acme.example");
        assertThat(positionService.list(SYSTEM_ADMIN, "ACME")).extracting(PositionView::code)
                .containsExactly(
                        "EMPLOYEE",
                        "ASSISTANT_MANAGER",
                        "MANAGER",
                        "DEPUTY_GENERAL_MANAGER",
                        "GENERAL_MANAGER");
    }

    @Test
    void rejects_duplicate_domain_case_insensitively() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("ACME", "Acme", "acme.example"));

        assertThatThrownBy(() -> companyService.create(SYSTEM_ADMIN,
                        new CreateCompanyCommand("BETA", "Beta", "ACME.EXAMPLE")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void rejects_reserved_auth_study_local_domain() {
        assertThatThrownBy(() -> companyService.create(SYSTEM_ADMIN,
                        new CreateCompanyCommand("LOCAL", "Local", "auth-study.local")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void updates_company_name_and_status_without_changing_identity() {
        CompanyView created = companyService.create(SYSTEM_ADMIN,
                new CreateCompanyCommand("ACME", "Acme", "acme.example"));

        CompanyView updated = companyService.update(SYSTEM_ADMIN,
                "acme", new UpdateCompanyCommand("Acme Korea", CompanyStatus.INACTIVE, created.version()));

        assertThat(updated)
                .extracting(CompanyView::code, CompanyView::name, CompanyView::emailDomain, CompanyView::status)
                .containsExactly("ACME", "Acme Korea", "acme.example", CompanyStatus.INACTIVE);
        assertThat(updated.version()).isGreaterThan(created.version());
    }

    @Test
    void creates_and_updates_position_without_changing_its_code() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("ACME", "Acme", "acme.example"));

        PositionView created = positionService.create(SYSTEM_ADMIN,
                "acme", new CreatePositionCommand("senior_manager", "Senior Manager", 60, 60));
        PositionView updated = positionService.update(SYSTEM_ADMIN,
                "ACME",
                "senior_manager",
                new UpdatePositionCommand("Senior Manager II", 70, 70, false, created.version()));

        assertThat(updated)
                .extracting(PositionView::code, PositionView::name, PositionView::level,
                        PositionView::displayOrder, PositionView::active)
                .containsExactly("SENIOR_MANAGER", "Senior Manager II", 70, 70, false);
    }

    @Test
    void rejects_duplicate_position_code_case_insensitively_within_a_company() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("ACME", "Acme", "acme.example"));
        positionService.create(SYSTEM_ADMIN, "ACME", new CreatePositionCommand("LEAD", "Lead", 60, 60));

        assertThatThrownBy(() -> positionService.create(SYSTEM_ADMIN,
                        "ACME", new CreatePositionCommand("lead", "Lead II", 70, 70)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.DUPLICATE_CODE));
    }
}
