package com.sweet.referenceapp.security;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reference.token-lifecycle")
public record OAuthTokenLifecycleProperties(Duration connectTimeout, Duration readTimeout, Duration refreshTimeout,
        Duration revocationTimeout, Duration handoffTtl, int handoffCapacity, URI revocationUri, URI endSessionUri) {
    public OAuthTokenLifecycleProperties {
        requirePositive(connectTimeout, "connect-timeout"); requirePositive(readTimeout, "read-timeout");
        requirePositive(refreshTimeout, "refresh-timeout"); requirePositive(revocationTimeout, "revocation-timeout");
        requirePositive(handoffTtl, "handoff-ttl");
        if (handoffCapacity <= 0) throw new IllegalArgumentException("handoff-capacity must be positive");
        validateEndpoint(revocationUri); validateEndpoint(endSessionUri);
    }
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
    }
    private static void validateEndpoint(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Unsafe OAuth endpoint URI");
        }
    }
}
