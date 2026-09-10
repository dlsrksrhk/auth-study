package com.sweet.referenceapp.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.web.client.RestClient;

public final class OAuthTokenRevoker {
    private final OAuthTokenLifecycleProperties properties;
    public OAuthTokenRevoker(OAuthTokenLifecycleProperties properties) {
        this.properties = properties;
    }
    private CloseableHttpClient newClient() {
        var readTimeout = Timeout.ofMilliseconds(properties.readTimeout().toMillis());
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setSocketTimeout(readTimeout).build();
        var requestConfig = RequestConfig.custom().setResponseTimeout(readTimeout).build();
        return HttpClients.custom().setDefaultRequestConfig(requestConfig)
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(connectionConfig).build())
                .disableAutomaticRetries().disableRedirectHandling().build();
    }
    public void revoke(ClientRegistration registration, OAuth2RefreshToken token) {
        if (registration == null || token == null) return;
        var client = newClient();
        var operation = Thread.ofVirtual().unstarted(() -> revokeOnce(client, registration, token));
        operation.start();
        try {
            if (!operation.join(properties.revocationTimeout())) {
                client.close(CloseMode.IMMEDIATE);
                operation.interrupt();
            }
        } catch (InterruptedException interrupted) {
            client.close(CloseMode.IMMEDIATE);
            operation.interrupt();
            Thread.currentThread().interrupt();
        }
    }
    private void revokeOnce(CloseableHttpClient client, ClientRegistration registration, OAuth2RefreshToken token) {
        try (client) {
            var http = RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(client)).build();
            var credentials = encode(registration.getClientId()) + ":" + encode(registration.getClientSecret());
            var body = "token=" + encode(token.getTokenValue()) + "&token_type_hint=refresh_token";
            http.post().uri(properties.revocationUri())
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(body).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @Override public String toString() { return "OAuthTokenRevoker[endpoint=" + properties.revocationUri() + "]"; }
}
