package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthProtocolEventRepositoryAdapter implements OAuthProtocolEventRepository,
        com.sweet.authstudy.oauth.application.OAuthProtocolEventQuery {

    private final EntityManager entityManager;

    public OAuthProtocolEventRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OAuthProtocolEvent saveRequired(OAuthProtocolEvent event) {
        return persist(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OAuthProtocolEvent saveBestEffort(OAuthProtocolEvent event) {
        return persist(event);
    }

    private OAuthProtocolEvent persist(OAuthProtocolEvent event) {
        if (event.id() != null) throw new IllegalArgumentException("Protocol events are append-only.");
        OAuthProtocolEventJpaEntity entity = OAuthProtocolEventJpaEntity.from(event);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<OAuthProtocolEvent> find(long companyId, String clientId,
            OAuthProtocolEvent.EventType type, OAuthProtocolEvent.Outcome outcome,
            java.time.Instant beforeTime, Long beforeId, int limit) {
        String jpql = "select e from OAuthProtocolEventJpaEntity e where e.companyId=:company and e.clientId=:client";
        if (type != null) jpql += " and e.eventType=:type";
        if (outcome != null) jpql += " and e.outcome=:outcome";
        if (beforeTime != null) jpql += " and (e.occurredAt<:time or (e.occurredAt=:time and e.id<:id))";
        var query=entityManager.createQuery(jpql+" order by e.occurredAt desc,e.id desc",OAuthProtocolEventJpaEntity.class)
                .setParameter("company",companyId).setParameter("client",clientId).setMaxResults(limit);
        if (type != null) query.setParameter("type",type);
        if (outcome != null) query.setParameter("outcome",outcome);
        if (beforeTime != null) query.setParameter("time",beforeTime).setParameter("id",beforeId);
        return query.getResultList().stream().map(OAuthProtocolEventJpaEntity::toDomain).toList();
    }
}
