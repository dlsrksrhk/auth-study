package com.sweet.authstudy.hr.user.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

    Optional<UserJpaEntity> findByCompanyIdAndCodeIgnoreCase(long companyId, String code);

    Optional<UserJpaEntity> findByCompanyIdAndEmployeeNumber(long companyId, String employeeNumber);
}
