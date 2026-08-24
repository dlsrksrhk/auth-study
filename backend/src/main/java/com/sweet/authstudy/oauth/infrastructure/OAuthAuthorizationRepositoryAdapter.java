package com.sweet.authstudy.oauth.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCodeExchangeBinding;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthAuthorizationRepositoryAdapter implements OAuthAuthorizationRepository {

    /** Maximum time from token issuance to the atomic finalization commit. */
    private static final Duration TOKEN_FINALIZATION_WINDOW = Duration.ofSeconds(30);

    private final OAuthAuthorizationJpaRepository authorizations;
    private final OAuthAuthorizationCodeJpaRepository codes;
    private final OAuthAccessTokenJpaRepository accessTokens;
    private final OAuthRefreshTokenJpaRepository refreshTokens;
    private final OAuthClientJpaRepository clients;
    private final OAuthConsentJpaRepository consents;
    private final CompanyRepository companies;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final OAuthSecurityProperties properties;

    public OAuthAuthorizationRepositoryAdapter(OAuthAuthorizationJpaRepository authorizations,
            OAuthAuthorizationCodeJpaRepository codes, OAuthAccessTokenJpaRepository accessTokens,
            OAuthRefreshTokenJpaRepository refreshTokens, OAuthClientJpaRepository clients,
            OAuthConsentJpaRepository consents,
            CompanyRepository companies, AccountRepository accounts, UserRepository users,
            OAuthSecurityProperties properties) {
        this.authorizations = authorizations;
        this.codes = codes;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clients = clients;
        this.consents = consents;
        this.companies = companies;
        this.accounts = accounts;
        this.users = users;
        this.properties = properties;
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
                accessTokens.findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(id)
                        .map(OAuthAccessTokenJpaEntity::toDomain).orElse(null),
                refreshTokens.findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(id)
                        .map(OAuthRefreshTokenJpaEntity::toDomain).orElse(null)));
    }

    @Override
    @Transactional
    public Optional<OAuthAuthorization> findByIdForUpdate(String id) {
        return authorizations.findByIdForUpdate(id).map(entity -> entity.toDomain(
                codes.findByAuthorizationId(id).map(OAuthAuthorizationCodeJpaEntity::toDomain).orElse(null),
                accessTokens.findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(id)
                        .map(OAuthAccessTokenJpaEntity::toDomain).orElse(null),
                refreshTokens.findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(id)
                        .map(OAuthRefreshTokenJpaEntity::toDomain).orElse(null)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthAuthorization> findByServerStateHash(String serverStateHash) {
        return authorizations.findByServerStateHash(serverStateHash).flatMap(entity -> findById(entity.toDomain(
                null, null, null).id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash) {
        return codes.findByCodeHash(codeHash).map(OAuthAuthorizationCodeJpaEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OAuthAuthorizationCode> findByCodeHashForUpdate(String codeHash) {
        return codes.findByCodeHashForUpdate(codeHash).map(OAuthAuthorizationCodeJpaEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> Optional<CodeConsumption<T>> consumeCodeAtomically(
            String codeHash, Instant consumedAt, Function<LockedCodeExchange, T> exchange) {
        java.util.Objects.requireNonNull(exchange, "exchange");
        return codes.findByCodeHashForUpdate(codeHash).map(entity -> {
            OAuthAuthorizationCode code = entity.toDomain();
            OAuthAuthorizationCode.Consumption consumption = code.consume(consumedAt);
            Optional<T> exchangeResult = Optional.empty();
            if (consumption == OAuthAuthorizationCode.Consumption.CONSUMED) {
                OAuthAuthorizationJpaEntity authorizationEntity = authorizations
                        .findByIdForUpdate(code.authorizationId())
                        .orElseThrow(() -> new IllegalStateException("Authorization does not exist for code."));
                OAuthAuthorization authorization = authorizationEntity.toDomain(code, null, null);
                OAuthClientJpaEntity clientEntity = clients.findByIdForUpdate(authorization.registeredClientId())
                        .orElseThrow(() -> new IllegalStateException("OAuth client does not exist for authorization."));
                boolean principalActive = lockAndValidatePrincipal(
                        authorization, clientEntity.toDomain(), consumedAt);
                exchangeResult = Optional.ofNullable(exchange.apply(
                        new LockedCodeExchange(code, authorization, clientEntity.toDomain(), principalActive)));
            }
            entity.updateFrom(code);
            codes.saveAndFlush(entity);
            return new CodeConsumption<>(consumption, exchangeResult);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CodeFinalizationResult finalizeAuthorizationCodeExchange(
            CodeFinalization finalization, Instant finalizedAt) {
        java.util.Objects.requireNonNull(finalization, "finalization");
        java.util.Objects.requireNonNull(finalizedAt, "finalizedAt");
        OAuthAuthorizationCodeJpaEntity codeEntity = codes
                .findByCodeHashForUpdate(finalization.consumedBinding().codeHash()).orElse(null);
        if (codeEntity == null) return CodeFinalizationResult.INVALID;
        OAuthAuthorizationCode code = codeEntity.toDomain();
        OAuthAuthorizationJpaEntity authorizationEntity = authorizations
                .findByIdForUpdate(code.authorizationId()).orElse(null);
        if (authorizationEntity == null) return CodeFinalizationResult.INVALID;
        OAuthAuthorization authorization = authorizationEntity.toDomain(code, null, null);
        OAuthClientJpaEntity clientEntity = clients
                .findByIdForUpdate(authorization.registeredClientId()).orElse(null);
        if (clientEntity == null) return CodeFinalizationResult.INVALID;
        OAuthClient client = clientEntity.toDomain();
        boolean principalActive = lockAndValidatePrincipal(authorization, client, finalizedAt);
        OAuthAuthorizationCodeExchangeBinding lockedBinding;
        try {
            lockedBinding = OAuthAuthorizationCodeExchangeBinding.captureLocked(code, authorization, client);
        } catch (IllegalArgumentException exception) {
            return CodeFinalizationResult.INVALID;
        }

        if (!finalization.consumedBinding().equals(finalization.candidateBinding())
                || !lockedBinding.equals(finalization.consumedBinding())
                || !validFinalization(finalization, finalizedAt, code, authorization, client, principalActive)
                || accessTokens.findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(authorization.id()).isPresent()
                || refreshTokens.findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(authorization.id()).isPresent()) {
            return CodeFinalizationResult.INVALID;
        }
        if (finalization.idTokenCandidate() != null) {
            authorizationEntity.recordIdTokenEvidence(new OAuthAuthorization.IdTokenEvidence(
                    finalization.idTokenCandidate().issuedAt(),
                    finalization.idTokenCandidate().expiresAt()));
            authorizations.saveAndFlush(authorizationEntity);
        }
        accessTokens.saveAndFlush(OAuthAccessTokenJpaEntity.from(finalization.accessToken()));
        if (finalization.refreshToken() != null) {
            refreshTokens.saveAndFlush(OAuthRefreshTokenJpaEntity.from(finalization.refreshToken()));
        }
        return CodeFinalizationResult.FINALIZED;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> RefreshRotation<T> rotateRefreshAtomically(
            String refreshTokenHash, Instant exchangedAt,
            Function<LockedRefreshExchange, Optional<RefreshSuccess<T>>> exchange) {
        return rotateRefreshAtomically(refreshTokenHash, exchangedAt, exchange, ignored -> { });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> RefreshRotation<T> rotateRefreshAtomically(
            String refreshTokenHash, Instant exchangedAt,
            Function<LockedRefreshExchange, Optional<RefreshSuccess<T>>> exchange,
            java.util.function.Consumer<RefreshSuccess<T>> afterPersistence) {
        java.util.Objects.requireNonNull(exchange, "exchange");
        java.util.Objects.requireNonNull(afterPersistence, "afterPersistence");
        String candidateAuthorizationId = refreshTokens
                .findAuthorizationIdByRefreshTokenHash(refreshTokenHash).orElse(null);
        if (candidateAuthorizationId == null) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }
        // Every generation in one grant shares this sentinel. It must precede refresh/auth row locks.
        authorizations.lockGrantScope(candidateAuthorizationId);
        OAuthRefreshTokenJpaEntity currentEntity = refreshTokens
                .findByRefreshTokenHashForUpdate(refreshTokenHash).orElse(null);
        if (currentEntity == null) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }
        OAuthRefreshToken current = currentEntity.toDomain();
        if (current.usedAt() != null) {
            refreshTokens.revokeFamily(current.familyId(), exchangedAt);
            return new RefreshRotation<>(RefreshRotationStatus.REUSED, Optional.empty());
        }
        if (current.revokedAt() != null || current.expiredAt(exchangedAt)) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }

        OAuthAuthorizationJpaEntity authorizationEntity = authorizations
                .findByIdForUpdate(current.authorizationId()).orElse(null);
        if (authorizationEntity == null) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }
        OAuthAccessToken previousAccess = accessTokens
                .findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(current.authorizationId())
                .map(OAuthAccessTokenJpaEntity::toDomain).orElse(null);
        OAuthAuthorization authorization = authorizationEntity.toDomain(null, previousAccess, current);
        OAuthClientJpaEntity clientEntity = clients.findByIdForUpdate(authorization.registeredClientId())
                .orElse(null);
        if (clientEntity == null) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }
        OAuthClient client = clientEntity.toDomain();
        boolean principalActive = lockAndValidatePrincipal(authorization, client, exchangedAt);
        boolean consentActive = client.trust() == OAuthClientTrust.TRUSTED_FIRST_PARTY
                || consents.findForUpdate(
                        authorization.principalAccountId(), authorization.registeredClientId())
                        .map(consent -> consent.toDomain().scopes()
                                .containsAll(current.authorizedScopes()))
                        .orElse(false);
        Optional<RefreshSuccess<T>> generated = exchange.apply(
                new LockedRefreshExchange(
                        current, authorization, client, principalActive, consentActive));
        if (generated.isEmpty()) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }

        RefreshSuccess<T> success = generated.orElseThrow();
        if (!validRefreshSuccess(success, current, authorization, exchangedAt)) {
            return new RefreshRotation<>(RefreshRotationStatus.INVALID, Optional.empty());
        }
        OAuthRefreshToken successor = refreshTokens
                .saveAndFlush(OAuthRefreshTokenJpaEntity.from(success.successor())).toDomain();
        current.markUsed(exchangedAt, successor);
        currentEntity.updateFrom(current);
        refreshTokens.saveAndFlush(currentEntity);
        accessTokens.saveAndFlush(OAuthAccessTokenJpaEntity.from(success.accessToken()));
        afterPersistence.accept(success);
        return new RefreshRotation<>(RefreshRotationStatus.ROTATED, Optional.of(success.result()));
    }

    private boolean validRefreshSuccess(RefreshSuccess<?> success, OAuthRefreshToken current,
            OAuthAuthorization authorization, Instant exchangedAt) {
        OAuthAccessToken accessToken = success.accessToken();
        OAuthRefreshToken successor = success.successor();
        return authorization.activeAt(exchangedAt)
                && authorization.id().equals(accessToken.authorizationId())
                && authorization.id().equals(successor.authorizationId())
                && current.familyId().equals(successor.familyId())
                && current.expiresAt().equals(successor.expiresAt())
                && current.authorizedScopes().containsAll(successor.authorizedScopes())
                && successor.authorizedScopes().equals(accessToken.authorizedScopes())
                && !successor.issuedAt().isBefore(exchangedAt)
                && successor.issuedAt().isBefore(successor.expiresAt())
                && accessToken.issuedAt().equals(successor.issuedAt())
                && accessToken.expiresAt().equals(accessToken.issuedAt().plus(properties.accessTokenTtl()))
                && !accessToken.expiresAt().isAfter(authorization.expiresAt());
    }

    private boolean validFinalization(CodeFinalization finalization, Instant finalizedAt,
            OAuthAuthorizationCode code, OAuthAuthorization authorization,
            OAuthClient client, boolean principalActive) {
        OAuthAuthorization.AuthorizationRequest request = authorization.attributes().authorizationRequest();
        boolean secretActive = client.publicClient()
                ? finalization.authenticatedSecretHash() == null
                : finalization.authenticatedSecretHash() != null && client.secrets().stream().anyMatch(secret ->
                        finalization.authenticatedSecretHash().equals(secret.secretHash())
                                && secret.revokedAt() == null
                                && (secret.expiresAt() == null || secret.expiresAt().isAfter(finalizedAt)));
        boolean openid = authorization.authorizedScopes().contains("openid");
        OAuthAuthorizationRepository.IdTokenCandidate idToken = finalization.idTokenCandidate();
        boolean accessTokenTimeValid = validTokenTime(
                finalization.accessToken().issuedAt(), finalization.accessToken().expiresAt(),
                properties.accessTokenTtl(), code.usedAt(), finalizedAt, authorization.expiresAt());
        boolean idTokenValid = openid
                ? idToken != null
                    && code.usedAt() != null
                    && authorization.idTokenEvidence().isEmpty()
                    && authorization.subject().toString().equals(idToken.subject())
                    && idToken.audiences().equals(java.util.Set.of(client.clientId()))
                    && idToken.issuedAt().equals(finalization.accessToken().issuedAt())
                    && validTokenTime(idToken.issuedAt(), idToken.expiresAt(), properties.idTokenTtl(),
                            code.usedAt(), finalizedAt, authorization.expiresAt())
                : idToken == null && authorization.idTokenEvidence().isEmpty();
        return code.usedAt() != null
                && authorization.id().equals(finalization.consumedBinding().authorizationId())
                && authorization.registeredClientId() == finalization.consumedBinding().registeredClientId()
                && client.id() == finalization.consumedBinding().registeredClientId()
                && client.status() == OAuthClientStatus.ACTIVE
                && authorization.companyId() == client.companyId()
                && authorization.activeAt(finalizedAt)
                && principalActive
                && secretActive
                && accessTokenTimeValid
                && idTokenValid
                && request != null
                && client.allowsRedirect(URI.create(request.redirectUri()))
                && code.redirectUri().toString().equals(request.redirectUri())
                && code.codeChallenge().equals(request.codeChallenge())
                && java.util.Objects.equals(code.nonce(), request.nonce())
                && "S256".equals(request.codeChallengeMethod())
                && client.scopes().containsAll(authorization.authorizedScopes())
                && request.requestedScopes().containsAll(authorization.authorizedScopes())
                && finalization.accessTokenScopes().equals(authorization.authorizedScopes())
                && finalization.accessToken().authorizedScopes().equals(finalization.accessTokenScopes())
                && authorization.id().equals(finalization.accessToken().authorizationId())
                && (finalization.refreshToken() == null
                    || authorization.id().equals(finalization.refreshToken().authorizationId())
                    && finalization.refreshToken().authorizedScopes().equals(finalization.accessTokenScopes()));
    }

    private boolean validTokenTime(Instant issuedAt, Instant expiresAt, Duration timeToLive,
            Instant consumedAt, Instant finalizedAt, Instant authorizationExpiresAt) {
        return consumedAt != null
                && !issuedAt.isBefore(consumedAt)
                && !issuedAt.isAfter(finalizedAt)
                && !issuedAt.isBefore(finalizedAt.minus(TOKEN_FINALIZATION_WINDOW))
                && expiresAt.equals(issuedAt.plus(timeToLive))
                && expiresAt.isAfter(finalizedAt)
                && !expiresAt.isAfter(authorizationExpiresAt);
    }

    private boolean lockAndValidatePrincipal(
            OAuthAuthorization authorization, OAuthClient client, Instant checkedAt) {
        Company company = companies.findLockedById(authorization.companyId()).orElse(null);
        Account account = accounts.findByIdForUpdate(authorization.principalAccountId()).orElse(null);
        HrUser user = account == null || account.userId() == null
                ? null : users.findByIdForUpdate(account.userId()).orElse(null);
        return company != null
                && account != null
                && user != null
                && company.status() == CompanyStatus.ACTIVE
                && account.status() == AccountStatus.ACTIVE
                && !account.mustChangePassword()
                && (account.lockedUntil() == null || !account.lockedUntil().isAfter(checkedAt))
                && user.status() == UserStatus.ACTIVE
                && java.util.Objects.equals(account.companyId(), authorization.companyId())
                && java.util.Objects.equals(account.userId(), user.id())
                && user.companyId() == authorization.companyId()
                && client.companyId() == authorization.companyId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthAccessToken> findByAccessTokenHash(String accessTokenHash) {
        return accessTokens.findByAccessTokenHash(accessTokenHash).map(OAuthAccessTokenJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthRefreshToken> findByRefreshTokenHash(String refreshTokenHash) {
        return refreshTokens.findByRefreshTokenHash(refreshTokenHash)
                .map(OAuthRefreshTokenJpaEntity::toDomain);
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
    public void revokeAuthorization(String authorizationId, Instant revokedAt) {
        revokeAuthorization(authorizationId, revokedAt, () -> { });
    }

    @Override
    @Transactional
    public void revokeAuthorization(String authorizationId, Instant revokedAt, Runnable afterRevocation) {
        java.util.Objects.requireNonNull(afterRevocation, "afterRevocation");
        lockGrantScopes(java.util.List.of(authorizationId));
        accessTokens.revokeByAuthorizationId(authorizationId, revokedAt);
        refreshTokens.revokeByAuthorizationId(authorizationId, revokedAt);
        authorizations.revokeById(authorizationId, "RP_REVOKED", revokedAt);
        afterRevocation.run();
    }

    @Override
    @Transactional
    public void revokeFamily(UUID familyId, Instant revokedAt) {
        lockGrantScopes(refreshTokens.findAuthorizationIdsByFamilyId(familyId));
        refreshTokens.revokeFamily(familyId, revokedAt);
    }

    @Override
    @Transactional
    public void lockByAccountId(long accountId) {
        lockGrantScopes(authorizations.findIdsByAccountId(accountId));
    }

    @Override
    @Transactional
    public void lockByCompanyId(long companyId) {
        lockGrantScopes(authorizations.findIdsByCompanyId(companyId));
    }

    @Override
    @Transactional
    public void lockByClientId(long registeredClientId) {
        lockGrantScopes(authorizations.findIdsByClientId(registeredClientId));
    }

    @Override
    @Transactional
    public void revokeByAccountId(long accountId, Instant revokedAt) {
        lockByAccountId(accountId);
        accessTokens.revokeByAccountId(accountId, revokedAt);
        refreshTokens.revokeByAccountId(accountId, revokedAt);
        authorizations.revokeByAccountId(accountId, "ACCOUNT_REVOKED", revokedAt);
    }

    @Override
    @Transactional
    public void revokeByCompanyId(long companyId, Instant revokedAt) {
        lockByCompanyId(companyId);
        accessTokens.revokeByCompanyId(companyId, revokedAt);
        refreshTokens.revokeByCompanyId(companyId, revokedAt);
        authorizations.revokeByCompanyId(companyId, "COMPANY_REVOKED", revokedAt);
    }

    @Override
    @Transactional
    public void revokeByClientId(long registeredClientId, Instant revokedAt) {
        lockByClientId(registeredClientId);
        accessTokens.revokeByClientId(registeredClientId, revokedAt);
        refreshTokens.revokeByClientId(registeredClientId, revokedAt);
        authorizations.revokeByClientId(registeredClientId, "CLIENT_REVOKED", revokedAt);
    }

    @Override
    @Transactional
    public void revokeByAccountIdAndClientId(
            long accountId, long registeredClientId, Instant revokedAt) {
        lockGrantScopes(authorizations.findIdsByAccountIdAndClientId(accountId, registeredClientId));
        accessTokens.revokeByAccountIdAndClientId(accountId, registeredClientId, revokedAt);
        refreshTokens.revokeByAccountIdAndClientId(accountId, registeredClientId, revokedAt);
        authorizations.revokeByAccountIdAndClientId(
                accountId, registeredClientId, "CONSENT_REVOKED", revokedAt);
    }

    private void lockGrantScopes(java.util.List<String> authorizationIds) {
        java.util.List<String> ordered = authorizationIds.stream().distinct().sorted().toList();
        ordered.forEach(authorizations::lockGrantScope);
        if (ordered.isEmpty()) return;
        refreshTokens.lockByAuthorizationIds(ordered);
        authorizations.lockByIds(ordered);
    }
}
