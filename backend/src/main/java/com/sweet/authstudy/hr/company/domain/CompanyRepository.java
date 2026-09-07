package com.sweet.authstudy.hr.company.domain;

import com.sweet.authstudy.shared.application.PageResult;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository {

    Company save(Company company);

    Optional<Company> findByCode(String code);

    Optional<Company> findByEmailDomain(String emailDomain);

    Optional<Company> findById(long id);

    Optional<Company> findLockedById(long id);

    List<Company> findAll();

    PageResult<Company> search(String search, CompanyStatus status, int page, int size, String sort);
}
