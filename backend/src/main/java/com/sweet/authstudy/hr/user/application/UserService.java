package com.sweet.authstudy.hr.user.application;

import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.audit.application.AuditFailurePlan;
import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.audit.application.AuditedTransactionExecutor;
import com.sweet.authstudy.authorization.AdministrativeTargetGuard;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.application.AccountService;
import com.sweet.authstudy.identity.application.OAuthGrantRevocationPort;
import com.sweet.authstudy.identity.application.PasswordGenerator;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import static com.sweet.authstudy.hr.user.application.UserCommands.UpdateUserCommand;
import static com.sweet.authstudy.hr.user.application.UserViews.*;

@Service
public class UserService {

    private final CompanyRepository companyRepository;
    private final PositionRepository positionRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final AccountRepository accountRepository;
    private final PasswordGenerator passwordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;
    private final OAuthGrantRevocationPort oauthGrants;
    private final TenantGuard tenantGuard;
    private final AdministrativeTargetGuard targetGuard;
    private final AuditService auditService;
    private final AuditedTransactionExecutor auditedTransactions;
    private final Clock clock;

    public UserService(
            CompanyRepository companyRepository,
            PositionRepository positionRepository,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            AccountRepository accountRepository,
            PasswordGenerator passwordGenerator,
            PasswordEncoder passwordEncoder,
            AccountService accountService,
            OAuthGrantRevocationPort oauthGrants,
            TenantGuard tenantGuard,
            AdministrativeTargetGuard targetGuard,
            AuditService auditService,
            AuditedTransactionExecutor auditedTransactions,
            Clock clock) {
        this.companyRepository = companyRepository;
        this.positionRepository = positionRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.accountRepository = accountRepository;
        this.passwordGenerator = passwordGenerator;
        this.passwordEncoder = passwordEncoder;
        this.accountService = accountService;
        this.oauthGrants = oauthGrants;
        this.tenantGuard = tenantGuard;
        this.targetGuard = targetGuard;
        this.auditService = auditService;
        this.auditedTransactions = auditedTransactions;
        this.clock = clock;
    }

    @Transactional
    public CreatedUserView create(AuthenticatedAccount actor, CreateUserCommand command) {
        if (command == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "User data is required.");
        }
        Company company = findLockedCompany(command.companyCode());
        tenantGuard.requireCompanyAccess(actor, company.id());
        requireActive(company);
        String code = normalizeCode(command.code());
        String employeeNumber = normalizeRequired(command.employeeNumber());
        String loginEmail = normalizeEmail(command.loginEmail());
        validateEmailDomain(loginEmail, company.emailDomain());
        rejectDuplicates(company.id(), code, employeeNumber, loginEmail);

        Position position = positionRepository
                .findByCompanyIdAndCode(company.id(), normalizeCode(command.positionCode()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Position was not found."));
        if (position.companyId() != company.id()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Position belongs to another company.");
        }
        if (!position.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Position is inactive.");
        }

        HrUser savedUser = userRepository.save(HrUser.create(
                company.id(),
                code,
                employeeNumber,
                normalizeRequired(command.name()),
                normalizeRequired(command.phone()),
                requireHiredAt(command),
                normalizeRequired(command.workplace()),
                normalizeOptional(command.profileImageUrl()),
                position.id(),
                clock.instant()));

        String temporaryPassword = passwordGenerator.generateTemporaryPassword();
        Account savedAccount = accountRepository.save(Account.createCompanyAccount(
                company.id(),
                savedUser.id(),
                loginEmail,
                passwordEncoder.encode(temporaryPassword),
                clock.instant()));
        auditService.record(actor, AuditActions.USER_CREATE, "USER", savedUser.id(), company.id(),
                Map.of("code", savedUser.code(), "status", savedUser.status().name(),
                        "positionCode", position.code()));
        return new CreatedUserView(UserViews.UserView.from(savedUser, savedAccount), temporaryPassword);
    }

    public UserView changeStatus(
            AuthenticatedAccount actor, String companyCode, String userCode, UserStatus status, long version) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            if (status == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "User status is required.");
            }
            if (status == UserStatus.LOCKED || status == UserStatus.RESIGNED) {
                Company identifiedCompany = findCompany(companyCode);
                tenantGuard.requireCompanyAccess(actor, identifiedCompany.id());
                HrUser identifiedUser = findUser(identifiedCompany.id(), userCode);
                Account identifiedAccount = accountRepository.findByUserId(identifiedUser.id())
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
                oauthGrants.revokeAccount(identifiedAccount.id(), clock.instant());
            }
            Company company = findLockedCompany(companyCode);
            tenantGuard.requireCompanyAccess(actor, company.id());
            HrUser user = userRepository.findByCompanyIdAndCode(company.id(), normalizeCode(userCode))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User was not found."));
            Account targetAccount = targetGuard.requireMayMutateUser(actor, user.id());
            UserStatus previousStatus = user.status();
            failurePlan.identify(actor, AuditActions.USER_STATUS_CHANGE, "USER", user.id(), company.id(),
                    Map.of("code", user.code(), "previousStatus", previousStatus.name()));
            if (user.version() != version) {
                throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "User version does not match.");
            }
            if (status == UserStatus.ACTIVE) {
                requireActivationReady(company, user);
            }
            user.changeStatus(status, clock.instant());
            HrUser saved = userRepository.save(user);
            if (status == UserStatus.LOCKED || status == UserStatus.RESIGNED) {
                accountService.revokeAllRefreshTokens(targetAccount.id());
            }
            auditService.record(actor, AuditActions.USER_STATUS_CHANGE, "USER", saved.id(), company.id(),
                    Map.of("code", saved.code(), "status", saved.status().name(),
                            "previousStatus", previousStatus.name()));
            return UserView.from(saved, targetAccount);
        });
    }

    public UserView update(
            AuthenticatedAccount actor, String companyCode, String userCode, UpdateUserCommand command) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            if (command == null || command.hiredAt() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "User data is required.");
            }
            Company company = findLockedCompany(companyCode);
            tenantGuard.requireCompanyAccess(actor, company.id());
            requireActive(company);
            HrUser user = findUser(company.id(), userCode);
            Account targetAccount = targetGuard.requireMayMutateUser(actor, user.id());
            failurePlan.identify(actor, AuditActions.USER_UPDATE, "USER", user.id(), company.id(),
                    Map.of("code", user.code()));
            if (user.version() != command.version()) {
                throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "User version does not match.");
            }
            Position position = positionRepository
                    .findByCompanyIdAndCode(company.id(), normalizeCode(command.positionCode()))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Position was not found."));
            if (!position.active()) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Position is inactive.");
            }
            user.updateProfile(normalizeRequired(command.name()), normalizeRequired(command.phone()),
                    command.hiredAt(), normalizeRequired(command.workplace()),
                    normalizeOptional(command.profileImageUrl()), position.id(), clock.instant());
            HrUser saved = userRepository.save(user);
            auditService.record(actor, AuditActions.USER_UPDATE, "USER", saved.id(), company.id(),
                    Map.of("code", saved.code(), "positionCode", position.code()));
            return UserView.from(saved, targetAccount);
        });
    }

    @Transactional(readOnly = true)
    public UserView find(AuthenticatedAccount actor, String companyCode, String userCode) {
        Company company = findCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        HrUser user = findUser(company.id(), userCode);
        Account account = accountRepository.findByUserId(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
        return UserView.from(user, account);
    }

    @Transactional(readOnly = true)
    public UserPage list(AuthenticatedAccount actor, String companyCode, String search,
                         UserStatus status, int page, int size, String sort) {
        Company company = findCompany(companyCode);
        tenantGuard.requireCompanyAccess(actor, company.id());
        var result = userRepository.search(company.id(), search, status, page, size, sort);
        var accounts = accountRepository.findAllByUserIds(
                        result.content().stream().map(HrUser::id).toList()).stream()
                .collect(Collectors.toMap(Account::userId, Function.identity()));
        var content = result.content().stream().map(user -> UserView.from(user,
                java.util.Optional.ofNullable(accounts.get(user.id()))
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.RESOURCE_NOT_FOUND, "User account was not found.")))).toList();
        return new UserPage(content, result.totalElements(), result.totalPages());
    }

    public String resetTemporaryPassword(
            AuthenticatedAccount actor, String companyCode, String userCode) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            Company identifiedCompany = findCompany(companyCode);
            tenantGuard.requireCompanyAccess(actor, identifiedCompany.id());
            requireActive(identifiedCompany);
            HrUser identifiedUser = findUser(identifiedCompany.id(), userCode);
            Account identifiedAccount = accountRepository.findByUserId(identifiedUser.id())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
            oauthGrants.revokeAccount(identifiedAccount.id(), clock.instant());
            Company company = findLockedCompany(companyCode);
            tenantGuard.requireCompanyAccess(actor, company.id());
            requireActive(company);
            HrUser user = findUser(company.id(), userCode);
            Account account = targetGuard.requireMayMutateUser(actor, user.id());
            failurePlan.identify(actor, AuditActions.USER_TEMPORARY_PASSWORD_RESET, "USER",
                    user.id(), company.id(), Map.of("code", user.code()));
            String temporaryPassword = accountService.resetTemporaryPassword(account.id());
            auditService.record(actor, AuditActions.USER_TEMPORARY_PASSWORD_RESET, "USER",
                    user.id(), company.id(), Map.of("code", user.code()));
            return temporaryPassword;
        });
    }

    public void assignCompanyAdmin(
            AuthenticatedAccount actor, String companyCode, String userCode) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        auditedTransactions.execute(failurePlan, () -> {
            tenantGuard.requireSystemAdmin(actor);
            Company company = findCompany(companyCode);
            HrUser user = findUser(company.id(), userCode);
            Account account = accountRepository.findByUserId(user.id())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
            failurePlan.identify(actor, AuditActions.COMPANY_ADMIN_GRANT, "USER",
                    user.id(), company.id(), Map.of("code", user.code(), "role", "COMPANY_ADMIN"));
            accountService.assignCompanyAdmin(actor, account.id());
            auditService.record(actor, AuditActions.COMPANY_ADMIN_GRANT, "USER", user.id(), company.id(),
                    Map.of("code", user.code(), "role", "COMPANY_ADMIN"));
            return null;
        });
    }

    public void revokeCompanyAdmin(
            AuthenticatedAccount actor, String companyCode, String userCode) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        auditedTransactions.execute(failurePlan, () -> {
            tenantGuard.requireSystemAdmin(actor);
            Company company = findCompany(companyCode);
            HrUser user = findUser(company.id(), userCode);
            Account account = accountRepository.findByUserId(user.id())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
            failurePlan.identify(actor, AuditActions.COMPANY_ADMIN_REVOKE, "USER",
                    user.id(), company.id(), Map.of("code", user.code(), "role", "COMPANY_ADMIN"));
            accountService.revokeCompanyAdmin(actor, account.id());
            auditService.record(actor, AuditActions.COMPANY_ADMIN_REVOKE, "USER", user.id(), company.id(),
                    Map.of("code", user.code(), "role", "COMPANY_ADMIN"));
            return null;
        });
    }

    private void requireActivationReady(Company company, HrUser user) {
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Company is inactive.");
        }
        Position position = positionRepository.findAllByCompanyId(company.id()).stream()
                .filter(candidate -> candidate.id() == user.positionId())
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE, "Position belongs to another company."));
        if (!position.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Position is inactive.");
        }
        if (!membershipRepository.existsActivePrimaryByUserId(user.id())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Active primary membership is required.");
        }
    }

    private void requireActive(Company company) {
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Company is inactive.");
        }
    }

    private Company findLockedCompany(String code) {
        Company identified = companyRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
        return companyRepository.findLockedById(identified.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
    }

    private Company findCompany(String code) {
        return companyRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
    }

    private HrUser findUser(long companyId, String code) {
        return userRepository.findByCompanyIdAndCode(companyId, normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User was not found."));
    }

    private void rejectDuplicates(long companyId, String code, String employeeNumber, String loginEmail) {
        if (userRepository.findByCompanyIdAndCode(companyId, code).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "User code already exists.");
        }
        if (userRepository.findByCompanyIdAndEmployeeNumber(companyId, employeeNumber).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_EMPLOYEE_NUMBER, "Employee number already exists.");
        }
        if (accountRepository.findCompanyAccount(companyId, loginEmail).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "Login email already exists.");
        }
    }

    private void validateEmailDomain(String email, String companyDomain) {
        int separator = email.lastIndexOf('@');
        if (separator <= 0 || separator == email.length() - 1
                || !email.substring(separator + 1).equals(companyDomain)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Login email must use the company domain.");
        }
    }

    private java.time.LocalDate requireHiredAt(CreateUserCommand command) {
        if (command.hiredAt() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hire date is required.");
        }
        return command.hiredAt();
    }

    private String normalizeCode(String value) {
        return BusinessCode.normalize(value);
    }

    private String normalizeEmail(String value) {
        return normalizeRequired(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A required value is missing.");
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
