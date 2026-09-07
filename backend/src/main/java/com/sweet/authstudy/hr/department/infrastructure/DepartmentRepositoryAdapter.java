package com.sweet.authstudy.hr.department.infrastructure;

import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DepartmentRepositoryAdapter implements DepartmentRepository {

    private final DepartmentJpaRepository repository;

    public DepartmentRepositoryAdapter(DepartmentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Department save(Department department) {
        DepartmentJpaEntity entity = department.id() == null
                ? DepartmentJpaEntity.from(department)
                : repository.findById(department.id())
                .orElseThrow(() -> new IllegalStateException("Department does not exist."));
        if (department.id() != null) {
            entity.updateFrom(department);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Department> findById(long id) {
        return repository.findById(id).map(DepartmentJpaEntity::toDomain);
    }

    @Override
    public Optional<Department> findByCompanyIdAndCode(long companyId, String code) {
        return repository.findByCompanyIdAndCodeIgnoreCase(companyId, code).map(DepartmentJpaEntity::toDomain);
    }

    @Override
    public List<Department> findAllByCompanyId(long companyId) {
        return repository.findAllByCompanyIdOrderByCodeAsc(companyId).stream()
                .map(DepartmentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsActiveChild(long parentDepartmentId) {
        return repository.existsByParentDepartmentIdAndStatus(parentDepartmentId, DepartmentStatus.ACTIVE);
    }

    @Override
    public PageResult<Department> search(
            long companyId, String search, DepartmentStatus status, int page, int size, String sort) {
        var result = repository.search(companyId, search, status,
                PageRequest.of(page, size, stableSort(sort, "code")));
        return new PageResult<>(result.getContent().stream().map(DepartmentJpaEntity::toDomain).toList(),
                result.getTotalElements(), result.getTotalPages());
    }

    private Sort stableSort(String requested, String tieBreaker) {
        Sort sort = Sort.by(requested);
        return requested.equals(tieBreaker) ? sort : sort.and(Sort.by(tieBreaker));
    }
}
