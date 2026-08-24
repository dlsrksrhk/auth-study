package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;

public interface OAuthAuthorizationRepository {
    enum RefreshRotationStatus { ROTATED, REUSED, INVALID }

    record LockedRefreshExchange(
            OAuthRefreshToken current,
            OAuthAuthorization authorization,
            OAuthClient client,
            boolean principalActive,
            boolean consentActive) {
        public LockedRefreshExchange {
            java.util.Objects.requireNonNull(current, "current");
            java.util.Objects.requireNonNull(authorization, "authorization");
            java.util.Objects.requireNonNull(client, "client");
        }
    }

    record RefreshSuccess<T>(
            OAuthAccessToken accessToken,
            OAuthRefreshToken successor,
            T result) {
        public RefreshSuccess {
            java.util.Objects.requireNonNull(accessToken, "accessToken");
            java.util.Objects.requireNonNull(successor, "successor");
            java.util.Objects.requireNonNull(result, "result");
        }
    }

    record RefreshRotation<T>(RefreshRotationStatus status, Optional<T> result) {
        public RefreshRotation {
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(result, "result");
            if ((status == RefreshRotationStatus.ROTATED) != result.isPresent()) {
                throw new IllegalArgumentException("Only a rotated refresh exchange has a result.");
            }
        }
    }

    record CodeConsumption<T>(
            OAuthAuthorizationCode.Consumption consumption,
            Optional<T> exchangeResult) {
        public CodeConsumption {
            java.util.Objects.requireNonNull(consumption, "consumption");
            java.util.Objects.requireNonNull(exchangeResult, "exchangeResult");
        }
    }

    record LockedCodeExchange(
            OAuthAuthorizationCode code,
            OAuthAuthorization authorization,
            OAuthClient client,
            boolean principalActive) {
        public LockedCodeExchange {
            java.util.Objects.requireNonNull(code, "code");
            java.util.Objects.requireNonNull(authorization, "authorization");
            java.util.Objects.requireNonNull(client, "client");
        }
    }

    enum CodeFinalizationResult { FINALIZED, INVALID }

    record IdTokenCandidate(
            String subject,
            java.util.Set<String> audiences,
            Instant issuedAt,
            Instant expiresAt) {
        public IdTokenCandidate {
            subject = OAuthRefreshToken.requireText(subject, "ID token subject");
            audiences = java.util.Set.copyOf(java.util.Objects.requireNonNull(audiences, "audiences"));
            if (audiences.isEmpty() || audiences.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("ID token audience is required.");
            }
            java.util.Objects.requireNonNull(issuedAt, "issuedAt");
            java.util.Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("ID token expiresAt must be after issuedAt.");
            }
        }
    }

    record CodeFinalization(
            OAuthAuthorizationCodeExchangeBinding consumedBinding,
            OAuthAuthorizationCodeExchangeBinding candidateBinding,
            String authenticatedSecretHash,
            java.util.Set<String> accessTokenScopes,
            IdTokenCandidate idTokenCandidate,
            OAuthAccessToken accessToken,
            OAuthRefreshToken refreshToken) {
        public CodeFinalization(
                OAuthAuthorizationCodeExchangeBinding consumedBinding,
                OAuthAuthorizationCodeExchangeBinding candidateBinding,
                String authenticatedSecretHash,
                java.util.Set<String> accessTokenScopes,
                OAuthAccessToken accessToken,
                OAuthRefreshToken refreshToken) {
            this(consumedBinding, candidateBinding, authenticatedSecretHash,
                    accessTokenScopes, null, accessToken, refreshToken);
        }

        public CodeFinalization {
            java.util.Objects.requireNonNull(consumedBinding, "consumedBinding");
            java.util.Objects.requireNonNull(candidateBinding, "candidateBinding");
            accessTokenScopes = java.util.Set.copyOf(
                    java.util.Objects.requireNonNull(accessTokenScopes, "accessTokenScopes"));
            java.util.Objects.requireNonNull(accessToken, "accessToken");
        }
    }

    OAuthAuthorization save(OAuthAuthorization authorization);
    Optional<OAuthAuthorization> findById(String id);
    default Optional<OAuthAuthorization> findByIdForUpdate(String id) { return findById(id); }
    Optional<OAuthAuthorization> findByServerStateHash(String serverStateHash);
    Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash);
    Optional<OAuthAuthorizationCode> findByCodeHashForUpdate(String codeHash);
    /**
     * Locks code, parent authorization, and current client in that stable order and consumes the code
     * in an independent transaction. A confirmed invalid exchange must be returned as a value from
     * {@code exchange}; throwing rolls the independent transaction back.
     */
    <T> Optional<CodeConsumption<T>> consumeCodeAtomically(
            String codeHash, Instant consumedAt, Function<LockedCodeExchange, T> exchange);
    /**
     * Linearizes an authorization-code exchange at token persistence. Locks code, parent
     * authorization, current client, company, account, and user in that order, then merges only
     * issued token metadata onto the locked current aggregate. Future identity-to-OAuth revocation
     * must acquire OAuth scope locks before taking identity rows in the same application transaction.
     */
    CodeFinalizationResult finalizeAuthorizationCodeExchange(CodeFinalization finalization, Instant finalizedAt);
    /**
     * Locks refresh, parent authorization, client, company, account, and user in that order.
     * Reuse is returned as a value so family revocation commits before the protocol layer emits
     * {@code invalid_grant}.
     */
    default <T> RefreshRotation<T> rotateRefreshAtomically(
            String refreshTokenHash, Instant exchangedAt,
            Function<LockedRefreshExchange, Optional<RefreshSuccess<T>>> exchange) {
        return rotateRefreshAtomically(refreshTokenHash, exchangedAt, exchange, ignored -> { });
    }
    <T> RefreshRotation<T> rotateRefreshAtomically(
            String refreshTokenHash, Instant exchangedAt,
            Function<LockedRefreshExchange, Optional<RefreshSuccess<T>>> exchange,
            Consumer<RefreshSuccess<T>> afterPersistence);
    Optional<OAuthAccessToken> findByAccessTokenHash(String accessTokenHash);
    Optional<OAuthRefreshToken> findByRefreshTokenHash(String refreshTokenHash);
    Optional<OAuthRefreshToken> findRefreshByHashForUpdate(String refreshTokenHash);
    OAuthAuthorizationCode saveAuthorizationCode(OAuthAuthorizationCode code);
    OAuthAccessToken saveAccessToken(OAuthAccessToken token);
    OAuthRefreshToken saveRefreshToken(OAuthRefreshToken token);
    void remove(String authorizationId);
    /** Revokes one RP grant, including every access token and refresh generation it owns. */
    default void revokeAuthorization(String authorizationId, Instant revokedAt) {
        revokeAuthorization(authorizationId, revokedAt, () -> { });
    }
    void revokeAuthorization(String authorizationId, Instant revokedAt, Runnable afterRevocation);
    void revokeFamily(UUID familyId, Instant revokedAt);
    void lockByAccountId(long accountId);
    void lockByCompanyId(long companyId);
    void lockByClientId(long registeredClientId);
    void revokeByAccountId(long accountId, Instant revokedAt);
    void revokeByCompanyId(long companyId, Instant revokedAt);
    void revokeByClientId(long registeredClientId, Instant revokedAt);
    void revokeByAccountIdAndClientId(long accountId, long registeredClientId, Instant revokedAt);
}
