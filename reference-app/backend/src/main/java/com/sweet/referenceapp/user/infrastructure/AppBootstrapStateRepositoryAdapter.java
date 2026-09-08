package com.sweet.referenceapp.user.infrastructure;

import com.sweet.referenceapp.user.domain.AppBootstrapState;
import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AppBootstrapStateRepositoryAdapter implements AppBootstrapStateRepository {
    private final EntityManager entityManager;
    private final AppBootstrapStateJpaRepository jpaRepository;

    public AppBootstrapStateRepositoryAdapter(EntityManager entityManager,
            AppBootstrapStateJpaRepository jpaRepository) {
        this.entityManager = entityManager;
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AppBootstrapState findSingletonForUpdate() {
        return lockedSingleton().toDomain();
    }

    @Override
    public void complete(AppBootstrapState completed) {
        if (!completed.completed()) {
            throw new IllegalArgumentException("Completed bootstrap state required");
        }
        var entity = lockedSingleton();
        if (entity.version() != completed.version()) {
            throw new ObjectOptimisticLockingFailureException(
                    AppBootstrapStateJpaEntity.class, completed.singletonKey());
        }
        if (entity.completed()) {
            throw new IllegalStateException("Bootstrap already completed");
        }
        entity.complete(completed.bootstrappedUserId(), completed.bootstrappedAt());
        entityManager.flush();
    }

    private AppBootstrapStateJpaEntity lockedSingleton() {
        var entity = jpaRepository.findSingletonLocked()
                .orElseThrow(() -> new IllegalStateException("Bootstrap state missing"));
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
}
