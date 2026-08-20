package com.sweet.authstudy.hr.user.application;

import static com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import static com.sweet.authstudy.hr.user.application.UserViews.CreatedUserView;
import static com.sweet.authstudy.hr.user.application.UserViews.UserView;

import java.time.Clock;
import java.util.Locale;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.application.PasswordGenerator;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final CompanyRepository companyRepository;
    private final PositionRepository positionRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final AccountRepository accountRepository;
    private final PasswordGenerator passwordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(
            CompanyRepository companyRepository,
            PositionRepository positionRepository,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            AccountRepository accountRepository,
            PasswordGenerator passwordGenerator,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.companyRepository = companyRepository;
        this.positionRepository = positionRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.accountRepository = accountRepository;
        this.passwordGenerator = passwordGenerator;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public CreatedUserView create(CreateUserCommand command) {
        if (command == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "User data is required.");
        }
        Company company = findActiveLockedCompany(command.companyCode());
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
        accountRepository.save(Account.createCompanyAccount(
                company.id(),
                savedUser.id(),
                loginEmail,
                passwordEncoder.encode(temporaryPassword),
                clock.instant()));
        return new CreatedUserView(UserViews.UserView.from(savedUser, loginEmail), temporaryPassword);
    }

    @Transactional
    public UserView changeStatus(
            String companyCode, String userCode, UserStatus status, long version) {
        if (status == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "User status is required.");
        }
        Company company = findLockedCompany(companyCode);
        HrUser user = userRepository.findByCompanyIdAndCode(company.id(), normalizeCode(userCode))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User was not found."));
        if (user.version() != version) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "User version does not match.");
        }
        if (status == UserStatus.ACTIVE) {
            requireActivationReady(company, user);
        }
        user.changeStatus(status, clock.instant());
        HrUser saved = userRepository.save(user);
        Account account = accountRepository.findByUserId(saved.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
        return UserView.from(saved, account.loginEmail());
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

    private Company findActiveLockedCompany(String code) {
        Company company = findLockedCompany(code);
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Company is inactive.");
        }
        return company;
    }

    private Company findLockedCompany(String code) {
        Company identified = companyRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
        return companyRepository.findLockedById(identified.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
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
        return normalizeRequired(value).toUpperCase(Locale.ROOT);
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
