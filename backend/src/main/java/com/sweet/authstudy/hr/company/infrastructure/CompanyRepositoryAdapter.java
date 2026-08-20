package com.sweet.authstudy.hr.company.infrastructure;

import java.util.Optional;
import java.util.List;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.shared.application.PageResult;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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

    @Override
    public PageResult<Company> search(
            String search, CompanyStatus status, int page, int size, String sort) {
        var result = repository.search(search, status, PageRequest.of(page, size, stableSort(sort, "code")));
        return new PageResult<>(result.getContent().stream().map(CompanyJpaEntity::toDomain).toList(),
                result.getTotalElements(), result.getTotalPages());
    }

    private Sort stableSort(String requested, String tieBreaker) {
        Sort sort = Sort.by(requested);
        return requested.equals(tieBreaker) ? sort : sort.and(Sort.by(tieBreaker));
    }
}
