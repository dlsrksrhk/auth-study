package com.sweet.authstudy.hr.company.infrastructure;

import java.util.Optional;
import java.util.List;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyRepositoryAdapter implements CompanyRepository {

    private final CompanyJpaRepository repository;
    private final EntityManager entityManager;

    public CompanyRepositoryAdapter(CompanyJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
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

    @Override
    public Optional<Company> findLockedById(long id) {
        return repository.findById(id).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return entity.toDomain();
        });
    }

    @Override
    public List<Company> findAll() {
        return repository.findAll().stream().map(CompanyJpaEntity::toDomain).toList();
    }
}
