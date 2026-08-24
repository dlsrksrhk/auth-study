package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthSigningKeyRepositoryAdapter implements OAuthSigningKeyRepository {

    private static final long KEY_RING_LOCK = 4_559_777_331L;

    private final EntityManager entityManager;

    public OAuthSigningKeyRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthSigningKey> findActive() {
        return active(false).map(OAuthSigningKeyJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OAuthSigningKey> findVerificationOnlyRetiredAfter(Instant cutoff) {
        return entityManager.createQuery("""
                select key from OAuthSigningKeyJpaEntity key
                where key.status = :status and key.retiredAt > :cutoff
                order by key.retiredAt desc, key.id desc
                """, OAuthSigningKeyJpaEntity.class)
                .setParameter("status", OAuthSigningKey.Status.VERIFICATION_ONLY)
                .setParameter("cutoff", cutoff)
                .getResultList().stream().map(OAuthSigningKeyJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public OAuthSigningKey save(OAuthSigningKey key) {
        if (key.id() == null) {
            OAuthSigningKeyJpaEntity entity = OAuthSigningKeyJpaEntity.from(key);
            entityManager.persist(entity);
            entityManager.flush();
            return entity.toDomain();
        }
        OAuthSigningKeyJpaEntity entity = entityManager.find(
                OAuthSigningKeyJpaEntity.class, key.id(), LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) throw new IllegalStateException("OAuth signing key does not exist.");
        entity.updateLifecycle(key);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public OAuthSigningKey bootstrapIfAbsent(Supplier<OAuthSigningKey> candidate) {
        lockKeyRing();
        return active(true).map(OAuthSigningKeyJpaEntity::toDomain)
                .orElseGet(() -> persistNewActive(candidate.get()));
    }

    @Override
    @Transactional
    public OAuthSigningKey rotate(Supplier<OAuthSigningKey> candidate, Instant retiredAt) {
        lockKeyRing();
        OAuthSigningKeyJpaEntity current = active(true)
                .orElseThrow(() -> new IllegalStateException("An active OAuth signing key is required for rotation."));
        current.updateLifecycle(current.toDomain().retire(retiredAt));
        entityManager.flush();
        return persistNewActive(candidate.get());
    }

    private OAuthSigningKey persistNewActive(OAuthSigningKey key) {
        if (key.id() != null || key.status() != OAuthSigningKey.Status.ACTIVE) {
            throw new IllegalArgumentException("A new active OAuth signing key is required.");
        }
        OAuthSigningKeyJpaEntity entity = OAuthSigningKeyJpaEntity.from(key);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    private Optional<OAuthSigningKeyJpaEntity> active(boolean lock) {
        var query = entityManager.createQuery("""
                select key from OAuthSigningKeyJpaEntity key where key.status = :status
                """, OAuthSigningKeyJpaEntity.class)
                .setParameter("status", OAuthSigningKey.Status.ACTIVE)
                .setMaxResults(1);
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        return query.getResultStream().findFirst();
    }

    private void lockKeyRing() {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:lockId)")
                .setParameter("lockId", KEY_RING_LOCK)
                .getSingleResult();
    }
}
