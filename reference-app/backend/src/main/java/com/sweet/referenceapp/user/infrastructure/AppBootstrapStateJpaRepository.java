package com.sweet.referenceapp.user.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AppBootstrapStateJpaRepository
        extends JpaRepository<AppBootstrapStateJpaEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AppBootstrapStateJpaEntity s where s.singletonKey=1")
    Optional<AppBootstrapStateJpaEntity> findSingletonLocked();
}
