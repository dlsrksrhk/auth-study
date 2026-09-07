package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthConsent;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "oauth_consent")
class OAuthConsentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "principal_account_id", nullable = false)
    private long principalAccountId;

    @Column(name = "registered_client_id", nullable = false)
    private long registeredClientId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_consent_scope", joinColumns = @JoinColumn(name = "consent_id"))
    @Column(name = "scope", nullable = false)
    private Set<String> scopes = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OAuthConsentJpaEntity() {
    }

    private OAuthConsentJpaEntity(OAuthConsent consent) {
        principalAccountId = consent.principalAccountId();
        registeredClientId = consent.registeredClientId();
        createdAt = consent.createdAt();
        updateFrom(consent);
    }

    static OAuthConsentJpaEntity from(OAuthConsent consent) {
        return new OAuthConsentJpaEntity(consent);
    }

    void updateFrom(OAuthConsent consent) {
        if (principalAccountId != consent.principalAccountId()
                || registeredClientId != consent.registeredClientId()) {
            throw new IllegalArgumentException("Consent ownership cannot be changed.");
        }
        scopes.clear();
        scopes.addAll(consent.scopes());
        updatedAt = consent.updatedAt();
    }

    OAuthConsent toDomain() {
        return OAuthConsent.restore(id, principalAccountId, registeredClientId, scopes, createdAt, updatedAt);
    }
}
