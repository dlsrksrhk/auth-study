package com.sweet.authstudy.hr.company.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyJpaRepository extends JpaRepository<CompanyJpaEntity, Long> {

    Optional<CompanyJpaEntity> findByCodeIgnoreCase(String code);

    Optional<CompanyJpaEntity> findByEmailDomainIgnoreCase(String emailDomain);
}
