package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.time.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LogoutHandoffStoreTest {
    private static final URI END = URI.create("https://idp.example/logout");
    private static final URI SPA = URI.create("https://app.example/logged-out");
    @Test void ticketIsOpaqueAndConsumedOnlyOnceWithEncodedFixedDestination() {
        try (var store = new LogoutHandoffStore(END, SPA, Clock.systemUTC(), Duration.ofSeconds(60), 1000)) {
            String ticket = store.issue("signed+id&token", "reference-client");
            assertThat(ticket).matches("[A-Za-z0-9_-]{43}");
            assertThat(store.consume(ticket)).contains(URI.create("https://idp.example/logout?id_token_hint=signed%2Bid%26token&client_id=reference-client&post_logout_redirect_uri=https%3A%2F%2Fapp.example%2Flogged-out"));
            assertThat(store.consume(ticket)).isEmpty();
            assertThat(store.consume("malformed")).isEmpty();
            assertThat(store.consume(null)).isEmpty();
        }
    }
    @Test void expiryBoundaryFreesCapacityWithoutStealingValidTickets() {
        var clock = new MutableClock();
        try (var store = new LogoutHandoffStore(END, SPA, clock, Duration.ofSeconds(60), 1)) {
            String first = store.issue("original", "client");
            clock.now = clock.now.plusSeconds(59);
            assertThatThrownBy(() -> store.issue("other", "client")).isInstanceOf(IllegalStateException.class);
            assertThat(store.consume(first)).isPresent();
            String next = store.issue("next", "client");
            clock.now = clock.now.plusSeconds(60);
            assertThat(store.consume(next)).isEmpty();
            assertThat(store.issue("new", "client")).isNotBlank();
        }
    }
    @Test void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        try (var store = new LogoutHandoffStore(END, SPA, Clock.systemUTC(), Duration.ofSeconds(60), 1000);
                var executor = Executors.newFixedThreadPool(16)) {
            String ticket = store.issue("original", "client");
            var start = new CountDownLatch(1);
            var futures = IntStream.range(0,16).mapToObj(i -> executor.submit(() -> { start.await(); return store.consume(ticket).isPresent(); })).toList();
            start.countDown();
            int winners = 0;
            for (var result : futures) if (result.get(5, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
    }
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-10T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
