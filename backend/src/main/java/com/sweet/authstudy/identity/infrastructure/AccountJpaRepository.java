package com.sweet.authstudy.identity.infrastructure;

import java.util.Optional;
import java.util.List;
import java.util.Collection;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {

    Optional<AccountJpaEntity> findByUserId(long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.companyId = :companyId order by a.id")
    List<AccountJpaEntity> findAllByCompanyIdForUpdate(@Param("companyId") long companyId);

    List<AccountJpaEntity> findAllByUserIdIn(Collection<Long> userIds);

    Optional<AccountJpaEntity> findByCompanyIdAndLoginEmailIgnoreCase(long companyId, String loginEmail);

    Optional<AccountJpaEntity> findByCompanyIdIsNullAndLoginEmailIgnoreCase(String loginEmail);

    @Query("select a.id as accountId, a.passwordHash as passwordHash, a.status as status, "
            + "a.lockedUntil as lockedUntil from AccountJpaEntity a "
            + "where a.companyId = :companyId and lower(a.loginEmail) = lower(:email)")
    Optional<AccountLoginProjection> findCompanyLoginSnapshot(
            @Param("companyId") long companyId, @Param("email") String email);

    @Query("select a.id as accountId, a.passwordHash as passwordHash, a.status as status, "
            + "a.lockedUntil as lockedUntil from AccountJpaEntity a "
            + "where a.companyId is null and lower(a.loginEmail) = lower(:email)")
    Optional<AccountLoginProjection> findSystemLoginSnapshot(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.id = :id")
    Optional<AccountJpaEntity> findByIdForUpdate(@Param("id") long id);

}
