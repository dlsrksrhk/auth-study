package com.sweet.authstudy.hr.company.infrastructure;

import java.util.Optional;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyRepositoryAdapter implements CompanyRepository {

    private final CompanyJpaRepository repository;

    public CompanyRepositoryAdapter(CompanyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Company save(Company company) {
        CompanyJpaEntity entity = company.id() == null
                ? CompanyJpaEntity.from(company)
                : repository.findById(company.id())
                        .orElseThrow(() -> new IllegalStateException("Company does not exist."));
        if (company.id() != null) {
            entity.updateFrom(company);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Company> findByCode(String code) {
        return repository.findByCodeIgnoreCase(code).map(CompanyJpaEntity::toDomain);
    }

    @Override
    public Optional<Company> findByEmailDomain(String emailDomain) {
        return repository.findByEmailDomainIgnoreCase(emailDomain).map(CompanyJpaEntity::toDomain);
    }

    @Override
    public Optional<Company> findById(long id) {
        return repository.findById(id).map(CompanyJpaEntity::toDomain);
    }
}
