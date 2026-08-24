package com.sweet.authstudy.oauth.application;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthConsent;
import com.sweet.authstudy.oauth.domain.OAuthConsentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthConsentService {
    private static final Map<String, String> SCOPE_DESCRIPTIONS = descriptions();

    private final OAuthConsentRepository consents;
    private final OAuthClientRepository clients;
    private final OAuthAuthorizationRepository authorizations;
    private final Clock clock;

    public OAuthConsentService(OAuthConsentRepository consents,
            OAuthClientRepository clients,
            OAuthAuthorizationRepository authorizations,
            Clock clock) {
        this.consents = consents;
        this.clients = clients;
        this.authorizations = authorizations;
        this.clock = clock;
    }

    @Transactional
    public void approve(long accountId, long registeredClientId, Set<String> authorizedScopes) {
        OAuthClient client = activeClient(registeredClientId);
        Set<String> snapshot = Set.copyOf(authorizedScopes);
        if (snapshot.isEmpty() || !client.scopes().containsAll(snapshot)) {
            throw new IllegalArgumentException("Authorized scopes must be an exact subset of the active client scopes.");
        }
        OAuthConsent consent = consents.findByAccountIdAndRegisteredClientId(accountId, registeredClientId)
                .orElseGet(() -> OAuthConsent.create(accountId, registeredClientId, snapshot, clock.instant()));
        if (consent.id() != null) consent.replaceScopes(snapshot, clock.instant());
        consents.save(consent);
    }

    @Transactional
    public void remove(long accountId, long registeredClientId) {
        consents.remove(accountId, registeredClientId);
    }

    @Transactional(readOnly = true)
    public Optional<OAuthConsent> find(long accountId, long registeredClientId) {
        if (clients.findById(registeredClientId)
                .filter(client -> client.status() == OAuthClientStatus.ACTIVE).isEmpty()) {
            return Optional.empty();
        }
        return consents.findByAccountIdAndRegisteredClientId(accountId, registeredClientId);
    }

    @Transactional(readOnly = true)
    public ConsentReview reviewPending(long accountId, long companyId, UUID sub,
            String authorizationId, String publicClientId, Set<String> requestedScopes) {
        OAuthAuthorization authorization = authorizations.findById(authorizationId)
                .filter(candidate -> candidate.activeAt(clock.instant()))
                .orElseThrow(OAuthConsentService::invalidPending);
        OAuthClient client = activeClient(authorization.registeredClientId());
        Set<String> exactRequested = Set.copyOf(requestedScopes);
        OAuthAuthorization.AuthorizationRequest request = authorization.attributes().authorizationRequest();
        if (authorization.serverStateHash() == null
                || authorization.principalAccountId() != accountId
                || authorization.companyId() != companyId
                || !authorization.subject().equals(sub)
                || !client.clientId().equals(publicClientId)
                || client.companyId() != companyId
                || request == null
                || !request.requestedScopes().equals(exactRequested)) {
            throw invalidPending();
        }
        Set<String> approved = consents.findByAccountIdAndRegisteredClientId(accountId, client.id())
                .map(OAuthConsent::scopes).orElse(Set.of());
        List<ScopeView> all = exactRequested.stream()
                .sorted(Comparator.comparingInt(OAuthConsentService::scopeOrder).thenComparing(String::compareTo))
                .map(scope -> scopeView(scope, !approved.contains(scope))).toList();
        List<ScopeView> newlyRequested = all.stream().filter(ScopeView::newlyRequested).toList();
        return new ConsentReview(client.id(), client.clientId(), client.displayName(), all, newlyRequested);
    }

    @Transactional(readOnly = true)
    public ApprovalDecision validateApproval(String rawServerState, String publicClientId,
            Set<String> submittedScopes, long accountId, long companyId, long userId, UUID sub) {
        if (rawServerState == null || rawServerState.isBlank()
                || publicClientId == null || publicClientId.isBlank()) throw invalidPending();
        OAuthAuthorization authorization = authorizations.findByServerStateHash(sha256(rawServerState))
                .filter(candidate -> candidate.activeAt(clock.instant()))
                .orElseThrow(OAuthConsentService::invalidPending);
        OAuthClient client = activeClient(authorization.registeredClientId());
        OAuthAuthorization.AuthorizationRequest request = authorization.attributes().authorizationRequest();
        Set<String> exactSubmitted = Set.copyOf(submittedScopes);
        if (authorization.serverStateHash() == null
                || !authorization.serverStateHash().equals(sha256(rawServerState))
                || authorization.principalAccountId() != accountId
                || authorization.companyId() != companyId
                || !authorization.subject().equals(sub)
                || !client.clientId().equals(publicClientId)
                || client.companyId() != companyId
                || request == null
                || exactSubmitted.isEmpty()
                || !request.requestedScopes().equals(exactSubmitted)) {
            throw invalidPending();
        }
        return new ApprovalDecision(authorization.id(), authorization.serverStateHash(), accountId,
                companyId, userId, sub, client.id(), publicClientId, exactSubmitted);
    }

    @Transactional(readOnly = true)
    public void validateSasApproval(ApprovalDecision decision, long accountId,
            long registeredClientId, Set<String> cumulativeScopes) {
        Set<String> snapshot = Set.copyOf(cumulativeScopes);
        OAuthClient client = activeClient(registeredClientId);
        if (decision.accountId() != accountId
                || decision.registeredClientId() != registeredClientId
                || decision.companyId() != client.companyId()
                || !decision.clientId().equals(client.clientId())
                || !snapshot.containsAll(decision.requestedScopes())
                || !client.scopes().containsAll(snapshot)) {
            throw invalidPending();
        }
    }

    private OAuthClient activeClient(long registeredClientId) {
        return clients.findById(registeredClientId)
                .filter(client -> client.status() == OAuthClientStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Active OAuth client does not exist."));
    }

    private static ScopeView scopeView(String scope, boolean newlyRequested) {
        String description = SCOPE_DESCRIPTIONS.get(scope);
        if (description == null) throw new IllegalArgumentException("Unsupported OAuth scope.");
        return new ScopeView(scope, description, newlyRequested);
    }

    private static int scopeOrder(String scope) {
        int index = List.copyOf(SCOPE_DESCRIPTIONS.keySet()).indexOf(scope);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static Map<String, String> descriptions() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("openid", "기본 식별");
        values.put("profile", "프로필");
        values.put("email", "이메일");
        values.put("hr.company", "회사");
        values.put("company", "회사");
        values.put("hr.organization", "조직/직위");
        values.put("org", "조직/직위");
        values.put("title", "조직/직위");
        values.put("hr.roles", "HR 역할");
        values.put("roles", "HR 역할");
        return java.util.Collections.unmodifiableMap(values);
    }

    private static IllegalArgumentException invalidPending() {
        return new IllegalArgumentException("Pending authorization request is invalid.");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record ScopeView(String scope, String description, boolean newlyRequested) { }

    public record ConsentReview(long registeredClientId, String clientId, String clientDisplayName,
            List<ScopeView> requestedScopes, List<ScopeView> newlyRequestedScopes) {
        public ConsentReview {
            requestedScopes = List.copyOf(requestedScopes);
            newlyRequestedScopes = List.copyOf(newlyRequestedScopes);
        }
    }

    public record ApprovalDecision(String authorizationId, String serverStateHash,
            long accountId, long companyId, long userId, UUID sub,
            long registeredClientId, String clientId, Set<String> requestedScopes) {
        public ApprovalDecision {
            requestedScopes = Set.copyOf(requestedScopes);
        }
    }
}
