package com.sweet.authstudy.hr.department;

import static com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import static com.sweet.authstudy.hr.department.application.DepartmentCommands.ChangeDepartmentStatusCommand;
import static com.sweet.authstudy.hr.department.application.DepartmentCommands.MoveDepartmentCommand;
import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.department.application.DepartmentView;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
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
class DepartmentTreeIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private DepartmentService departmentService;

    @BeforeEach
    void createCompany() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("ACME", "Acme", "acme.example"));
    }

    @Test
    void rejects_moving_department_below_its_descendant() {
        DepartmentView hq = createDepartment("HQ", null);
        createDepartment("DEV", "HQ");
        createDepartment("API", "DEV");

        assertThatThrownBy(() -> departmentService.move(SYSTEM_ADMIN,
                new MoveDepartmentCommand("ACME", "HQ", "API", hq.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void normalizes_code_and_rejects_case_insensitive_duplicates_within_company() {
        DepartmentView created = departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand(" acme ", " dev ", " Development ", null));

        assertThat(created.code()).isEqualTo("DEV");
        assertThat(created.name()).isEqualTo("Development");
        assertThatThrownBy(() -> departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand("ACME", "DeV", "Duplicate", null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.DUPLICATE_CODE));
    }

    @Test
    void requires_parent_to_belong_to_the_same_company() {
        companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand("BETA", "Beta", "beta.example"));
        departmentService.create(SYSTEM_ADMIN, new CreateDepartmentCommand("BETA", "OTHER", "Other", null));

        assertThatThrownBy(() -> departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand("ACME", "DEV", "Development", "OTHER")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void rejects_self_parent_and_stale_move() {
        DepartmentView hq = createDepartment("HQ", null);

        assertThatThrownBy(() -> departmentService.move(SYSTEM_ADMIN,
                new MoveDepartmentCommand("ACME", "HQ", "HQ", hq.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> departmentService.move(SYSTEM_ADMIN,
                new MoveDepartmentCommand("ACME", "HQ", null, hq.version() + 1)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT));
    }

    @Test
    void rejects_deactivation_while_an_active_child_exists() {
        DepartmentView hq = createDepartment("HQ", null);
        createDepartment("DEV", "HQ");

        assertThatThrownBy(() -> departmentService.changeStatus(SYSTEM_ADMIN,
                new ChangeDepartmentStatusCommand(
                        "ACME", "HQ", DepartmentStatus.INACTIVE, hq.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void rejects_reactivation_below_an_inactive_parent() {
        DepartmentView hq = createDepartment("HQ", null);
        DepartmentView dev = createDepartment("DEV", "HQ");
        DepartmentView inactiveDev = departmentService.changeStatus(SYSTEM_ADMIN,
                new ChangeDepartmentStatusCommand(
                        "ACME", "DEV", DepartmentStatus.INACTIVE, dev.version()));
        departmentService.changeStatus(SYSTEM_ADMIN, new ChangeDepartmentStatusCommand(
                "ACME", "HQ", DepartmentStatus.INACTIVE, hq.version()));

        assertThatThrownBy(() -> departmentService.changeStatus(SYSTEM_ADMIN,
                new ChangeDepartmentStatusCommand(
                        "ACME", "DEV", DepartmentStatus.ACTIVE, inactiveDev.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void returns_the_department_hierarchy_with_parent_references() {
        DepartmentView hq = createDepartment("HQ", null);
        DepartmentView dev = createDepartment("DEV", "HQ");

        assertThat(departmentService.tree(SYSTEM_ADMIN, "ACME"))
                .extracting(DepartmentView::code)
                .containsExactly("DEV", "HQ");
        assertThat(dev.parentDepartmentId()).isEqualTo(hq.id());
    }

    private DepartmentView createDepartment(String code, String parentCode) {
        return departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand("ACME", code, code, parentCode));
    }
}
