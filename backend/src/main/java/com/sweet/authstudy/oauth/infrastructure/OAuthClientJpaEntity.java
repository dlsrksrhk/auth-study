package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import jakarta.persistence.*;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Entity
@Table(name = "oauth_client")
class OAuthClientJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private long companyId;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthClientStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthClientTrust trust;

    @Column(name = "public_client", nullable = false)
    private boolean publicClient;

    @Version
    @Column(nullable = false)
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "oauth_client_redirect_uri",
            joinColumns = @JoinColumn(name = "client_id"))
    private Set<RedirectValue> redirects = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "oauth_client_scope",
            joinColumns = @JoinColumn(name = "client_id"))
    @Column(name = "scope", nullable = false)
    private Set<String> scopes = new HashSet<>();

    @OneToMany(
            mappedBy = "client",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private Set<OAuthClientSecretJpaEntity> secrets = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OAuthClientJpaEntity() {
    }

    private OAuthClientJpaEntity(OAuthClient client) {
        this.companyId = client.companyId();
        this.clientId = client.clientId();
        this.createdAt = client.createdAt();
        updateFrom(client);
    }

    static OAuthClientJpaEntity from(OAuthClient client) {
        return new OAuthClientJpaEntity(client);
    }

    void updateFrom(OAuthClient client) {
        this.displayName = client.displayName();
        this.status = client.status();
        this.trust = client.trust();
        this.publicClient = client.publicClient();
        this.updatedAt = client.updatedAt();

        this.redirects.clear();
        client.redirectUris().stream()
                .map(uri -> RedirectValue.of(uri, RedirectPurpose.AUTHORIZATION))
                .forEach(this.redirects::add);
        client.postLogoutRedirectUris().stream()
                .map(uri -> RedirectValue.of(uri, RedirectPurpose.POST_LOGOUT))
                .forEach(this.redirects::add);

        this.scopes.clear();
        this.scopes.addAll(client.scopes());
        synchronizeSecrets(client.secrets());
    }

    private void synchronizeSecrets(Set<OAuthClientSecret> domainSecrets) {
        Map<Long, OAuthClientSecretJpaEntity> persistedById = secrets.stream()
                .filter(secret -> secret.id() != null)
                .collect(Collectors.toMap(OAuthClientSecretJpaEntity::id, Function.identity()));
        Set<Long> retainedIds = domainSecrets.stream()
                .map(OAuthClientSecret::id)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        secrets.removeIf(secret -> secret.id() != null && !retainedIds.contains(secret.id()));
        for (OAuthClientSecret secret : domainSecrets) {
            if (secret.id() == null) {
                secrets.add(OAuthClientSecretJpaEntity.from(this, secret));
            } else {
                OAuthClientSecretJpaEntity entity = persistedById.get(secret.id());
                if (entity == null) {
                    throw new IllegalStateException("OAuth client secret does not belong to this client.");
                }
                entity.updateFrom(secret);
            }
        }
    }

    OAuthClient toDomain() {
        Set<URI> redirectUris = redirects.stream()
                .filter(redirect -> redirect.purpose == RedirectPurpose.AUTHORIZATION)
                .map(RedirectValue::uri)
                .collect(Collectors.toUnmodifiableSet());
        Set<URI> postLogoutRedirectUris = redirects.stream()
                .filter(redirect -> redirect.purpose == RedirectPurpose.POST_LOGOUT)
                .map(RedirectValue::uri)
                .collect(Collectors.toUnmodifiableSet());
        Set<OAuthClientSecret> domainSecrets = secrets.stream()
                .map(OAuthClientSecretJpaEntity::toDomain)
                .collect(Collectors.toUnmodifiableSet());
        return OAuthClient.restore(
                id, companyId, clientId, displayName, status, trust, publicClient, version,
                redirectUris, postLogoutRedirectUris, scopes, domainSecrets, createdAt, updatedAt);
    }

    private enum RedirectPurpose {
        AUTHORIZATION,
        POST_LOGOUT
    }

    @Embeddable
    static class RedirectValue {

        @Column(name = "redirect_uri", nullable = false)
        private String value;

        @Enumerated(EnumType.STRING)
        @Column(name = "purpose", nullable = false)
        private RedirectPurpose purpose;

        protected RedirectValue() {
        }

        private RedirectValue(URI uri, RedirectPurpose purpose) {
            this.value = uri.toString();
            this.purpose = purpose;
        }

        static RedirectValue of(URI uri, RedirectPurpose purpose) {
            return new RedirectValue(uri, purpose);
        }

        URI uri() {
            return URI.create(value);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RedirectValue that)) {
                return false;
            }
            return value.equals(that.value) && purpose == that.purpose;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(value, purpose);
        }
    }
}
