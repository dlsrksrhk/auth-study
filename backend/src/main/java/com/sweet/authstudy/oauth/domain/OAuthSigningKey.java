package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class OAuthSigningKey {

    public static final String RS256 = "RS256";

    public enum Status {ACTIVE, VERIFICATION_ONLY}

    private final Long id;
    private final String kid;
    private final String algorithm;
    private final String publicJwk;
    private final byte[] encryptedPrivateMaterial;
    private final Status status;
    private final Instant activatedAt;
    private final Instant retiredAt;

    private OAuthSigningKey(Long id, String kid, String algorithm, String publicJwk,
                            byte[] encryptedPrivateMaterial, Status status, Instant activatedAt, Instant retiredAt) {
        this.id = id;
        this.kid = requireText(kid, "kid");
        this.algorithm = requireText(algorithm, "algorithm");
        if (!RS256.equals(this.algorithm)) {
            throw new IllegalArgumentException("OAuth signing algorithm must be RS256.");
        }
        this.publicJwk = requireText(publicJwk, "publicJwk");
        Objects.requireNonNull(encryptedPrivateMaterial, "encryptedPrivateMaterial");
        if (encryptedPrivateMaterial.length == 0) {
            throw new IllegalArgumentException("encryptedPrivateMaterial must not be empty.");
        }
        this.encryptedPrivateMaterial = Arrays.copyOf(
                encryptedPrivateMaterial, encryptedPrivateMaterial.length);
        this.status = Objects.requireNonNull(status, "status");
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        this.retiredAt = retiredAt;
        if ((status == Status.ACTIVE && retiredAt != null)
                || (status == Status.VERIFICATION_ONLY && retiredAt == null)) {
            throw new IllegalArgumentException("OAuth signing key status and retiredAt do not match.");
        }
        if (retiredAt != null && retiredAt.isBefore(activatedAt)) {
            throw new IllegalArgumentException("retiredAt must not be before activatedAt.");
        }
    }

    public static OAuthSigningKey active(String kid, String publicJwk,
                                         byte[] encryptedPrivateMaterial, Instant activatedAt) {
        return new OAuthSigningKey(null, kid, RS256, publicJwk, encryptedPrivateMaterial,
                Status.ACTIVE, activatedAt, null);
    }

    public static OAuthSigningKey restore(Long id, String kid, String algorithm, String publicJwk,
                                          byte[] encryptedPrivateMaterial, Status status, Instant activatedAt, Instant retiredAt) {
        return new OAuthSigningKey(id, kid, algorithm, publicJwk, encryptedPrivateMaterial,
                status, activatedAt, retiredAt);
    }

    public OAuthSigningKey retire(Instant at) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Only an active OAuth signing key can be retired.");
        }
        return new OAuthSigningKey(id, kid, algorithm, publicJwk, encryptedPrivateMaterial,
                Status.VERIFICATION_ONLY, activatedAt, Objects.requireNonNull(at, "at"));
    }

    public Long id() {
        return id;
    }

    public String kid() {
        return kid;
    }

    public String algorithm() {
        return algorithm;
    }

    public String publicJwk() {
        return publicJwk;
    }

    public byte[] encryptedPrivateMaterial() {
        return Arrays.copyOf(encryptedPrivateMaterial, encryptedPrivateMaterial.length);
    }

    public Status status() {
        return status;
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public Instant retiredAt() {
        return retiredAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
