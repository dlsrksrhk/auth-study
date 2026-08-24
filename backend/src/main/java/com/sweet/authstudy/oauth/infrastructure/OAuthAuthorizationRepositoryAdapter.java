package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthAuthorizationRepositoryAdapter implements OAuthAuthorizationRepository {

    private final OAuthAuthorizationJpaRepository authorizations;
    private final OAuthAuthorizationCodeJpaRepository codes;
    private final OAuthAccessTokenJpaRepository accessTokens;
    private final OAuthRefreshTokenJpaRepository refreshTokens;

    public OAuthAuthorizationRepositoryAdapter(OAuthAuthorizationJpaRepository authorizations,
            OAuthAuthorizationCodeJpaRepository codes, OAuthAccessTokenJpaRepository accessTokens,
            OAuthRefreshTokenJpaRepository refreshTokens) {
        this.authorizations = authorizations;
        this.codes = codes;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    @Override
    @Transactional
    public OAuthAuthorization save(OAuthAuthorization authorization) {
        OAuthAuthorizationJpaEntity entity = authorizations.findById(authorization.id()).orElse(null);
        if (entity == null) entity = OAuthAuthorizationJpaEntity.from(authorization);
        else entity.updateFrom(authorization);
        authorizations.saveAndFlush(entity);
        authorization.authorizationCode().ifPresent(this::saveAuthorizationCode);
        authorization.accessToken().ifPresent(this::saveAccessToken);
        authorization.refreshToken().ifPresent(this::saveRefreshToken);
        return findById(authorization.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthAuthorization> findById(String id) {
        return authorizations.findById(id).map(entity -> entity.toDomain(
                codes.findByAuthorizationId(id).map(OAuthAuthorizationCodeJpaEntity::toDomain).orElse(null),
                accessTokens.findFirstByAuthorizationIdOrderByIssuedAtDesc(id)
                        .map(OAuthAccessTokenJpaEntity::toDomain).orElse(null),
                refreshTokens.findFirstByAuthorizationIdOrderByIssuedAtDesc(id)
                        .map(OAuthRefreshTokenJpaEntity::toDomain).orElse(null)));
    }

    @Override
    public Optional<OAuthAuthorizationCode> findByCodeHashForUpdate(String codeHash) {
        return codes.findByCodeHashForUpdate(codeHash).map(OAuthAuthorizationCodeJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthAccessToken> findByAccessTokenHash(String accessTokenHash) {
        return accessTokens.findByAccessTokenHash(accessTokenHash).map(OAuthAccessTokenJpaEntity::toDomain);
    }

    @Override
    public Optional<OAuthRefreshToken> findRefreshByHashForUpdate(String refreshTokenHash) {
        return refreshTokens.findByRefreshTokenHashForUpdate(refreshTokenHash)
                .map(OAuthRefreshTokenJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public OAuthAuthorizationCode saveAuthorizationCode(OAuthAuthorizationCode code) {
        OAuthAuthorizationCodeJpaEntity entity = code.id() == null
                ? OAuthAuthorizationCodeJpaEntity.from(code)
                : codes.findById(code.id()).orElseThrow(() -> new IllegalStateException("Code does not exist."));
        if (code.id() != null) entity.updateFrom(code);
        return codes.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional
    public OAuthAccessToken saveAccessToken(OAuthAccessToken token) {
        OAuthAccessTokenJpaEntity entity = token.id() == null
                ? OAuthAccessTokenJpaEntity.from(token)
                : accessTokens.findById(token.id())
                        .orElseThrow(() -> new IllegalStateException("Access token does not exist."));
        if (token.id() != null) entity.updateFrom(token);
        return accessTokens.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional
    public OAuthRefreshToken saveRefreshToken(OAuthRefreshToken token) {
        OAuthRefreshTokenJpaEntity entity = token.id() == null
                ? OAuthRefreshTokenJpaEntity.from(token)
                : refreshTokens.findById(token.id())
                        .orElseThrow(() -> new IllegalStateException("Refresh token does not exist."));
        if (token.id() != null) entity.updateFrom(token);
        return refreshTokens.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional
    public void remove(String authorizationId) { authorizations.deleteById(authorizationId); }

    @Override
    @Transactional
    public void revokeFamily(UUID familyId, Instant revokedAt) {
        refreshTokens.revokeFamily(familyId, revokedAt);
    }

    @Override
    @Transactional
    public void revokeByAccountId(long accountId, Instant revokedAt) {
        accessTokens.revokeByAccountId(accountId, revokedAt);
        refreshTokens.revokeByAccountId(accountId, revokedAt);
        authorizations.revokeByAccountId(accountId, "ACCOUNT_REVOKED", revokedAt);
    }

    @Override
    @Transactional
    public void revokeByClientId(long registeredClientId, Instant revokedAt) {
        accessTokens.revokeByClientId(registeredClientId, revokedAt);
        refreshTokens.revokeByClientId(registeredClientId, revokedAt);
        authorizations.revokeByClientId(registeredClientId, "CLIENT_REVOKED", revokedAt);
    }
}
