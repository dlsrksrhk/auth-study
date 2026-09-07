package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Repository
public class OAuthSigningKeyRepositoryAdapter implements OAuthSigningKeyRepository {

    private static final long KEY_RING_LOCK = 4_559_777_331L;

    private final EntityManager entityManager;

    public OAuthSigningKeyRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuthSigningKey requireActive() {
        return requireExactlyOneActive().toDomain();
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
    public OAuthSigningKey bootstrapIfAbsent(Supplier<OAuthSigningKey> candidate) {
        lockKeyRing();
        List<OAuthSigningKeyJpaEntity> active = activeKeys();
        if (active.size() > 1) throw invalidActiveRing();
        return active.isEmpty() ? persistNewActive(candidate.get()) : active.getFirst().toDomain();
    }

    @Override
    @Transactional
    public OAuthSigningKey rotate(Supplier<OAuthSigningKey> candidate, Instant retiredAt) {
        lockKeyRing();
        OAuthSigningKeyJpaEntity current = requireExactlyOneActive();
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

    private OAuthSigningKeyJpaEntity requireExactlyOneActive() {
        List<OAuthSigningKeyJpaEntity> active = activeKeys();
        if (active.size() != 1) throw invalidActiveRing();
        return active.getFirst();
    }

    private List<OAuthSigningKeyJpaEntity> activeKeys() {
        return entityManager.createQuery("""
                        select key from OAuthSigningKeyJpaEntity key where key.status = :status
                        """, OAuthSigningKeyJpaEntity.class)
                .setParameter("status", OAuthSigningKey.Status.ACTIVE)
                .getResultList();
    }

    private IllegalStateException invalidActiveRing() {
        return new IllegalStateException("Exactly one active OAuth signing key is required.");
    }

    private void lockKeyRing() {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:lockId)")
                .setParameter("lockId", KEY_RING_LOCK)
                .getSingleResult();
    }
}
