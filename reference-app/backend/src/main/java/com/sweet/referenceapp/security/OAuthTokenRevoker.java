package com.sweet.referenceapp.security;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

public final class OAuthTokenRevoker {
    private final OAuthTokenLifecycleProperties properties;
    private final HttpClient http;
    public OAuthTokenRevoker(OAuthTokenLifecycleProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public void revoke(ClientRegistration registration, OAuth2RefreshToken token) {
        if (registration == null || token == null) return;
        try {
            var credentials = encode(registration.getClientId()) + ":" + encode(registration.getClientSecret());
            var body = "token=" + encode(token.getTokenValue()) + "&token_type_hint=refresh_token";
            var request = HttpRequest.newBuilder(properties.revocationUri()).timeout(properties.revocationTimeout())
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return;
        } catch (Exception ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @Override public String toString() { return "OAuthTokenRevoker[endpoint=" + properties.revocationUri() + "]"; }
}
