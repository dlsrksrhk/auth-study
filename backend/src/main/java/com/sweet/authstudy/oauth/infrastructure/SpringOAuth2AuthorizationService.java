package com.sweet.authstudy.oauth.infrastructure;

import java.time.Clock;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.application.OAuthConsentService;
import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCodeExchangeBinding;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository.LockedCodeExchange;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@Service
@Primary
public final class SpringOAuth2AuthorizationService implements OAuth2AuthorizationService {

    static final String AUTHORIZATION_CODE_TYPE = "code";
    static final String STATE_TYPE = "state";
    private static final String CONSUMED_CODE_ATTRIBUTE =
            SpringOAuth2AuthorizationService.class.getName() + ".CONSUMED_CODE";
    private static final String AUTHORIZATION_SOURCE_ATTRIBUTE =
            SpringOAuth2AuthorizationService.class.getName() + ".AUTHORIZATION_SOURCE";

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthAuthorizationMapper mapper;
    private final Clock clock;
    private final OAuthConsentDecisionCoordinator consentCoordinator;
    private final OAuthConsentDecisionContext consentDecisions;
    private final OAuthProtocolEventService protocolEvents;

    public SpringOAuth2AuthorizationService(OAuthAuthorizationRepository authorizations,
            OAuthAuthorizationMapper mapper, Clock clock) {
        this(authorizations, mapper, clock, null, null, null);
    }

    @Autowired
    SpringOAuth2AuthorizationService(OAuthAuthorizationRepository authorizations,
            OAuthAuthorizationMapper mapper, Clock clock,
            OAuthConsentDecisionCoordinator consentCoordinator,
            OAuthConsentDecisionContext consentDecisions,
            OAuthProtocolEventService protocolEvents) {
        this.authorizations = authorizations;
        this.mapper = mapper;
        this.clock = clock;
        this.consentCoordinator = consentCoordinator;
        this.consentDecisions = consentDecisions;
        this.protocolEvents = protocolEvents;
    }

    @Override
    public void save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        CachedAuthorization consumed = cachedAuthorization();
        boolean codeFinalization = isAuthorizationCodeFinalization(authorization);
        try {
            if (codeFinalization) {
                if (consumed == null) throwInvalidGrant();
                OAuthAuthorizationRepository.CodeFinalization finalization = mapper.codeFinalization(
                        authorization, consumed.binding(), consumed.authenticatedSecretHash());
                if (!finalization.consumedBinding().equals(finalization.candidateBinding())) {
                    throwInvalidGrant();
                }
                if (authorizations.finalizeAuthorizationCodeExchange(
                        finalization, clock.instant())
                        != OAuthAuthorizationRepository.CodeFinalizationResult.FINALIZED) {
                    throwInvalidGrant();
                }
                recordCodeExchange(consumed.binding());
                return;
            }
            OAuthConsentService.ApprovalDecision staged = consentDecisions == null
                    ? null : consentDecisions.staged();
            if (staged != null) {
                try {
                    consentCoordinator.approve(authorization, staged);
                } catch (RuntimeException exception) {
                    throw authorizationRequestError(authorization, exception);
                }
                return;
            }
            if (consentDecisions != null && consentDecisions.validated() != null) {
                throw authorizationRequestError(authorization,
                        new IllegalArgumentException("Consent persistence was not staged."));
            }
            OAuthAuthorization existing = authorizations.findById(authorization.getId()).orElse(null);
            OAuthAuthorization saved = authorizations.save(mapper.toDomain(authorization, existing));
            recordAuthorizationSave(authorization, saved);
        } catch (IllegalArgumentException exception) {
            if (codeFinalization) throwInvalidGrant();
            throw exception;
        } finally {
            clearConsumedAuthorization();
            if (consentDecisions != null) consentDecisions.clear();
        }
    }

    @Override
    public void remove(org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        authorizations.remove(authorization.getId());
    }

    @Override
    public org.springframework.security.oauth2.server.authorization.OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return authorizations.findById(id)
                .map(value -> markPersisted(mapper.toSpring(value, null, null)))
                .orElse(null);
    }

    @Override
    public org.springframework.security.oauth2.server.authorization.OAuth2Authorization findByToken(
            String token, OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");
        if (tokenType == null) return findByAnyIndexedToken(token);
        String value = tokenType.getValue();
        if (AUTHORIZATION_CODE_TYPE.equals(value)) {
            CachedAuthorization cached = cachedAuthorization(token);
            if (cached != null) return cached.authorization();
        }
        if (STATE_TYPE.equals(value)) {
            return authorizations.findByServerStateHash(OAuthAuthorizationMapper.sha256(token))
                    .map(authorization -> markPersisted(mapper.toSpring(authorization, token, STATE_TYPE)))
                    .orElse(null);
        }
        String hash;
        if (AUTHORIZATION_CODE_TYPE.equals(value)
                || OAuth2TokenType.ACCESS_TOKEN.getValue().equals(value)
                || OAuth2TokenType.REFRESH_TOKEN.getValue().equals(value)) {
            hash = OAuthAuthorizationMapper.sha256(token);
        } else {
            return null;
        }
        Optional<String> authorizationId = switch (value) {
            case AUTHORIZATION_CODE_TYPE -> authorizations.findByCodeHash(hash)
                    .map(OAuthAuthorizationCode::authorizationId);
            case "access_token" -> authorizations.findByAccessTokenHash(hash)
                    .map(com.sweet.authstudy.oauth.domain.OAuthAccessToken::authorizationId);
            case "refresh_token" -> authorizations.findByRefreshTokenHash(hash)
                    .map(com.sweet.authstudy.oauth.domain.OAuthRefreshToken::authorizationId);
            default -> Optional.empty();
        };
        return authorizationId.flatMap(authorizations::findById)
                .map(authorization -> markPersisted(mapper.toSpring(authorization, token, value)))
                .orElse(null);
    }

    public <T> Optional<OAuthAuthorizationRepository.CodeConsumption<T>> consumeAuthorizationCode(
            String rawCode, Function<LockedCodeExchange, T> exchange) {
        Objects.requireNonNull(exchange, "exchange");
        return authorizations.consumeCodeAtomically(
                OAuthAuthorizationMapper.sha256(rawCode), clock.instant(), exchange);
    }

    public org.springframework.security.oauth2.server.authorization.OAuth2Authorization
            findByAccessTokenForUserInfo(String rawAccessToken) {
        Assert.hasText(rawAccessToken, "access token cannot be empty");
        String hash = OAuthAuthorizationMapper.sha256(rawAccessToken);
        return authorizations.findByAccessTokenHash(hash)
                .flatMap(token -> authorizations.findById(token.authorizationId())
                        .filter(authorization -> authorization.accessToken()
                                .map(current -> current.accessTokenHash().equals(hash))
                                .orElse(false)))
                .map(authorization -> mapper.toSpringForUserInfo(authorization, rawAccessToken))
                .map(this::markPersisted)
                .orElse(null);
    }

    public org.springframework.security.oauth2.server.authorization.OAuth2Authorization reconstructConsumedAuthorization(
            String rawCode, OAuthAuthorization authorization) {
        return mapper.toSpring(authorization, rawCode, AUTHORIZATION_CODE_TYPE, true);
    }

    public void cacheConsumedAuthorization(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization,
            String authenticatedSecretHash, OAuthAuthorizationCodeExchangeBinding binding) {
        clearConsumedAuthorization();
        Objects.requireNonNull(binding, "binding");
        if (!binding.equals(mapper.codeExchangeBinding(authorization))) {
            throw new IllegalArgumentException("Consumed authorization binding does not match reconstruction.");
        }
        RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
        attributes.setAttribute(CONSUMED_CODE_ATTRIBUTE,
                new CachedAuthorization(binding, authorization, authenticatedSecretHash),
                RequestAttributes.SCOPE_REQUEST);
    }

    private org.springframework.security.oauth2.server.authorization.OAuth2Authorization findByAnyIndexedToken(
            String token) {
        String hash = OAuthAuthorizationMapper.sha256(token);
        List<Match> matches = new ArrayList<>(4);
        authorizations.findByServerStateHash(hash)
                .ifPresent(value -> matches.add(new Match(value.id(), STATE_TYPE)));
        authorizations.findByCodeHash(hash).ifPresent(value ->
                matches.add(new Match(value.authorizationId(), AUTHORIZATION_CODE_TYPE)));
        authorizations.findByAccessTokenHash(hash).ifPresent(value ->
                matches.add(new Match(value.authorizationId(), OAuth2TokenType.ACCESS_TOKEN.getValue())));
        authorizations.findByRefreshTokenHash(hash).ifPresent(value ->
                matches.add(new Match(value.authorizationId(), OAuth2TokenType.REFRESH_TOKEN.getValue())));
        if (matches.size() != 1) return null;
        Match match = matches.getFirst();
        return authorizations.findById(match.authorizationId())
                .map(authorization -> markPersisted(mapper.toSpring(authorization, token, match.tokenType())))
                .orElse(null);
    }

    private record Match(String authorizationId, String tokenType) { }

    private CachedAuthorization cachedAuthorization(String rawCode) {
        CachedAuthorization cached = cachedAuthorization();
        return cached != null && cached.binding().codeHash().equals(OAuthAuthorizationMapper.sha256(rawCode))
                ? cached : null;
    }

    private CachedAuthorization cachedAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(CONSUMED_CODE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof CachedAuthorization cached ? cached : null;
    }

    private void clearConsumedAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.removeAttribute(CONSUMED_CODE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private void throwInvalidGrant() {
        throw new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_GRANT, "Invalid authorization code grant.", null));
    }

    private void recordAuthorizationSave(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization source,
            OAuthAuthorization saved) {
        if (protocolEvents == null) return;
        OAuth2AuthorizationRequest request = source.getAttribute(OAuth2AuthorizationRequest.class.getName());
        String clientId = request == null ? null : request.getClientId();
        OAuthProtocolEventService.Context context = new OAuthProtocolEventService.Context(
                clientId, saved.subject(), saved.principalAccountId(), saved.companyId(), saved.id());
        java.util.Set<String> eventScopes = saved.authorizedScopes().isEmpty() && request != null
                ? request.getScopes() : saved.authorizedScopes();
        OAuthProtocolEvent.Metadata metadata = OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                "endpoint", "AUTHORIZE", "response_type", "CODE",
                "scopes", eventScopes, "redirect_validated", true));
        if (source.getToken(
                org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class) != null) {
            protocolEvents.success(OAuthProtocolEvent.EventType.AUTHORIZATION_REQUESTED, context, metadata);
            protocolEvents.success(OAuthProtocolEvent.EventType.CODE_ISSUED, context, metadata);
        } else {
            protocolEvents.success(OAuthProtocolEvent.EventType.AUTHORIZATION_REQUESTED, context, metadata);
        }
    }

    private void recordCodeExchange(OAuthAuthorizationCodeExchangeBinding binding) {
        if (protocolEvents == null) return;
        authorizations.findById(binding.authorizationId()).ifPresent(authorization -> {
            OAuthProtocolEventService.Context context = new OAuthProtocolEventService.Context(
                    binding.authorizationRequest().clientId(), authorization.subject(),
                    authorization.principalAccountId(), authorization.companyId(), authorization.id());
            OAuthProtocolEvent.Metadata metadata = OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                    "endpoint", "TOKEN", "grant_type", "AUTHORIZATION_CODE",
                    "scopes", authorization.authorizedScopes()));
            protocolEvents.success(OAuthProtocolEvent.EventType.AUTHORIZATION_CODE_EXCHANGED, context, metadata);
            protocolEvents.success(OAuthProtocolEvent.EventType.TOKEN_ISSUED, context, metadata);
        });
    }

    private OAuth2AuthorizationCodeRequestAuthenticationException authorizationRequestError(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization,
            RuntimeException cause) {
        OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        Object sourcePrincipal = authorization.getAttribute(Principal.class.getName());
        Authentication principal = sourcePrincipal instanceof Authentication authentication
                ? authentication : SecurityContextHolder.getContext().getAuthentication();
        OAuth2AuthorizationCodeRequestAuthenticationToken token = request == null ? null
                : new OAuth2AuthorizationCodeRequestAuthenticationToken(
                        request.getAuthorizationUri(), request.getClientId(), principal,
                        request.getRedirectUri(), request.getState(), request.getScopes(),
                        request.getAdditionalParameters());
        return new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_REQUEST, "The consent decision is stale or invalid.", null),
                cause, token);
    }

    private boolean isAuthorizationCodeFinalization(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorization.getAuthorizationGrantType())
                || authorization.getAccessToken() == null && authorization.getRefreshToken() == null) {
            return false;
        }
        return authorization.getAttribute(AUTHORIZATION_SOURCE_ATTRIBUTE) != AuthorizationSource.PERSISTED;
    }

    private org.springframework.security.oauth2.server.authorization.OAuth2Authorization markPersisted(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        if (authorization == null) return null;
        // SAS refreshes via OAuth2Authorization.from(existing), retaining the original authorization-code
        // grant type. This request-local, non-persisted provenance keeps refresh saves on the normal path.
        return org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(authorization)
                .attribute(AUTHORIZATION_SOURCE_ATTRIBUTE, AuthorizationSource.PERSISTED)
                .build();
    }

    private enum AuthorizationSource { PERSISTED }

    private record CachedAuthorization(OAuthAuthorizationCodeExchangeBinding binding,
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization,
            String authenticatedSecretHash) { }
}
