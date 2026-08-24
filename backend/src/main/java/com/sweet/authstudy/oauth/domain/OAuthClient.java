package com.sweet.authstudy.oauth.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public final class OAuthClient {

    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "openid", "profile", "email", "hr.company", "hr.organization", "hr.roles");

    private final Long id;
    private final long companyId;
    private final String clientId;
    private final boolean publicClient;
    private final Instant createdAt;
    private String displayName;
    private OAuthClientStatus status;
    private OAuthClientTrust trust;
    private long version;
    private Set<URI> redirectUris;
    private Set<URI> postLogoutRedirectUris;
    private Set<String> scopes;
    private Set<OAuthClientSecret> secrets;
    private Instant updatedAt;

    private OAuthClient(
            Long id,
            long companyId,
            String clientId,
            String displayName,
            OAuthClientStatus status,
            OAuthClientTrust trust,
            boolean publicClient,
            long version,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            Set<OAuthClientSecret> secrets,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.clientId = Objects.requireNonNull(clientId);
        this.displayName = Objects.requireNonNull(displayName);
        this.status = Objects.requireNonNull(status);
        this.trust = Objects.requireNonNull(trust);
        this.publicClient = publicClient;
        this.version = version;
        this.redirectUris = validatedRedirects(redirectUris);
        this.postLogoutRedirectUris = validatedRedirects(postLogoutRedirectUris);
        this.scopes = validatedScopes(scopes);
        this.secrets = Set.copyOf(Objects.requireNonNull(secrets));
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static OAuthClient create(
            long companyId,
            String clientId,
            String displayName,
            boolean publicClient,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            OAuthClientTrust trust,
            Instant now) {
        return new OAuthClient(
                null, companyId, clientId, displayName, OAuthClientStatus.ACTIVE, trust,
                publicClient, 0, redirectUris, postLogoutRedirectUris, scopes, Set.of(), now, now);
    }

    public static OAuthClient create(
            long companyId,
            String clientId,
            String displayName,
            boolean publicClient,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            OAuthClientTrust trust,
            Set<OAuthClientSecret> secrets,
            Instant now) {
        return new OAuthClient(
                null, companyId, clientId, displayName, OAuthClientStatus.ACTIVE, trust,
                publicClient, 0, redirectUris, postLogoutRedirectUris, scopes, secrets, now, now);
    }

    public static OAuthClient restore(
            Long id,
            long companyId,
            String clientId,
            String displayName,
            OAuthClientStatus status,
            OAuthClientTrust trust,
            boolean publicClient,
            long version,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            Set<OAuthClientSecret> secrets,
            Instant createdAt,
            Instant updatedAt) {
        return new OAuthClient(
                id, companyId, clientId, displayName, status, trust, publicClient, version,
                redirectUris, postLogoutRedirectUris, scopes, secrets, createdAt, updatedAt);
    }

    private static Set<URI> validatedRedirects(Set<URI> uris) {
        Set<URI> copy = Set.copyOf(Objects.requireNonNull(uris));
        copy.forEach(OAuthClient::validateRedirect);
        return copy;
    }

    private static void validateRedirect(URI uri) {
        if (uri.getFragment() != null || uri.getUserInfo() != null) {
            throw invalidRedirect();
        }
        boolean localHttp = "http".equals(uri.getScheme())
                && uri.getHost() != null && uri.getHost().endsWith(".localhost");
        if (!"https".equals(uri.getScheme()) && !localHttp) {
            throw invalidRedirect();
        }
    }

    private static IllegalArgumentException invalidRedirect() {
        return new IllegalArgumentException("Redirect URI must use HTTPS or an HTTP subdomain of localhost without user-info or fragment.");
    }

    private static Set<String> validatedScopes(Set<String> scopes) {
        Set<String> copy = Set.copyOf(Objects.requireNonNull(scopes));
        if (!copy.contains("openid") || !ALLOWED_SCOPES.containsAll(copy)) {
            throw new IllegalArgumentException("Scopes must include openid and use only supported values.");
        }
        return copy;
    }

    public boolean allowsRedirect(URI requested) {
        Objects.requireNonNull(requested);
        String exactValue = requested.toString();
        return redirectUris.stream().anyMatch(uri -> uri.toString().equals(exactValue));
    }

    public boolean allowsPostLogoutRedirect(URI requested) {
        Objects.requireNonNull(requested);
        String exactValue = requested.toString();
        return postLogoutRedirectUris.stream().anyMatch(uri -> uri.toString().equals(exactValue));
    }

    public Long id() { return id; }
    public long companyId() { return companyId; }
    public String clientId() { return clientId; }
    public String displayName() { return displayName; }
    public OAuthClientStatus status() { return status; }
    public OAuthClientTrust trust() { return trust; }
    public boolean publicClient() { return publicClient; }
    public long version() { return version; }
    public Set<URI> redirectUris() { return redirectUris; }
    public Set<URI> postLogoutRedirectUris() { return postLogoutRedirectUris; }
    public Set<String> scopes() { return scopes; }
    public Set<OAuthClientSecret> secrets() { return secrets; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
