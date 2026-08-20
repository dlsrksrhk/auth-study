package com.sweet.authstudy.hr.membership;

import static com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import static com.sweet.authstudy.hr.department.application.DepartmentCommands.ChangeDepartmentStatusCommand;
import static com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import static com.sweet.authstudy.hr.membership.application.MembershipCommands.UpdateMembershipCommand;
import static com.sweet.authstudy.hr.membership.domain.DepartmentRole.HEAD;
import static com.sweet.authstudy.hr.membership.domain.DepartmentRole.MEMBER;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.department.application.DepartmentView;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.membership.application.MembershipView;
import com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.position.application.PositionView;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.application.UserViews.CreatedUserView;
import com.sweet.authstudy.hr.user.domain.UserStatus;
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
class MembershipIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private UserService userService;

    @Autowired
    private PositionService positionService;

    @Autowired
    private MembershipService membershipService;

    @BeforeEach
    void setUpOrganization() {
        companyService.create(new CreateCompanyCommand("ACME", "Acme", "acme.example"));
        departmentService.create(new CreateDepartmentCommand("ACME", "DEV", "Development", null));
        departmentService.create(new CreateDepartmentCommand("ACME", "TF", "Task Force", null));
        departmentService.create(new CreateDepartmentCommand("ACME", "SALES", "Sales", null));
        createUser("U001", "E-1001");
        createUser("U002", "E-1002");
        createUser("U003", "E-1003");
    }

    @Test
    void allows_multiple_memberships_but_only_one_active_primary_and_head() {
        membershipService.assign(command("U001", "DEV", MEMBER, true));
        membershipService.assign(command("U001", "TF", MEMBER, false));

        assertThatThrownBy(() -> membershipService.assign(command("U001", "SALES", MEMBER, true)))
                .isInstanceOf(ApiException.class);

        membershipService.assign(command("U002", "DEV", HEAD, false));
        assertThatThrownBy(() -> membershipService.assign(command("U003", "DEV", HEAD, false)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejects_duplicate_active_membership_for_the_same_user_and_department() {
        membershipService.assign(command("U001", "DEV", MEMBER, false));

        assertThatThrownBy(() -> membershipService.assign(command("U001", "DEV", MEMBER, false)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void updates_role_and_primary_while_preserving_uniqueness_rules() {
        MembershipView dev = membershipService.assign(command("U001", "DEV", MEMBER, false));
        MembershipView tf = membershipService.assign(command("U001", "TF", MEMBER, true));

        assertThatThrownBy(() -> membershipService.update(new UpdateMembershipCommand(
                "ACME", "U001", dev.id(), HEAD, true, dev.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        MembershipView updated = membershipService.update(new UpdateMembershipCommand(
                "ACME", "U001", tf.id(), HEAD, true, tf.version()));
        assertThat(updated.role()).isEqualTo(HEAD);
        assertThat(updated.primary()).isTrue();
    }

    @Test
    void ends_instead_of_deleting_and_allows_a_later_membership_in_the_same_department() {
        MembershipView assigned = membershipService.assign(command("U001", "DEV", MEMBER, true));

        MembershipView ended = membershipService.end(
                "ACME", "U001", assigned.id(), assigned.version());
        MembershipView reassigned = membershipService.assign(command("U001", "DEV", MEMBER, true));

        assertThat(ended.endedAt()).isNotNull();
        assertThat(reassigned.id()).isNotEqualTo(assigned.id());
        assertThat(membershipService.listByUser("ACME", "U001"))
                .hasSize(2)
                .anySatisfy(view -> assertThat(view.endedAt()).isEqualTo(ended.endedAt()));
    }

    @Test
    void rejects_membership_mutations_for_inactive_department_or_wrong_company() {
        DepartmentView sales = departmentService.tree("ACME").stream()
                .filter(view -> view.code().equals("SALES"))
                .findFirst()
                .orElseThrow();
        departmentService.changeStatus(new ChangeDepartmentStatusCommand(
                "ACME", "SALES", DepartmentStatus.INACTIVE, sales.version()));

        assertThatThrownBy(() -> membershipService.assign(command("U001", "SALES", MEMBER, false)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        companyService.create(new CreateCompanyCommand("BETA", "Beta", "beta.example"));
        departmentService.create(new CreateDepartmentCommand("BETA", "OPS", "Operations", null));
        assertThatThrownBy(() -> membershipService.assign(new AssignMembershipCommand(
                "BETA", "U001", "OPS", MEMBER, false, Instant.parse("2026-08-20T00:00:00Z"))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void rejects_department_deactivation_while_an_active_membership_exists() {
        MembershipView membership = membershipService.assign(command("U001", "DEV", MEMBER, false));
        DepartmentView dev = departmentService.tree("ACME").stream()
                .filter(view -> view.code().equals("DEV"))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> departmentService.changeStatus(new ChangeDepartmentStatusCommand(
                "ACME", "DEV", DepartmentStatus.INACTIVE, dev.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        membershipService.end("ACME", "U001", membership.id(), membership.version());
        assertThat(departmentService.changeStatus(new ChangeDepartmentStatusCommand(
                "ACME", "DEV", DepartmentStatus.INACTIVE, dev.version())).status())
                .isEqualTo(DepartmentStatus.INACTIVE);
    }

    @Test
    void requires_an_active_position_and_primary_membership_before_user_activation() {
        CreatedUserView pending = createUser("U004", "E-1004");

        assertThatThrownBy(() -> userService.changeStatus(
                "ACME", "U004", UserStatus.ACTIVE, pending.user().version()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        membershipService.assign(command("U004", "DEV", MEMBER, true));
        assertThat(userService.changeStatus(
                "ACME", "U004", UserStatus.ACTIVE, pending.user().version()).status())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void rejects_user_activation_when_its_position_is_inactive() {
        CreatedUserView pending = createUser("U004", "E-1004");
        membershipService.assign(command("U004", "DEV", MEMBER, true));
        PositionView employee = positionService.list("ACME").stream()
                .filter(view -> view.code().equals("EMPLOYEE"))
                .findFirst()
                .orElseThrow();
        positionService.update("ACME", "EMPLOYEE", new UpdatePositionCommand(
                employee.name(), employee.level(), employee.displayOrder(), false, employee.version()));

        assertThatThrownBy(() -> userService.changeStatus(
                "ACME", "U004", UserStatus.ACTIVE, pending.user().version()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void keeps_exactly_one_primary_membership_for_an_active_user() {
        MembershipView dev = membershipService.assign(command("U001", "DEV", MEMBER, true));
        MembershipView tf = membershipService.assign(command("U001", "TF", MEMBER, false));
        var active = userService.changeStatus("ACME", "U001", UserStatus.ACTIVE, 0);

        assertThatThrownBy(() -> membershipService.update(new UpdateMembershipCommand(
                "ACME", "U001", dev.id(), MEMBER, false, dev.version())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> membershipService.end(
                "ACME", "U001", dev.id(), dev.version()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        MembershipView promoted = membershipService.update(new UpdateMembershipCommand(
                "ACME", "U001", tf.id(), MEMBER, true, tf.version()));
        assertThat(promoted.primary()).isTrue();
        assertThat(membershipService.listByUser("ACME", "U001"))
                .filteredOn(view -> view.endedAt() == null && view.primary())
                .extracting(MembershipView::id)
                .containsExactly(promoted.id());
        assertThat(active.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void allows_ending_memberships_after_the_user_resigns() {
        MembershipView membership = membershipService.assign(command("U001", "DEV", MEMBER, true));
        userService.changeStatus("ACME", "U001", UserStatus.RESIGNED, 0);

        MembershipView ended = membershipService.end(
                "ACME", "U001", membership.id(), membership.version());

        assertThat(ended.endedAt()).isNotNull();
    }

    private AssignMembershipCommand command(
            String userCode, String departmentCode, com.sweet.authstudy.hr.membership.domain.DepartmentRole role,
            boolean primary) {
        return new AssignMembershipCommand(
                "ACME", userCode, departmentCode, role, primary, Instant.parse("2026-08-20T00:00:00Z"));
    }

    private CreatedUserView createUser(String code, String employeeNumber) {
        return userService.create(new CreateUserCommand(
                "ACME", code, employeeNumber, code, code.toLowerCase() + "@acme.example",
                "010-0000-0000", LocalDate.parse("2026-08-20"), "Seoul", null, "EMPLOYEE"));
    }
}
