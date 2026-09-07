package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthClientView;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

public final class OAuthAdminResponses {
    private OAuthAdminResponses() {
    }

    public record ClientResponse(String companyCode, String clientId, String displayName,
                                 OAuthClientStatus status, OAuthClientTrust trust, boolean publicClient,
                                 Set<URI> redirectUris, Set<URI> postLogoutRedirectUris, Set<String> scopes,
                                 String activeSecretHint, long version, Instant createdAt, Instant updatedAt) {
        public static ClientResponse from(OAuthClientView view) {
            return new ClientResponse(view.companyCode(), view.clientId(), view.displayName(),
                    view.status(), view.trust(), view.publicClient(), view.redirectUris(),
                    view.postLogoutRedirectUris(), view.scopes(), view.activeSecretHint(),
                    view.version(), view.createdAt(), view.updatedAt());
        }
    }

    public record OneTimeClientSecretResponse(ClientResponse client, String oneTimeSecret) {
    }

    public record ConsentResponse(java.util.UUID subject, Set<String> approvedScopes,
                                  Instant grantedAt, Instant updatedAt) {
        public static ConsentResponse from(com.sweet.authstudy.oauth.application.OAuthConsentAdminQuery.Entry entry) {
            return new ConsentResponse(entry.subject(), entry.approvedScopes(), entry.grantedAt(), entry.updatedAt());
        }
    }

    public record ProtocolEventResponse(long id, Instant occurredAt, String correlationId,
                                        com.sweet.authstudy.oauth.domain.OAuthProtocolEvent.EventType eventType,
                                        com.sweet.authstudy.oauth.domain.OAuthProtocolEvent.Outcome outcome,
                                        String clientId, java.util.UUID subject, String authorizationId,
                                        String errorCode,
                                        java.util.Map<String, Object> metadata) {
        public static ProtocolEventResponse from(com.sweet.authstudy.oauth.domain.OAuthProtocolEvent event) {
            return new ProtocolEventResponse(event.id(), event.occurredAt(), event.correlationId(), event.eventType(),
                    event.outcome(), event.clientId(), event.subject(), event.authorizationId(), event.errorCode(), event.metadata().toMap());
        }
    }

    public record CursorResponse<T>(java.util.List<T> content, String nextCursor, boolean hasNext) {
    }
}
