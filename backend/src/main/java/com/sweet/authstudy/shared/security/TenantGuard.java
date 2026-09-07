package com.sweet.authstudy.shared.security;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.validation.BusinessCode;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class TenantGuard {
    private final CompanyRepository companyRepository;

    public TenantGuard(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public void requireSystemAdmin(AuthenticatedAccount actor) {
        requireAuthenticated(actor);
        if (!actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            throw forbidden();
        }
    }

    public void requireCompanyAccess(AuthenticatedAccount actor, long targetCompanyId) {
        requireAuthenticated(actor);
        if (actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            return;
        }
        if (!actor.roles().contains(AccountRole.COMPANY_ADMIN)
                || !Objects.equals(actor.companyId(), targetCompanyId)) {
            throw forbidden();
        }
        Company company = companyRepository.findById(targetCompanyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
        if (company.status() != CompanyStatus.ACTIVE) {
            throw forbidden();
        }
    }

    public long requireCompanyAccess(AuthenticatedAccount actor, String companyCode) {
        Company company = companyRepository.findByCode(BusinessCode.normalize(companyCode))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Company was not found."));
        requireCompanyAccess(actor, company.id());
        return company.id();
    }

    private void requireAuthenticated(AuthenticatedAccount actor) {
        if (actor == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
        }
    }

    private ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You do not have permission to perform this action.");
    }
}
