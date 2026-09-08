package com.sweet.referenceapp.user.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserJpaRepository extends JpaRepository<AppUserJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUserJpaEntity u where u.issuer=:issuer and u.subject=:subject")
    Optional<AppUserJpaEntity> findLocked(@Param("issuer") String issuer,
            @Param("subject") String subject);
}
