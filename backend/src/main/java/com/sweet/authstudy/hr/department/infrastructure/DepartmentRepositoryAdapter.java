package com.sweet.authstudy.hr.department.infrastructure;

import java.util.List;
import java.util.Optional;

import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import org.springframework.stereotype.Repository;

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
}
