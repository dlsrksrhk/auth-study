package com.sweet.authstudy.identity.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {

    Optional<AccountJpaEntity> findByUserId(long userId);

    Optional<AccountJpaEntity> findByCompanyIdAndLoginEmailIgnoreCase(long companyId, String loginEmail);

    Optional<AccountJpaEntity> findByCompanyIdIsNullAndLoginEmailIgnoreCase(String loginEmail);
}
