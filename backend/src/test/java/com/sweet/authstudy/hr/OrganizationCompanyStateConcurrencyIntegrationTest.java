package com.sweet.authstudy.hr;

import static com.sweet.authstudy.hr.membership.domain.DepartmentRole.MEMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.application.UserViews.CreatedUserView;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class OrganizationCompanyStateConcurrencyIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private CompanyRepository companyRepository;

    @Test
    void rejects_department_create_resuming_after_company_deactivation() throws Exception {
        TestCompany company = createCompany();

        Throwable result = runAfterCompanyDeactivation(company, () -> departmentService.create(
                new CreateDepartmentCommand(company.code(), "LATE", "Late", null)));

        assertInactiveCompanyFailure(result);
        assertThat(departmentService.tree(company.code())).isEmpty();
    }

    @Test
    void rejects_membership_assign_resuming_after_company_deactivation() throws Exception {
        TestCompany company = createCompany();
        departmentService.create(new CreateDepartmentCommand(company.code(), "DEV", "Development", null));
        createUser(company, "U001", "E-1001");

        Throwable result = runAfterCompanyDeactivation(company, () -> membershipService.assign(
                new AssignMembershipCommand(
                        company.code(), "U001", "DEV", MEMBER, true,
                        Instant.parse("2026-08-20T00:00:00Z"))));

        assertInactiveCompanyFailure(result);
        assertThat(membershipService.listByUser(company.code(), "U001")).isEmpty();
    }

    @Test
    void rejects_user_activation_resuming_after_company_deactivation() throws Exception {
        TestCompany company = createCompany();
        departmentService.create(new CreateDepartmentCommand(company.code(), "DEV", "Development", null));
        CreatedUserView pending = createUser(company, "U001", "E-1001");
        membershipService.assign(new AssignMembershipCommand(
                company.code(), "U001", "DEV", MEMBER, true,
                Instant.parse("2026-08-20T00:00:00Z")));

        Throwable result = runAfterCompanyDeactivation(company, () -> userService.changeStatus(
                company.code(), "U001", UserStatus.ACTIVE, pending.user().version()));

        assertInactiveCompanyFailure(result);
        assertThat(userRepository.findByCompanyIdAndCode(company.id(), "U001").orElseThrow().status())
                .isEqualTo(UserStatus.PENDING);
    }

    private Throwable runAfterCompanyDeactivation(TestCompany company, ThrowingAction action) throws Exception {
        CountDownLatch staleCompanyRead = new CountDownLatch(1);
        CountDownLatch resumeMutation = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("late-organization-mutation")) {
                staleCompanyRead.countDown();
                if (!resumeMutation.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Organization mutation was not resumed.");
                }
            }
            return result;
        }).when(companyRepository).findByCode(eq(company.code()));

        try (var executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "late-organization-mutation"))) {
            var mutation = executor.submit(() -> {
                try {
                    action.run();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(staleCompanyRead.await(5, TimeUnit.SECONDS)).isTrue();
            CompanyView latest = companyService.find(company.code());
            companyService.update(company.code(), new UpdateCompanyCommand(
                    latest.name(), CompanyStatus.INACTIVE, latest.version()));
            resumeMutation.countDown();
            return mutation.get(10, TimeUnit.SECONDS);
        } finally {
            resumeMutation.countDown();
        }
    }

    private TestCompany createCompany() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String code = "CO" + suffix;
        CompanyView company = companyService.create(new CreateCompanyCommand(
                code, code, code.toLowerCase() + ".example"));
        return new TestCompany(company.id(), company.code(), company.emailDomain());
    }

    private CreatedUserView createUser(
            TestCompany company, String code, String employeeNumber) {
        return userService.create(new CreateUserCommand(
                company.code(), code, employeeNumber, code, code.toLowerCase() + "@" + company.emailDomain(),
                "010-0000-0000", LocalDate.parse("2026-08-20"), "Seoul", null, "EMPLOYEE"));
    }

    private void assertInactiveCompanyFailure(Throwable failure) {
        assertThat(failure)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    private record TestCompany(long id, String code, String emailDomain) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
