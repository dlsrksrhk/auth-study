package com.sweet.authstudy.hr.department.infrastructure;

import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface DepartmentJpaRepository extends JpaRepository<DepartmentJpaEntity, Long> {

    Optional<DepartmentJpaEntity> findByCompanyIdAndCodeIgnoreCase(long companyId, String code);

    List<DepartmentJpaEntity> findAllByCompanyIdOrderByCodeAsc(long companyId);

    boolean existsByParentDepartmentIdAndStatus(long parentDepartmentId, DepartmentStatus status);

    @Query("""
            select d from DepartmentJpaEntity d
            where d.companyId = :companyId
              and (:status is null or d.status = :status)
              and (:search = '' or lower(d.code) like lower(concat('%', :search, '%'))
                or lower(d.name) like lower(concat('%', :search, '%')))
            """)
    Page<DepartmentJpaEntity> search(@Param("companyId") long companyId,
                                     @Param("search") String search, @Param("status") DepartmentStatus status, Pageable pageable);
}
