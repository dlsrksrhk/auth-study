package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.web.client.RestClient;

public final class OAuthSessionTokenService implements AutoCloseable {
    private final CloseableHttpClient http;
    private final RestClientRefreshTokenTokenResponseClient refreshClient;
    private final RestClient userInfoClient;
    private final OidcExternalIdentityMapper mapper;
    private final OAuthTokenRevoker revoker;

    public OAuthSessionTokenService(OAuthTokenLifecycleProperties properties, OidcExternalIdentityMapper mapper, OAuthTokenRevoker revoker) {
        this.mapper = mapper; this.revoker = revoker;
        var readTimeout = Timeout.ofMilliseconds(properties.readTimeout().toMillis());
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setSocketTimeout(readTimeout).build();
        var requestConfig = RequestConfig.custom().setResponseTimeout(readTimeout).build();
        http = HttpClients.custom().setDefaultRequestConfig(requestConfig)
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(connectionConfig).build())
                .disableAutomaticRetries().disableRedirectHandling().build();
        var factory = new HttpComponentsClientHttpRequestFactory(http);
        var tokenRestClient = RestClient.builder().requestFactory(factory)
                .messageConverters(converters -> { converters.clear(); converters.add(new FormHttpMessageConverter()); converters.add(new OAuth2AccessTokenResponseHttpMessageConverter()); })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
        refreshClient = new RestClientRefreshTokenTokenResponseClient();
        refreshClient.setRestClient(tokenRestClient);
        userInfoClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override public void close() throws Exception { http.close(); }

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
