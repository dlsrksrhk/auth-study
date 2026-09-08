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

final class MockOidcIssuer implements AutoCloseable {
    static final String CLIENT_ID = "test-reference-client";
    static final String CLIENT_SECRET = "test-reference-secret";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    volatile String nonce;
    volatile String fault = "valid";
    volatile Map<String, String> tokenForm = Map.of();
    volatile String clientAuthorization;

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
            server.createContext("/userinfo", exchange -> respond(exchange, 200,
                    Map.of("sub", "external-user-1", "name", "Reference User", "email", "user@example.test")));
            server.createContext("/token", this::token);
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    int tokenRequestCount() { return tokenRequests.get(); }
    void reset() {
        fault = "valid";
        nonce = null;
        tokenForm = Map.of();
        clientAuthorization = null;
        tokenRequests.set(0);
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        tokenForm = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        clientAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (fault.equals("token-error")) {
            respond(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        try {
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(origin()).subject("external-user-1")
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

    @Override public void close() { server.stop(0); }
}
