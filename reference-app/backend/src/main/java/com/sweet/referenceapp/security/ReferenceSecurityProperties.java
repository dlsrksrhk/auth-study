package com.sweet.referenceapp.security;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reference.security")
public record ReferenceSecurityProperties(URI bffOrigin, URI spaOrigin) {
    public ReferenceSecurityProperties {
        validateOrigin(bffOrigin, "bff-origin");
        validateOrigin(spaOrigin, "spa-origin");
    }

    private static void validateOrigin(URI origin, String name) {
        if (origin == null || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getRawUserInfo() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || origin.toString().contains("*") || origin.getPort() == 0 || origin.getPort() > 65535
                || origin.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException(name + " must be an http(s) origin containing only scheme, host and optional port");
        }
    }

    public URI callbackUri() { return URI.create(bffOrigin + "/login/oauth2/code/reference-app"); }
    public URI successUri() { return URI.create(spaOrigin + "/"); }
    public URI failureUri() { return URI.create(spaOrigin + "/login-error?code=oidc_login_failed"); }
}
