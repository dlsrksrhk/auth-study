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
    private final CompanyRepository companies;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final OAuthSecurityProperties properties;

    public OAuthAuthorizationRepositoryAdapter(OAuthAuthorizationJpaRepository authorizations,
            OAuthAuthorizationCodeJpaRepository codes, OAuthAccessTokenJpaRepository accessTokens,
            OAuthRefreshTokenJpaRepository refreshTokens, OAuthClientJpaRepository clients,
            CompanyRepository companies, AccountRepository accounts, UserRepository users,
            OAuthSecurityProperties properties) {
        this.authorizations = authorizations;
        this.codes = codes;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clients = clients;
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
                && authorization.id().equals(finalization.accessToken().authorizationId())
                && (finalization.refreshToken() == null
                    || authorization.id().equals(finalization.refreshToken().authorizationId()));
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
