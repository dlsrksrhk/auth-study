package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "oauth_protocol_event")
class OAuthProtocolEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private OAuthProtocolEvent.EventType eventType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OAuthProtocolEvent.Outcome outcome;
    @Column(name = "client_id", updatable = false)
    private String clientId;
    @Column(updatable = false)
    private UUID subject;
    @Column(name = "account_id", updatable = false)
    private Long accountId;
    @Column(name = "company_id", updatable = false)
    private Long companyId;
    @Column(name = "authorization_id", updatable = false)
    private String authorizationId;
    @Column(name = "error_code", updatable = false)
    private String errorCode;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    protected OAuthProtocolEventJpaEntity() { }

    static OAuthProtocolEventJpaEntity from(OAuthProtocolEvent event) {
        OAuthProtocolEventJpaEntity entity = new OAuthProtocolEventJpaEntity();
        entity.occurredAt = event.occurredAt();
        entity.correlationId = event.correlationId();
        entity.eventType = event.eventType();
        entity.outcome = event.outcome();
        entity.clientId = event.clientId();
        entity.subject = event.subject();
        entity.accountId = event.accountId();
        entity.companyId = event.companyId();
        entity.authorizationId = event.authorizationId();
        entity.errorCode = event.errorCode();
        entity.metadata = event.metadata().toMap();
        return entity;
    }

    OAuthProtocolEvent toDomain() {
        return OAuthProtocolEvent.restore(id, occurredAt, correlationId, eventType, outcome,
                clientId, subject, accountId, companyId, authorizationId, errorCode,
                OAuthProtocolEvent.Metadata.from(metadata));
    }
}
