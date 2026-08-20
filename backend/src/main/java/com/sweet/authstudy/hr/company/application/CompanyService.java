package com.sweet.authstudy.hr.company.application;

import static com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import static com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.audit.application.AuditFailurePlan;
import com.sweet.authstudy.audit.application.AuditedTransactionExecutor;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.identity.application.AccountService;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private static final String RESERVED_DOMAIN = "auth-study.local";

    private final CompanyRepository companyRepository;
    private final PositionService positionService;
    private final TenantGuard tenantGuard;
    private final AccountService accountService;
    private final AuditService auditService;
    private final AuditedTransactionExecutor auditedTransactions;
    private final Clock clock;

    public CompanyService(
            CompanyRepository companyRepository, PositionService positionService,
            TenantGuard tenantGuard, AccountService accountService,
            AuditService auditService, AuditedTransactionExecutor auditedTransactions, Clock clock) {
        this.companyRepository = companyRepository;
        this.positionService = positionService;
        this.tenantGuard = tenantGuard;
        this.accountService = accountService;
        this.auditService = auditService;
        this.auditedTransactions = auditedTransactions;
        this.clock = clock;
    }

    @Transactional
    public CompanyView create(AuthenticatedAccount actor, CreateCompanyCommand command) {
        tenantGuard.requireSystemAdmin(actor);
        String code = normalizeCode(command.code());
        String name = normalizeRequiredValue(command.name());
        String domain = normalizeDomain(command.emailDomain());
        rejectReservedDomain(domain);
        rejectDuplicateCompany(code, domain);

        Company saved = companyRepository.save(Company.create(code, name, domain, clock.instant()));
        positionService.createDefaults(actor, saved.id());
        auditService.record(actor, AuditActions.COMPANY_CREATE, "COMPANY", saved.id(), saved.id(),
                Map.of("code", saved.code(), "status", saved.status().name()));
        return CompanyView.from(saved);
    }

    public CompanyView update(AuthenticatedAccount actor, String code, UpdateCompanyCommand command) {
        AuditFailurePlan failurePlan = new AuditFailurePlan();
        return auditedTransactions.execute(failurePlan, () -> {
            tenantGuard.requireSystemAdmin(actor);
            Company identified = findCompany(normalizeCode(code));
            Company company = companyRepository.findLockedById(identified.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
            CompanyStatus previousStatus = company.status();
            String action = previousStatus == command.status()
                    ? AuditActions.COMPANY_UPDATE : AuditActions.COMPANY_STATUS_CHANGE;
            failurePlan.identify(actor, action, "COMPANY", company.id(), company.id(),
                    Map.of("code", company.code(), "previousStatus", previousStatus.name()));
            if (company.version() != command.version()) {
                throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Company version does not match.");
            }
            company.update(normalizeRequiredValue(command.name()), requireStatus(command.status()), clock.instant());
            Company saved = companyRepository.save(company);
            if (previousStatus != CompanyStatus.INACTIVE && saved.status() == CompanyStatus.INACTIVE) {
                accountService.revokeAllRefreshTokensForCompany(saved.id());
            }
            auditService.record(actor, action, "COMPANY", saved.id(), saved.id(),
                    Map.of("code", saved.code(), "status", saved.status().name(),
                            "previousStatus", previousStatus.name()));
            return CompanyView.from(saved);
        });
    }

    @Transactional(readOnly = true)
    public CompanyView find(AuthenticatedAccount actor, String code) {
        tenantGuard.requireSystemAdmin(actor);
        return CompanyView.from(findCompany(normalizeCode(code)));
    }

    @Transactional(readOnly = true)
    public PageResult<CompanyView> list(AuthenticatedAccount actor, String search,
            CompanyStatus status, int page, int size, String sort) {
        tenantGuard.requireSystemAdmin(actor);
        var result = companyRepository.search(search, status, page, size, sort);
        return new PageResult<>(result.content().stream().map(CompanyView::from).toList(),
                result.totalElements(), result.totalPages());
    }

    private void rejectDuplicateCompany(String code, String domain) {
        if (companyRepository.findByCode(code).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "Company code already exists.");
        }
        if (companyRepository.findByEmailDomain(domain).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "Company email domain already exists.");
        }
    }

    private Company findCompany(String code) {
        return companyRepository.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
    }

    private String normalizeCode(String value) {
        return BusinessCode.normalize(value);
    }

    private String normalizeDomain(String value) {
        return normalizeRequiredValue(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeRequiredValue(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A required value is missing.");
        }
        return value.trim();
    }

    private CompanyStatus requireStatus(CompanyStatus status) {
        if (status == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Company status is required.");
        }
        return status;
    }

    private void rejectReservedDomain(String domain) {
        if (RESERVED_DOMAIN.equals(domain)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The domain is reserved.");
        }
    }
}
