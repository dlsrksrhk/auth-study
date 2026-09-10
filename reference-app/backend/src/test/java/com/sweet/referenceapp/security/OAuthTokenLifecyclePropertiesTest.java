package com.sweet.referenceapp.security;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OAuthTokenLifecyclePropertiesTest {
    @Test void rejectsUnsafeEndpointUris() {
        assertThatThrownBy(() -> properties("https://user@example.test/revoke")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("https://example.test/revoke#fragment")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("/relative")).isInstanceOf(IllegalArgumentException.class);
    }

    private static OAuthTokenLifecycleProperties properties(String uri) {
        return new OAuthTokenLifecycleProperties(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(10),
                Duration.ofSeconds(3), Duration.ofSeconds(60), 1000, URI.create(uri), URI.create("https://example.test/logout"));
    }
}
