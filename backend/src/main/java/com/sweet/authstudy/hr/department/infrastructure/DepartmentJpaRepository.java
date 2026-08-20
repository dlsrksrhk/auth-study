package com.sweet.authstudy.hr.department.infrastructure;

import java.util.List;
import java.util.Optional;

import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

interface DepartmentJpaRepository extends JpaRepository<DepartmentJpaEntity, Long> {

    Optional<DepartmentJpaEntity> findByCompanyIdAndCodeIgnoreCase(long companyId, String code);

    List<DepartmentJpaEntity> findAllByCompanyIdOrderByCodeAsc(long companyId);

    boolean existsByParentDepartmentIdAndStatus(long parentDepartmentId, DepartmentStatus status);
}
