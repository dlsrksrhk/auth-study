package com.sweet.authstudy.hr.company.infrastructure;

import java.util.Optional;

import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CompanyJpaRepository extends JpaRepository<CompanyJpaEntity, Long> {

    Optional<CompanyJpaEntity> findByCodeIgnoreCase(String code);

    Optional<CompanyJpaEntity> findByEmailDomainIgnoreCase(String emailDomain);

    @Query("""
            select c from CompanyJpaEntity c
            where (:status is null or c.status = :status)
              and (:search = '' or lower(c.code) like lower(concat('%', :search, '%'))
                or lower(c.name) like lower(concat('%', :search, '%'))
                or lower(c.emailDomain) like lower(concat('%', :search, '%')))
            """)
    Page<CompanyJpaEntity> search(
            @Param("search") String search, @Param("status") CompanyStatus status, Pageable pageable);
}
