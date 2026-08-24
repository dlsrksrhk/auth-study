package com.sweet.authstudy.oauth.presentation;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

public final class OAuthProtocolCorsConfigurationSource implements CorsConfigurationSource {

    private final OAuthClientRepository clients;
    private final String issuerOrigin;

    public OAuthProtocolCorsConfigurationSource(OAuthClientRepository clients, URI issuer) {
        this.clients = clients;
        this.issuerOrigin = origin(issuer);
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        // The canonical issuer Origin is same-origin browser traffic, not protocol CORS.
        if (issuerOrigin.equals(request.getHeader(HttpHeaders.ORIGIN))) return null;
        if (request.getRequestURI().equals("/oauth2/revoke")) {
            CorsConfiguration sameOrigin = new CorsConfiguration();
            sameOrigin.setAllowedOrigins(List.of(issuerOrigin));
            sameOrigin.setAllowedMethods(List.of(HttpMethod.POST.name()));
            sameOrigin.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
            sameOrigin.setAllowCredentials(false);
            return sameOrigin;
        }
        if (!corsEndpoint(request.getRequestURI())) return null;
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(activePublicOrigins().stream().toList());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        return configuration;
    }

    private Set<String> activePublicOrigins() {
        Set<String> origins = new LinkedHashSet<>();
        clients.findAll().stream()
                .filter(OAuthClient::publicClient)
                .filter(client -> client.status() == OAuthClientStatus.ACTIVE)
                .flatMap(client -> client.redirectUris().stream())
                .map(OAuthProtocolCorsConfigurationSource::origin)
                .forEach(origins::add);
        return origins;
    }

    private boolean corsEndpoint(String path) {
        return path.startsWith("/.well-known/")
                || path.equals("/oauth2/jwks")
                || path.equals("/oauth2/token");
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port < 0 || "http".equals(scheme) && port == 80
                || "https".equals(scheme) && port == 443;
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
