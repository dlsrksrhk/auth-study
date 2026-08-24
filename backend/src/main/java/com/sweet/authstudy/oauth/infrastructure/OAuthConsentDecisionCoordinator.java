package com.sweet.authstudy.oauth.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.sweet.authstudy.oauth.application.IdpSessionStateService;
import com.sweet.authstudy.oauth.application.OAuthConsentDecisionService;
import com.sweet.authstudy.oauth.application.OAuthConsentService;
import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthConsent;
import com.sweet.authstudy.oauth.domain.OAuthConsentRepository;
import jakarta.persistence.EntityManager;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Linearizes one pending consent decision and its resulting authorization-code persistence. */
@Service
public class OAuthConsentDecisionCoordinator implements OAuthConsentDecisionService {
    private static final String CODE_CHALLENGE = "code_challenge";
    private static final String CODE_CHALLENGE_METHOD = "code_challenge_method";
    private static final String NONCE = "nonce";

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthClientRepository clients;
    private final OAuthConsentRepository consents;
    private final IdpSessionStateService sessionStates;
    private final OAuthAuthorizationMapper mapper;
    private final Clock clock;
    private final EntityManager entityManager;
    private final OAuthProtocolEventService protocolEvents;

    public OAuthConsentDecisionCoordinator(OAuthAuthorizationRepository authorizations,
            OAuthClientRepository clients, OAuthConsentRepository consents,
            IdpSessionStateService sessionStates, OAuthAuthorizationMapper mapper, Clock clock,
            EntityManager entityManager, OAuthProtocolEventService protocolEvents) {
        this.authorizations = authorizations;
        this.clients = clients;
        this.consents = consents;
        this.sessionStates = sessionStates;
        this.mapper = mapper;
        this.clock = clock;
        this.entityManager = entityManager;
        this.protocolEvents = protocolEvents;
    }

    @Transactional
    public void approve(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization source,
            OAuthConsentService.ApprovalDecision decision) {
        clearRequestReadSnapshot();
        Instant now = clock.instant();
        OAuthAuthorization pending = lockPending(decision, now);
        OAuthClient client = lockClient(decision, pending);
        requireCurrentIdentity(decision);
        validateSource(source, decision, pending, client);

        consents.lockDecision(decision.accountId(), decision.registeredClientId());
        OAuthConsent consent = consents.findByAccountIdAndRegisteredClientIdForUpdate(
                        decision.accountId(), decision.registeredClientId()).orElse(null);
        Set<String> finalApprovedScopes = consent == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(consent.scopes());
        finalApprovedScopes.addAll(decision.requestedScopes());
        if (!client.scopes().containsAll(finalApprovedScopes)) throw invalidDecision();
        if (consent == null) {
            consent = OAuthConsent.create(decision.accountId(), decision.registeredClientId(),
                    finalApprovedScopes, now);
        } else {
            consent.replaceScopes(finalApprovedScopes, now);
        }

        OAuthAuthorization completed = mapper.toDomain(source, pending);
        if (completed.serverStateHash() != null || completed.authorizationCode().isEmpty()
                || !completed.authorizedScopes().equals(decision.requestedScopes())) {
            throw invalidDecision();
        }
        // Both flushes participate in this transaction: a failed code save rolls consent back.
        consents.save(consent);
        authorizations.save(completed);
        OAuthProtocolEventService.Context eventContext = new OAuthProtocolEventService.Context(
                decision.clientId(), decision.sub(), decision.accountId(), decision.companyId(), completed.id());
        OAuthProtocolEvent.Metadata metadata = OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                "endpoint", "CONSENT", "scopes", decision.requestedScopes(), "redirect_validated", true));
        protocolEvents.successAfterCommit(OAuthProtocolEvent.EventType.CONSENT_APPROVED, eventContext, metadata);
        protocolEvents.successAfterCommit(OAuthProtocolEvent.EventType.CODE_ISSUED, eventContext, metadata);
    }

    @Transactional
    @Override
    public void deny(OAuthConsentService.ApprovalDecision decision) {
        clearRequestReadSnapshot();
        OAuthAuthorization pending = lockPending(decision, clock.instant());
        lockClient(decision, pending);
        requireCurrentIdentity(decision);
        consents.lockDecision(decision.accountId(), decision.registeredClientId());
        consents.findByAccountIdAndRegisteredClientIdForUpdate(
                decision.accountId(), decision.registeredClientId());
        authorizations.remove(pending.id());
    }

    private void clearRequestReadSnapshot() {
        // The browser filter reads the pending aggregate before this transaction. With OpenEntityManagerInView,
        // that read otherwise remains in the first-level cache even after SELECT FOR UPDATE waits for a winner.
        entityManager.clear();
    }

    private OAuthAuthorization lockPending(OAuthConsentService.ApprovalDecision decision, Instant now) {
        OAuthAuthorization pending = authorizations.findByIdForUpdate(decision.authorizationId())
                .filter(candidate -> candidate.activeAt(now))
                .orElseThrow(OAuthConsentDecisionCoordinator::invalidDecision);
        OAuthAuthorization.AuthorizationRequest request = pending.attributes().authorizationRequest();
        if (!Objects.equals(pending.serverStateHash(), decision.serverStateHash())
                || pending.principalAccountId() != decision.accountId()
                || pending.companyId() != decision.companyId()
                || !pending.subject().equals(decision.sub())
                || pending.registeredClientId() != decision.registeredClientId()
                || request == null
                || !request.requestedScopes().equals(decision.requestedScopes())) {
            throw invalidDecision();
        }
        return pending;
    }

    private OAuthClient lockClient(OAuthConsentService.ApprovalDecision decision,
            OAuthAuthorization pending) {
        OAuthClient client = clients.findByIdForUpdate(decision.registeredClientId())
                .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                .orElseThrow(OAuthConsentDecisionCoordinator::invalidDecision);
        OAuthAuthorization.AuthorizationRequest request = pending.attributes().authorizationRequest();
        if (!client.clientId().equals(decision.clientId())
                || client.companyId() != decision.companyId()
                || !client.scopes().containsAll(decision.requestedScopes())
                || client.redirectUris().stream().noneMatch(
                        redirect -> redirect.toString().equals(request.redirectUri()))) {
            throw invalidDecision();
        }
        return client;
    }

    private void requireCurrentIdentity(OAuthConsentService.ApprovalDecision decision) {
        if (sessionStates.evaluateLocked(decision.accountId(), decision.companyId(),
                decision.userId(), decision.sub()) != IdpSessionStateService.State.CURRENT) {
            throw invalidDecision();
        }
    }

    private void validateSource(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization source,
            OAuthConsentService.ApprovalDecision decision, OAuthAuthorization pending, OAuthClient client) {
        if (!source.getId().equals(pending.id())
                || !source.getRegisteredClientId().equals(Long.toString(decision.registeredClientId()))
                || !source.getPrincipalName().equals(Long.toString(decision.accountId()))
                || !source.getAuthorizedScopes().equals(decision.requestedScopes())
                || source.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class)
                        == null) {
            throw invalidDecision();
        }
        OAuth2AuthorizationRequest sourceRequest = source.getAttribute(OAuth2AuthorizationRequest.class.getName());
        OAuthAuthorization.AuthorizationRequest locked = pending.attributes().authorizationRequest();
        if (sourceRequest == null
                || !client.clientId().equals(sourceRequest.getClientId())
                || !Objects.equals(pending.attributes().authorizationRequestUri(),
                        sourceRequest.getAuthorizationUri())
                || !Objects.equals(locked.redirectUri(), sourceRequest.getRedirectUri())
                || !locked.requestedScopes().equals(sourceRequest.getScopes())
                || !Objects.equals(locked.rpState(), sourceRequest.getState())
                || !Objects.equals(locked.codeChallenge(),
                        sourceRequest.getAdditionalParameters().get(CODE_CHALLENGE))
                || !Objects.equals(locked.codeChallengeMethod(),
                        sourceRequest.getAdditionalParameters().get(CODE_CHALLENGE_METHOD))
                || !Objects.equals(locked.nonce(), sourceRequest.getAdditionalParameters().get(NONCE))) {
            throw invalidDecision();
        }
    }

    private static IllegalArgumentException invalidDecision() {
        return new IllegalArgumentException("The consent decision is stale or invalid.");
    }
}
