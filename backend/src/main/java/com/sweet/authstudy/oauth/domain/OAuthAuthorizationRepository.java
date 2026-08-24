package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAuthorizationRepository {
    OAuthAuthorization save(OAuthAuthorization authorization);
    Optional<OAuthAuthorization> findById(String id);
    Optional<OAuthAuthorizationCode> findByCodeHashForUpdate(String codeHash);
    Optional<OAuthAccessToken> findByAccessTokenHash(String accessTokenHash);
    Optional<OAuthRefreshToken> findRefreshByHashForUpdate(String refreshTokenHash);
    OAuthAuthorizationCode saveAuthorizationCode(OAuthAuthorizationCode code);
    OAuthAccessToken saveAccessToken(OAuthAccessToken token);
    OAuthRefreshToken saveRefreshToken(OAuthRefreshToken token);
    void remove(String authorizationId);
    void revokeFamily(UUID familyId, Instant revokedAt);
    void revokeByAccountId(long accountId, Instant revokedAt);
    void revokeByClientId(long registeredClientId, Instant revokedAt);
}
