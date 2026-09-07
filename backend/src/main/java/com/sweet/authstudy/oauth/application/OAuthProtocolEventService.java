package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEventRepository;
import com.sweet.authstudy.shared.trace.TraceIdProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Application boundary for sanitized OAuth/OIDC protocol history.
 */
@Service
public final class OAuthProtocolEventService {

    public static final class RequiredEventPersistenceException extends RuntimeException {
        public RequiredEventPersistenceException(RuntimeException cause) {
            super(cause);
        }
    }

    private static final String USERINFO_DENIED_ATTRIBUTE =
            OAuthProtocolEventService.class.getName() + ".userinfoDenied";

    public record Context(String clientId, UUID subject, Long accountId, Long companyId,
                          String authorizationId) {
        public static Context empty() {
            return new Context(null, null, null, null, null);
        }
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
            events.saveBestEffort(event);
        } catch (RuntimeException ignored) {
            // Protocol history is best-effort and must never change an OAuth/OIDC result.
        }
    }

    public void success(OAuthProtocolEvent.EventType type, Context context,
                        OAuthProtocolEvent.Metadata metadata) {
        bestEffort(() -> newEvent(
                type, OAuthProtocolEvent.Outcome.SUCCESS, context, null, metadata));
    }

    public void successRequired(OAuthProtocolEvent.EventType type, Context context,
                                OAuthProtocolEvent.Metadata metadata) {
        try {
            events.saveRequired(newEvent(
                    type, OAuthProtocolEvent.Outcome.SUCCESS, context, null, metadata));
        } catch (RuntimeException exception) {
            throw new RequiredEventPersistenceException(exception);
        }
    }

    public void failure(OAuthProtocolEvent.EventType type, Context context, String errorCode,
                        OAuthProtocolEvent.Metadata metadata) {
        bestEffort(() -> newEvent(
                type, OAuthProtocolEvent.Outcome.FAILURE, context, errorCode, metadata));
    }

    public void denied(OAuthProtocolEvent.EventType type, Context context, String errorCode,
                       OAuthProtocolEvent.Metadata metadata) {
        bestEffort(() -> newEvent(
                type, OAuthProtocolEvent.Outcome.DENIED, context, errorCode, metadata));
    }

    public void userInfoDenied(Context context) {
        org.springframework.web.context.request.RequestAttributes request =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (request != null && Boolean.TRUE.equals(request.getAttribute(
                USERINFO_DENIED_ATTRIBUTE, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST))) {
            return;
        }
        if (request != null) {
            request.setAttribute(USERINFO_DENIED_ATTRIBUTE, true,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        }
        denied(OAuthProtocolEvent.EventType.USERINFO_DENIED,
                context == null ? Context.empty() : context,
                "invalid_token", OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                        "endpoint", OAuthProtocolEvent.Endpoint.USERINFO,
                        "reason", OAuthProtocolEvent.FailureReason.INVALID_TOKEN,
                        "http_status", 401)));
    }

    private void bestEffort(java.util.function.Supplier<OAuthProtocolEvent> eventFactory) {
        try {
            events.saveBestEffort(eventFactory.get());
        } catch (RuntimeException ignored) {
            // Validation and storage are both isolated from the protocol result on this path.
        }
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
