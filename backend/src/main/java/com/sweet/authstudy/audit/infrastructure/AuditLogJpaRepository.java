package com.sweet.authstudy.audit.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, Long> {
    @Query("""
            select a from AuditLogJpaEntity a
            where a.companyId = :companyId
              and (:action is null or a.action = :action)
              and (:success is null or a.success = :success)
              and (:search = '' or lower(a.action) like lower(concat('%', :search, '%'))
                or lower(a.targetType) like lower(concat('%', :search, '%'))
                or lower(a.traceId) like lower(concat('%', :search, '%')))
            """)
    Page<AuditLogJpaEntity> search(
            @Param("companyId") long companyId,
            @Param("search") String search,
            @Param("action") String action,
            @Param("success") Boolean success,
            Pageable pageable);
}
