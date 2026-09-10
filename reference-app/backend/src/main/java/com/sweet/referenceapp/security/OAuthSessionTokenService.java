package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.web.client.RestClient;

public final class OAuthSessionTokenService {
    private final RestClientRefreshTokenTokenResponseClient refreshClient;
    private final RestClient userInfoClient;
    private final OidcExternalIdentityMapper mapper;
    private final OAuthTokenRevoker revoker;

    public OAuthSessionTokenService(OAuthTokenLifecycleProperties properties, OidcExternalIdentityMapper mapper, OAuthTokenRevoker revoker) {
        this.mapper = mapper; this.revoker = revoker;
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        var tokenRestClient = RestClient.builder().requestFactory(factory)
                .messageConverters(converters -> { converters.clear(); converters.add(new FormHttpMessageConverter()); converters.add(new OAuth2AccessTokenResponseHttpMessageConverter()); })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
        refreshClient = new RestClientRefreshTokenTokenResponseClient();
        refreshClient.setRestClient(tokenRestClient);
        userInfoClient = RestClient.builder().requestFactory(factory).build();
    }

    public RefreshCandidate refresh(OAuth2AuthorizedClient current, AppOidcUser principal) {
        OAuth2AuthorizedClient successor = null;
        try {
            Objects.requireNonNull(current); Objects.requireNonNull(principal);
            var oldRefresh = Objects.requireNonNull(current.getRefreshToken());
            var result = refreshClient.getTokenResponse(new OAuth2RefreshTokenGrantRequest(
                    current.getClientRegistration(), current.getAccessToken(), oldRefresh));
            if (result.getRefreshToken() == null || Objects.equals(result.getRefreshToken().getTokenValue(), oldRefresh.getTokenValue())) {
                throw new IllegalStateException("Rotated refresh token missing");
            }
            successor = new OAuth2AuthorizedClient(current.getClientRegistration(), current.getPrincipalName(),
                    result.getAccessToken(), Objects.requireNonNull(result.getRefreshToken()));
            var bearerToken = successor.getAccessToken().getTokenValue();
            Map<String, Object> claims = userInfoClient.get().uri(current.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri())
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            var info = new OidcUserInfo(Objects.requireNonNull(claims));
            ExternalIdentityProfile profile = mapper.map(principal.getIdToken(), info);
            return new RefreshCandidate(successor, profile);
        } catch (Exception failure) {
            if (successor != null) revoker.revoke(successor.getClientRegistration(), successor.getRefreshToken());
            throw new IllegalStateException("OAuth token refresh failed");
        }
    }
}
