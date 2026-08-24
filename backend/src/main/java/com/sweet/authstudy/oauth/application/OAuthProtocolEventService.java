package com.sweet.authstudy.oauth.application;

import java.time.Clock;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEventRepository;
import com.sweet.authstudy.shared.trace.TraceIdProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Application boundary for sanitized OAuth/OIDC protocol history. */
@Service
public final class OAuthProtocolEventService {

    public record Context(String clientId, UUID subject, Long accountId, Long companyId,
            String authorizationId) {
        public static Context empty() { return new Context(null, null, null, null, null); }
    }

    private final OAuthProtocolEventRepository events;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    public OAuthProtocolEventService(OAuthProtocolEventRepository events,
            TraceIdProvider traceIds, Clock clock) {
        this.events = events;
        this.traceIds = traceIds;
        this.clock = clock;
    }

    public void record(OAuthProtocolEvent event) {
        try {
            events.save(event);
        } catch (RuntimeException ignored) {
            // Protocol history is best-effort and must never change an OAuth/OIDC result.
        }
    }

    public void success(OAuthProtocolEvent.EventType type, Context context,
            OAuthProtocolEvent.Metadata metadata) {
        record(newEvent(type, OAuthProtocolEvent.Outcome.SUCCESS, context, null, metadata));
    }

    public void successAfterCommit(OAuthProtocolEvent.EventType type, Context context,
            OAuthProtocolEvent.Metadata metadata) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            success(type, context, metadata);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                success(type, context, metadata);
            }
        });
    }

    public void failure(OAuthProtocolEvent.EventType type, Context context, String errorCode,
            OAuthProtocolEvent.Metadata metadata) {
        record(newEvent(type, OAuthProtocolEvent.Outcome.FAILURE, context, errorCode, metadata));
    }

    public void denied(OAuthProtocolEvent.EventType type, Context context, String errorCode,
            OAuthProtocolEvent.Metadata metadata) {
        record(newEvent(type, OAuthProtocolEvent.Outcome.DENIED, context, errorCode, metadata));
    }

    private OAuthProtocolEvent newEvent(OAuthProtocolEvent.EventType type,
            OAuthProtocolEvent.Outcome outcome, Context context, String errorCode,
            OAuthProtocolEvent.Metadata metadata) {
        Context safe = context == null ? Context.empty() : context;
        return OAuthProtocolEvent.create(clock.instant(), traceIds.current(), type, outcome,
                safe.clientId(), safe.subject(), safe.accountId(), safe.companyId(),
                safe.authorizationId(), errorCode, metadata);
    }
}
