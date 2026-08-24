package com.sweet.authstudy.oauth.infrastructure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository.LockedCodeExchange;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
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

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthAuthorizationMapper mapper;
    private final Clock clock;

    public SpringOAuth2AuthorizationService(OAuthAuthorizationRepository authorizations,
            OAuthAuthorizationMapper mapper, Clock clock) {
        this.authorizations = authorizations;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        OAuthAuthorization existing = authorizations.findById(authorization.getId()).orElse(null);
        authorizations.save(mapper.toDomain(authorization, existing));
        clearConsumedAuthorization();
    }

    @Override
    public void remove(org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        authorizations.remove(authorization.getId());
    }

    @Override
    public org.springframework.security.oauth2.server.authorization.OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return authorizations.findById(id).map(value -> mapper.toSpring(value, null, null)).orElse(null);
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
                    .map(authorization -> mapper.toSpring(authorization, token, STATE_TYPE))
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
                .map(authorization -> mapper.toSpring(authorization, token, value))
                .orElse(null);
    }

    public <T> Optional<OAuthAuthorizationRepository.CodeConsumption<T>> consumeAuthorizationCode(
            String rawCode, Function<LockedCodeExchange, T> exchange) {
        Objects.requireNonNull(exchange, "exchange");
        return authorizations.consumeCodeAtomically(
                OAuthAuthorizationMapper.sha256(rawCode), clock.instant(), exchange);
    }

    public org.springframework.security.oauth2.server.authorization.OAuth2Authorization reconstructConsumedAuthorization(
            String rawCode, OAuthAuthorization authorization) {
        return mapper.toSpring(authorization, rawCode, AUTHORIZATION_CODE_TYPE, true);
    }

    public void cacheConsumedAuthorization(String rawCode,
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) {
        RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
        attributes.setAttribute(CONSUMED_CODE_ATTRIBUTE,
                new CachedAuthorization(OAuthAuthorizationMapper.sha256(rawCode), authorization),
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
                .map(authorization -> mapper.toSpring(authorization, token, match.tokenType()))
                .orElse(null);
    }

    private record Match(String authorizationId, String tokenType) { }

    private CachedAuthorization cachedAuthorization(String rawCode) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(CONSUMED_CODE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof CachedAuthorization cached
                && cached.codeHash().equals(OAuthAuthorizationMapper.sha256(rawCode))) {
            return cached;
        }
        return null;
    }

    private void clearConsumedAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.removeAttribute(CONSUMED_CODE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private record CachedAuthorization(String codeHash,
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization) { }
}
