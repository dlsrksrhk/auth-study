package com.sweet.authstudy.hr.company.application;

import com.sweet.authstudy.hr.company.domain.CompanyStatus;

public final class CompanyCommands {

    private CompanyCommands() {
    }

    public record CreateCompanyCommand(String code, String name, String emailDomain) {
    }

    public record UpdateCompanyCommand(String name, CompanyStatus status, long version) {
    }
}
