package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthProtocolEventRepositoryAdapter implements OAuthProtocolEventRepository {

    private final EntityManager entityManager;

    public OAuthProtocolEventRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OAuthProtocolEvent save(OAuthProtocolEvent event) {
        if (event.id() != null) throw new IllegalArgumentException("Protocol events are append-only.");
        OAuthProtocolEventJpaEntity entity = OAuthProtocolEventJpaEntity.from(event);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }
}
