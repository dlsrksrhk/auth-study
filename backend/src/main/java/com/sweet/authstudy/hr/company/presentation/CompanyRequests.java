package com.sweet.authstudy.hr.company.presentation;

import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CompanyRequests {
    private CompanyRequests() {}

    public record CreateCompanyRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 253) String emailDomain) {}

    public record UpdateCompanyRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull CompanyStatus status,
            @NotNull Long version) {}
}
