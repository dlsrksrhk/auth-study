package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.audit.application.AuditActions;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.shared.application.PageResult;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthAdminService {
    private final OAuthClientService clientService;
    private final OAuthClientRepository clients;
    private final OAuthConsentAdminQuery consents;
    private final OAuthConsentService consentService;
    private final OAuthProtocolEventQuery events;
    private final OAuthGrantRevocationService grants;
    private final AuditService audit;
    private final Clock clock;

    public OAuthAdminService(OAuthClientService clientService, OAuthClientRepository clients,
            OAuthConsentAdminQuery consents, OAuthConsentService consentService, OAuthProtocolEventQuery events,
            OAuthGrantRevocationService grants, AuditService audit, Clock clock) {
        this.clientService = clientService;
        this.clients = clients;
        this.consents = consents;
        this.consentService = consentService;
        this.events = events;
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult<OAuthConsentAdminQuery.Entry> consents(AuthenticatedAccount actor,
            String companyCode, String clientId, int page, int size) {
        OAuthClient client = client(actor, companyCode, clientId);
        return consents.findPage(client.companyId(), client.id(), page, size);
    }

    @Transactional
    public void revokeConsent(AuthenticatedAccount actor, String companyCode, String clientId, UUID subject) {
        OAuthClient client = client(actor, companyCode, clientId);
        var consent = consents.find(client.companyId(), client.id(), subject)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "OAuth consent was not found."));
        consentService.remove(consent.accountId(), client.id());
        audit.record(actor, AuditActions.OAUTH_CONSENT_REVOKED, "OAUTH_CLIENT",
                client.id(), client.companyId(), Map.of());
    }

    @Transactional
    public void revokeAuthorizations(AuthenticatedAccount actor, String companyCode, String clientId) {
        OAuthClient client = client(actor, companyCode, clientId);
        grants.revokeClient(client.id(), clock.instant());
        audit.record(actor, AuditActions.OAUTH_AUTHORIZATIONS_REVOKED, "OAUTH_CLIENT",
                client.id(), client.companyId(), Map.of());
    }

    @Transactional(readOnly = true)
    public EventPage events(AuthenticatedAccount actor, String companyCode, String clientId,
            OAuthProtocolEvent.EventType type, OAuthProtocolEvent.Outcome outcome, String cursor, int size) {
        OAuthClient client = client(actor, companyCode, clientId);
        Cursor before = decode(cursor);
        var found = events.find(client.companyId(), client.clientId(), type, outcome,
                before == null ? null : before.time(), before == null ? null : before.id(), size + 1);
        boolean hasNext = found.size() > size;
        var content = List.copyOf(found.subList(0, Math.min(size, found.size())));
        String next = null;
        if (hasNext) {
            var last = content.get(content.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.occurredAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return new EventPage(content, next, hasNext);
    }

    private OAuthClient client(AuthenticatedAccount actor, String companyCode, String clientId) {
        clientService.requireClientInCompany(actor, companyCode, clientId);
        return clients.findByClientId(clientId).orElseThrow(() ->
                new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "OAuth client was not found."));
    }

    private Cursor decode(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 128) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Cursor value = new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
            if (value.id() <= 0) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException exception) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid event cursor.");
        }
    }

    private record Cursor(Instant time, long id) {}
    public record EventPage(List<OAuthProtocolEvent> content, String nextCursor, boolean hasNext) {}
}
