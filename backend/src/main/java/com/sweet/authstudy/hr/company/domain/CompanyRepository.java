package com.sweet.authstudy.hr.company.domain;

import java.util.Optional;

public interface CompanyRepository {

    Company save(Company company);

    Optional<Company> findByCode(String code);

    Optional<Company> findByEmailDomain(String emailDomain);

    Optional<Company> findById(long id);
}
