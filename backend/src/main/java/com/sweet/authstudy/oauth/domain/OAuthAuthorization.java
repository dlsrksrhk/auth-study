package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class OAuthAuthorization {

    public enum Status { ACTIVE, REVOKED }

    public record AuthorizationRequest(
            String redirectUri,
            Set<String> requestedScopes,
            String rpState,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce) {
        public AuthorizationRequest {
            redirectUri = OAuthRefreshToken.requireText(redirectUri, "redirectUri");
            requestedScopes = Set.copyOf(OAuthConsent.normalizeScopes(requestedScopes));
            codeChallenge = OAuthRefreshToken.requireText(codeChallenge, "codeChallenge");
            if (!codeChallenge.matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("codeChallenge must be a PKCE S256 challenge.");
            }
            if (!"S256".equals(codeChallengeMethod)) {
                throw new IllegalArgumentException("codeChallengeMethod must be S256.");
            }
        }
    }

    public record Attributes(String principalName, String authorizationRequestUri,
            AuthorizationRequest authorizationRequest) {
        public Attributes(String principalName, String authorizationRequestUri) {
            this(principalName, authorizationRequestUri, null);
        }

        public Attributes {
            principalName = OAuthRefreshToken.requireText(principalName, "principalName");
            authorizationRequestUri = OAuthRefreshToken.requireText(
                    authorizationRequestUri, "authorizationRequestUri");
        }
    }

    public record IdTokenEvidence(Instant issuedAt, Instant expiresAt) {
        public IdTokenEvidence {
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("ID token expiresAt must be after issuedAt.");
            }
        }
    }

    public static final class Ownership {
        private final long registeredClientId;
        private final UUID subject;
        private final long principalAccountId;
        private final long companyId;

        private Ownership(long registeredClientId, UUID subject, long principalAccountId, long companyId) {
            this.registeredClientId = registeredClientId;
            this.subject = subject;
            this.principalAccountId = principalAccountId;
            this.companyId = companyId;
        }

        public static Ownership verified(OAuthClient client, OAuthSubject subject,
                long principalAccountId, long accountCompanyId) {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(subject, "subject");
            if (client.id() == null || subject.id() == null) {
                throw new IllegalArgumentException("Authorization ownership requires persisted client and subject.");
            }
            if (subject.accountId() != principalAccountId) {
                throw new IllegalArgumentException("OAuth subject must belong to the principal account.");
            }
            if (client.companyId() != accountCompanyId) {
                throw new IllegalArgumentException("OAuth client must belong to the principal account company.");
            }
            return new Ownership(client.id(), subject.subject(), principalAccountId, accountCompanyId);
        }

        public long registeredClientId() { return registeredClientId; }
        public UUID subject() { return subject; }
        public long principalAccountId() { return principalAccountId; }
        public long companyId() { return companyId; }
    }

    private final String id;
    private final long registeredClientId;
    private final UUID subject;
    private final long principalAccountId;
    private final long companyId;
    private final String authorizationGrantType;
    private final Set<String> authorizedScopes;
    private final Attributes attributes;
    private final String serverStateHash;
    private final Instant authenticatedAt;
    private Status status;
    private String revocationReason;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private final IdTokenEvidence idTokenEvidence;
    private OAuthAuthorizationCode authorizationCode;
    private OAuthAccessToken accessToken;
    private OAuthRefreshToken refreshToken;

    private OAuthAuthorization(String id, long registeredClientId, UUID subject, long principalAccountId,
            long companyId, String authorizationGrantType, Set<String> authorizedScopes,
            Attributes attributes, String serverStateHash, Instant authenticatedAt, Status status,
            String revocationReason, Instant createdAt, Instant expiresAt, Instant revokedAt,
            IdTokenEvidence idTokenEvidence,
            OAuthAuthorizationCode authorizationCode, OAuthAccessToken accessToken,
            OAuthRefreshToken refreshToken) {
        this.id = OAuthRefreshToken.requireText(id, "id");
        this.registeredClientId = registeredClientId;
        this.subject = Objects.requireNonNull(subject, "subject");
        this.principalAccountId = principalAccountId;
        this.companyId = companyId;
        this.authorizationGrantType = OAuthRefreshToken.requireText(
                authorizationGrantType, "authorizationGrantType");
        this.authorizedScopes = Set.copyOf(OAuthConsent.normalizeScopes(authorizedScopes));
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        if (serverStateHash != null && !serverStateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("serverStateHash must be a lowercase SHA-256 hash.");
        }
        this.serverStateHash = serverStateHash;
        this.authenticatedAt = Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.revocationReason = revocationReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.revokedAt = revokedAt;
        this.idTokenEvidence = idTokenEvidence;
        this.authorizationCode = authorizationCode;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public static OAuthAuthorization create(String id, Ownership ownership, String authorizationGrantType,
            Set<String> authorizedScopes, Attributes attributes, String serverStateHash, Instant authenticatedAt,
            Instant createdAt, Instant expiresAt) {
        Objects.requireNonNull(ownership, "ownership");
        return new OAuthAuthorization(id, ownership.registeredClientId(), ownership.subject(),
                ownership.principalAccountId(), ownership.companyId(),
                authorizationGrantType, authorizedScopes, attributes, serverStateHash, authenticatedAt,
                Status.ACTIVE, null, createdAt, expiresAt, null, null, null, null, null);
    }

    public static OAuthAuthorization restore(String id, long registeredClientId, UUID subject,
            long principalAccountId, long companyId, String authorizationGrantType,
            Set<String> authorizedScopes, Attributes attributes, String serverStateHash, Instant authenticatedAt,
            Status status, String revocationReason, Instant createdAt, Instant expiresAt,
            Instant revokedAt, OAuthAuthorizationCode authorizationCode, OAuthAccessToken accessToken,
            OAuthRefreshToken refreshToken) {
        return restore(id, registeredClientId, subject, principalAccountId, companyId,
                authorizationGrantType, authorizedScopes, attributes, serverStateHash, authenticatedAt,
                status, revocationReason, createdAt, expiresAt, revokedAt, null,
                authorizationCode, accessToken, refreshToken);
    }

    public static OAuthAuthorization restore(String id, long registeredClientId, UUID subject,
            long principalAccountId, long companyId, String authorizationGrantType,
            Set<String> authorizedScopes, Attributes attributes, String serverStateHash, Instant authenticatedAt,
            Status status, String revocationReason, Instant createdAt, Instant expiresAt,
            Instant revokedAt, IdTokenEvidence idTokenEvidence,
            OAuthAuthorizationCode authorizationCode, OAuthAccessToken accessToken,
            OAuthRefreshToken refreshToken) {
        return new OAuthAuthorization(id, registeredClientId, subject, principalAccountId, companyId,
                authorizationGrantType, authorizedScopes, attributes, serverStateHash, authenticatedAt, status,
                revocationReason, createdAt, expiresAt, revokedAt, idTokenEvidence,
                authorizationCode, accessToken, refreshToken);
    }

    public void attachAuthorizationCode(OAuthAuthorizationCode code) {
        requireSameAuthorization(code.authorizationId());
        authorizationCode = code;
    }

    public void attachAccessToken(OAuthAccessToken token) {
        requireSameAuthorization(token.authorizationId());
        accessToken = token;
    }

    public void attachRefreshToken(OAuthRefreshToken token) {
        requireSameAuthorization(token.authorizationId());
        refreshToken = token;
    }

    public void revoke(String reason, Instant now) {
        OAuthRefreshToken.requireText(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (revokedAt == null) {
            status = Status.REVOKED;
            revocationReason = reason;
            revokedAt = now;
            if (accessToken != null) accessToken.revoke(now);
            if (refreshToken != null) refreshToken.revoke(now);
        }
    }

    public boolean expiredAt(Instant now) { return !expiresAt.isAfter(Objects.requireNonNull(now)); }
    public boolean activeAt(Instant now) { return status == Status.ACTIVE && revokedAt == null && !expiredAt(now); }

    private void requireSameAuthorization(String authorizationId) {
        if (!id.equals(authorizationId)) {
            throw new IllegalArgumentException("Token metadata belongs to another authorization.");
        }
    }

    public String id() { return id; }
    public long registeredClientId() { return registeredClientId; }
    public UUID subject() { return subject; }
    public long principalAccountId() { return principalAccountId; }
    public long companyId() { return companyId; }
    public String authorizationGrantType() { return authorizationGrantType; }
    public Set<String> authorizedScopes() { return authorizedScopes; }
    public Attributes attributes() { return attributes; }
    public String serverStateHash() { return serverStateHash; }
    public Instant authenticatedAt() { return authenticatedAt; }
    public Status status() { return status; }
    public String revocationReason() { return revocationReason; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant revokedAt() { return revokedAt; }
    public Optional<IdTokenEvidence> idTokenEvidence() { return Optional.ofNullable(idTokenEvidence); }
    public Optional<OAuthAuthorizationCode> authorizationCode() { return Optional.ofNullable(authorizationCode); }
    public Optional<OAuthAccessToken> accessToken() { return Optional.ofNullable(accessToken); }
    public Optional<OAuthRefreshToken> refreshToken() { return Optional.ofNullable(refreshToken); }
}
