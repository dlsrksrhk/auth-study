package com.sweet.authstudy.hr.company.application;

import static com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import static com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;

import java.time.Clock;
import java.util.Locale;
import java.util.List;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.TenantGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private static final String RESERVED_DOMAIN = "auth-study.local";

    private final CompanyRepository companyRepository;
    private final PositionService positionService;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public CompanyService(
            CompanyRepository companyRepository, PositionService positionService,
            TenantGuard tenantGuard, Clock clock) {
        this.companyRepository = companyRepository;
        this.positionService = positionService;
        this.tenantGuard = tenantGuard;
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
        return CompanyView.from(saved);
    }

    @Transactional
    public CompanyView update(AuthenticatedAccount actor, String code, UpdateCompanyCommand command) {
        tenantGuard.requireSystemAdmin(actor);
        Company company = findCompany(normalizeCode(code));
        if (company.version() != command.version()) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Company version does not match.");
        }
        company.update(normalizeRequiredValue(command.name()), requireStatus(command.status()), clock.instant());
        return CompanyView.from(companyRepository.save(company));
    }

    @Transactional(readOnly = true)
    public CompanyView find(AuthenticatedAccount actor, String code) {
        tenantGuard.requireSystemAdmin(actor);
        return CompanyView.from(findCompany(normalizeCode(code)));
    }

    @Transactional(readOnly = true)
    public List<CompanyView> list(AuthenticatedAccount actor) {
        tenantGuard.requireSystemAdmin(actor);
        return companyRepository.findAll().stream().map(CompanyView::from).toList();
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
        return normalizeRequiredValue(value).toUpperCase(Locale.ROOT);
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
