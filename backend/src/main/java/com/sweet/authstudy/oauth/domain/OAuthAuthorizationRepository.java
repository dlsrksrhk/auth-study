package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public interface OAuthAuthorizationRepository {
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
            OAuthClient client) {
        public LockedCodeExchange {
            java.util.Objects.requireNonNull(code, "code");
            java.util.Objects.requireNonNull(authorization, "authorization");
            java.util.Objects.requireNonNull(client, "client");
        }
    }

    OAuthAuthorization save(OAuthAuthorization authorization);
    Optional<OAuthAuthorization> findById(String id);
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
    Optional<OAuthAccessToken> findByAccessTokenHash(String accessTokenHash);
    Optional<OAuthRefreshToken> findByRefreshTokenHash(String refreshTokenHash);
    Optional<OAuthRefreshToken> findRefreshByHashForUpdate(String refreshTokenHash);
    OAuthAuthorizationCode saveAuthorizationCode(OAuthAuthorizationCode code);
    OAuthAccessToken saveAccessToken(OAuthAccessToken token);
    OAuthRefreshToken saveRefreshToken(OAuthRefreshToken token);
    void remove(String authorizationId);
    void revokeFamily(UUID familyId, Instant revokedAt);
    void revokeByAccountId(long accountId, Instant revokedAt);
    void revokeByClientId(long registeredClientId, Instant revokedAt);
}
