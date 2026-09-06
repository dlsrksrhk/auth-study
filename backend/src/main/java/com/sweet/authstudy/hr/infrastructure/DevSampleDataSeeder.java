package com.sweet.authstudy.hr.infrastructure;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "app.dev.sample-data", name = "enabled", havingValue = "true")
@Order(1)
public class DevSampleDataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSampleDataSeeder.class);
    private static final String COMPANY = "DEMO";
    private final CompanyRepository companies;
    private final CompanyService companyService;
    private final DepartmentService departmentService;
    private final UserService userService;
    private final MembershipService membershipService;
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AppSecurityProperties securityProperties;
    private final Clock clock;

    public DevSampleDataSeeder(CompanyRepository companies, CompanyService companyService,
            DepartmentService departmentService, UserService userService, MembershipService membershipService,
            AccountRepository accounts, PasswordEncoder passwords, AppSecurityProperties securityProperties,
            Clock clock) {
        this.companies = companies;
        this.companyService = companyService;
        this.departmentService = departmentService;
        this.userService = userService;
        this.membershipService = membershipService;
        this.accounts = accounts;
        this.passwords = passwords;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (companies.findByCode(COMPANY).isPresent()) {
            log.info("Skipping development sample data: company DEMO already exists.");
            return;
        }
        var admin = accounts.findSystemByEmail(
                securityProperties.bootstrapAdmin().email().trim().toLowerCase(Locale.ROOT)).orElseThrow();
        var actor = new AuthenticatedAccount(admin.id(), null, null, admin.roles(), false);
        companyService.create(actor, new CreateCompanyCommand(COMPANY, "데모 주식회사", "demo.example"));
        departmentService.create(actor, new CreateDepartmentCommand(COMPANY, "HQ", "본사", null));
        String[] departmentCodes = {"DEV", "HR", "SALES", "FINANCE"};
        String[] departmentNames = {"개발부", "인사부", "영업부", "재무부"};
        for (int index = 0; index < departmentCodes.length; index++) {
            departmentService.create(actor, new CreateDepartmentCommand(
                    COMPANY, departmentCodes[index], departmentNames[index], "HQ"));
        }
        String[] positionCodes = {"GENERAL_MANAGER", "DEPUTY_GENERAL_MANAGER", "MANAGER", "ASSISTANT_MANAGER", "EMPLOYEE"};
        String[] names = {"김민준", "이서연", "박지호", "최서윤", "정도윤", "강하은", "조예준", "윤지우", "장시우", "임수아",
                "한주원", "오지유", "서도현", "신채원", "권건우", "황다은", "안우진", "송예린", "류선우", "홍소율"};
        String passwordHash = passwords.encode("Demo1234!");
        var startedAt = clock.instant();
        for (int index = 0; index < names.length; index++) {
            String number = String.format(Locale.ROOT, "%03d", index + 1);
            String code = "U" + number;
            var created = userService.create(actor, new CreateUserCommand(
                    COMPANY, code, "DEMO-" + number, names[index], "user" + number + "@demo.example",
                    "010-0000-" + String.format(Locale.ROOT, "%04d", index + 1),
                    LocalDate.of(2024, 1, 1).plusMonths(index), "서울 본사", null,
                    positionCodes[index % positionCodes.length]));
            membershipService.assign(actor, new AssignMembershipCommand(
                    COMPANY, code, departmentCodes[index / 5],
                    index % 5 == 0 ? DepartmentRole.HEAD : DepartmentRole.MEMBER, true, startedAt));
            userService.changeStatus(actor, COMPANY, code, UserStatus.ACTIVE, created.user().version());
            // Only newly created local sample accounts bypass the initial password change.
            var account = accounts.findByUserId(created.user().id()).orElseThrow();
            account.changePassword(passwordHash, clock.instant());
            accounts.save(account);
        }
        log.info("Prepared development sample data: DEMO, 5 departments, 20 users (user001@demo.example through user020@demo.example).");
    }
}
