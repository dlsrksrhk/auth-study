package com.sweet.referenceapp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MockOidcIssuer implements AutoCloseable {
    static final String CLIENT_ID = "test-reference-client";
    static final String CLIENT_SECRET = "test-reference-secret";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger userInfoRequests = new AtomicInteger();
    private final AtomicInteger revocationRequests = new AtomicInteger();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    volatile Map<String, Object> userInfoClaims = defaultClaims();
    volatile int userInfoStatus = 200;
    volatile String subject = "external-user-1";
    volatile String nonce;
    volatile String fault = "valid";
    volatile Map<String, String> tokenForm = Map.of();
    volatile String clientAuthorization;
    volatile String userInfoAuthorization;
    volatile Map<String, String> revocationForm = Map.of();
    volatile CountDownLatch requestLatch = new CountDownLatch(0);

    MockOidcIssuer() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/openid-configuration", exchange -> respond(exchange, 200, Map.of(
                    "issuer", origin(), "authorization_endpoint", origin() + "/authorize",
                    "token_endpoint", origin() + "/token", "userinfo_endpoint", origin() + "/userinfo",
                    "jwks_uri", origin() + "/jwks", "response_types_supported", List.of("code"),
                    "subject_types_supported", List.of("public"),
                    "id_token_signing_alg_values_supported", List.of("RS256"))));
            server.createContext("/jwks", exchange -> respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
            server.createContext("/userinfo", this::userInfo);
            server.createContext("/token", this::token);
            server.createContext("/revoke", this::revoke);
            server.setExecutor(executor);
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> defaultClaims() {
        return Map.of("sub", "external-user-1", "name", "Reference User", "email", "user@example.test");
    }

    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    int tokenRequestCount() { return tokenRequests.get(); }
    int userInfoRequestCount() { return userInfoRequests.get(); }
    int revocationRequestCount() { return revocationRequests.get(); }
    void reset() {
        userInfoClaims = defaultClaims();
        userInfoStatus = 200;
        subject = "external-user-1";
        fault = "valid";
        nonce = null;
        tokenForm = Map.of();
        clientAuthorization = null;
        userInfoAuthorization = null;
        revocationForm = Map.of();
        requestLatch = new CountDownLatch(0);
        tokenRequests.set(0);
        userInfoRequests.set(0);
        revocationRequests.set(0);
    }

    private void userInfo(HttpExchange exchange) throws IOException {
        userInfoRequests.incrementAndGet();
        userInfoAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (fault.equals("userinfo-error")) {
            respond(exchange, 500, Map.of("error", "local_user_disabled"));
            return;
        }
        var claims = new LinkedHashMap<String, Object>(userInfoClaims);
        switch (fault) {
            case "numeric-name" -> claims.put("name", 42);
            case "numeric-email" -> claims.put("email", 42);
            case "numeric-sub" -> claims.put("sub", 42);
            case "missing-sub" -> claims.remove("sub");
            case "mismatched-sub" -> claims.put("sub", "other-user");
            default -> { }
        }
        respond(exchange, userInfoStatus, claims);
    }
    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        tokenForm = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        clientAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (fault.equals("token-delay")) {
            try { Thread.sleep(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
        if (fault.equals("token-error")) {
            respond(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        if (fault.equals("token-malformed")) {
            respondRaw(exchange, 200, "not-json");
            return;
        }
        if ("refresh_token".equals(tokenForm.get("grant_type"))) {
            if (fault.equals("refresh-missing-token")) {
                respond(exchange, 200, Map.of("access_token", "test-access-token-refreshed", "token_type", "Bearer", "expires_in", 300));
                return;
            }
            respond(exchange, 200, Map.of("access_token", "test-access-token-refreshed", "token_type", "Bearer", "expires_in", 300,
                    "refresh_token", "test-refresh-token-refreshed", "scope", "openid profile email"));
            return;
        }
        try {
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(origin()).subject(subject)
                    .audience(CLIENT_ID).expirationTime(Date.from(now.plusSeconds(300)))
                    .issueTime(Date.from(now.minusSeconds(300))).claim("nonce", nonce);
            switch (fault) {
                case "missing-nonce" -> claims.claim("nonce", null);
                case "wrong-nonce" -> claims.claim("nonce", "wrong-nonce");
                case "wrong-issuer" -> claims.issuer("https://other-issuer.example");
                case "wrong-audience" -> claims.audience("other-client");
                case "wrong-azp" -> claims.claim("azp", "other-client");
                case "missing-exp" -> claims.expirationTime(null);
                case "missing-iat" -> claims.issueTime(null);
                case "expired" -> claims.expirationTime(Date.from(now.minusSeconds(120)));
                case "future-iat" -> claims.issueTime(Date.from(now.plusSeconds(120)));
                case "within-skew" -> claims.expirationTime(Date.from(now.minusSeconds(20)));
                case "iat-within-skew" -> claims.issueTime(Date.from(now.plusSeconds(20)));
                default -> { }
            }
            var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(fault.equals("unknown-kid") ? "unknown-key" : signingKey.getKeyID()).build(), claims.build());
            token.sign(new RSASSASigner(fault.equals("wrong-signature") ? wrongKey : signingKey));
            respond(exchange, 200, Map.of("access_token", "test-access-token-" + tokenRequests.get(), "refresh_token", "test-refresh-token",
                    "token_type", "Bearer", "expires_in", 300, "scope", "openid profile email",
                    "id_token", token.serialize()));
        } catch (JOSEException exception) {
            throw new IOException(exception);
        }
    }

    private void revoke(HttpExchange exchange) throws IOException {
        revocationRequests.incrementAndGet();
        revocationForm = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        clientAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestLatch.countDown();
        if (fault.equals("revoke-delay")) {
            try { Thread.sleep(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
        if (fault.equals("revoke-redirect")) {
            exchange.getResponseHeaders().set("Location", origin() + "/revoke");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        respond(exchange, fault.equals("revoke-error") ? 500 : 200, Map.of());
    }

    private void respondRaw(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        var bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    static Map<String, String> parameters(String query) {
        var values = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            var parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
