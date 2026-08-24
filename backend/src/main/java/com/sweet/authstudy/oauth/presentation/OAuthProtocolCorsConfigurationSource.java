package com.sweet.authstudy.oauth.presentation;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.sweet.authstudy.oauth.domain.OAuthPublicClientRedirectRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

public final class OAuthProtocolCorsConfigurationSource implements CorsConfigurationSource {

    private final OAuthPublicClientRedirectRepository redirects;
    private final String issuerOrigin;

    public OAuthProtocolCorsConfigurationSource(
            OAuthPublicClientRedirectRepository redirects, URI issuer) {
        this.redirects = redirects;
        this.issuerOrigin = origin(issuer)
                .orElseThrow(() -> new IllegalArgumentException("Issuer must have a valid HTTP origin."));
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
        redirects.findActivePublicAuthorizationRedirectUris().stream()
                .map(OAuthProtocolCorsConfigurationSource::origin)
                .flatMap(java.util.Optional::stream)
                .forEach(origins::add);
        return origins;
    }

    private boolean corsEndpoint(String path) {
        return path.startsWith("/.well-known/")
                || path.equals("/oauth2/jwks")
                || path.equals("/oauth2/token");
    }

    private static java.util.Optional<String> origin(String value) {
        try {
            return origin(URI.create(value));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<String> origin(URI uri) {
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            return java.util.Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return java.util.Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        int port = uri.getPort();
        boolean defaultPort = port < 0 || "http".equals(scheme) && port == 80
                || "https".equals(scheme) && port == 443;
        return java.util.Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port));
    }
}
