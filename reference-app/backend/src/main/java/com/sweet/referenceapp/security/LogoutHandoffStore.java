package com.sweet.referenceapp.security;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.web.util.UriComponentsBuilder;

public final class LogoutHandoffStore implements AutoCloseable {
    private final URI endpoint;
    private final URI redirect;
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new HashMap<>();
    private final ScheduledExecutorService cleanup;
    private boolean closed;
    // Deliberately not a record: generated toString must never expose the payload.
    private static final class Entry {
        final URI location;
        final Instant expires;
        Entry(URI location, Instant expires) { this.location = location; this.expires = expires; }
    }
    public LogoutHandoffStore(URI endpoint, URI redirect, Clock clock, Duration ttl, int capacity) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(60)) > 0 || capacity < 1 || capacity > 1000)
            throw new IllegalArgumentException("Invalid logout handoff bounds");
        this.endpoint = endpoint;
        this.redirect = redirect;
        this.clock = clock;
        this.ttl = ttl;
        this.capacity = capacity;
        cleanup = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("logout-handoff-cleanup").factory());
        cleanup.scheduleAtFixedRate(this::purge, 1, 1, TimeUnit.SECONDS);
    }
    public synchronized String issue(String idToken, String clientId) {
        purge();
        if (closed || entries.size() >= capacity || idToken == null || idToken.isBlank() || clientId == null || clientId.isBlank())
            throw new IllegalStateException("Logout continuation unavailable");
        URI location = UriComponentsBuilder.fromUri(endpoint)
                .replaceQueryParam("id_token_hint", "{token}")
                .replaceQueryParam("client_id", "{client}")
                .replaceQueryParam("post_logout_redirect_uri", "{redirect}")
                .encode().buildAndExpand(idToken, clientId, redirect.toString()).toUri();
        String ticket;
        do {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (entries.containsKey(ticket));
        entries.put(ticket, new Entry(location, clock.instant().plus(ttl)));
        return ticket;
    }
    public synchronized Optional<URI> consume(String ticket) {
        purge();
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        Entry entry = entries.remove(ticket);
        return entry == null ? Optional.empty() : Optional.of(entry.location);
    }
    private synchronized void purge() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !now.isBefore(entry.expires));
    }
    public synchronized void close() {
        closed = true;
        entries.clear();
        cleanup.shutdownNow();
    }
}
