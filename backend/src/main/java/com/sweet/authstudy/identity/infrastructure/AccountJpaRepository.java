package com.sweet.authstudy.identity.infrastructure;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {

    Optional<AccountJpaEntity> findByUserId(long userId);

    Optional<AccountJpaEntity> findByCompanyIdAndLoginEmailIgnoreCase(long companyId, String loginEmail);

    Optional<AccountJpaEntity> findByCompanyIdIsNullAndLoginEmailIgnoreCase(String loginEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.id = :id")
    Optional<AccountJpaEntity> findByIdForUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.companyId = :companyId and lower(a.loginEmail) = lower(:email)")
    Optional<AccountJpaEntity> findCompanyAccountForUpdate(
            @Param("companyId") long companyId, @Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.companyId is null and lower(a.loginEmail) = lower(:email)")
    Optional<AccountJpaEntity> findSystemByEmailForUpdate(@Param("email") String email);
}
