package com.sweet.authstudy.hr.company.application;

import java.time.Instant;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;

public record CompanyView(
        long id,
        String code,
        String name,
        String emailDomain,
        CompanyStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static CompanyView from(Company company) {
        return new CompanyView(
                company.id(),
                company.code(),
                company.name(),
                company.emailDomain(),
                company.status(),
                company.version(),
                company.createdAt(),
                company.updatedAt());
    }
}
