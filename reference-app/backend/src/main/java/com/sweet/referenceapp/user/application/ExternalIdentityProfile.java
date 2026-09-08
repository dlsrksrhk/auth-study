package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ExternalIdentityProfile(
        URI issuer,
        String subject,
        String email,
        String displayName,
        Map<String, Object> company,
        Map<String, Object> organization,
        Set<String> hrRoles) {

    public ExternalIdentityProfile {
        Objects.requireNonNull(issuer, "issuer");
        if (!issuer.isAbsolute()
                || !("http".equalsIgnoreCase(issuer.getScheme())
                    || "https".equalsIgnoreCase(issuer.getScheme()))
                || issuer.getHost() == null
                || issuer.getUserInfo() != null
                || issuer.getQuery() != null
                || issuer.getFragment() != null
                || issuer.toString().length() > 1024) {
            throw new IllegalArgumentException("Invalid issuer");
        }
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("Invalid subject");
        }
        var snapshot = new ExternalUserSnapshot(
                email, displayName, company, organization, hrRoles);
        email = snapshot.email();
        displayName = snapshot.displayName();
        company = snapshot.company();
        organization = snapshot.organization();
        hrRoles = snapshot.hrRoles();
    }

    public ExternalUserSnapshot snapshot() {
        return new ExternalUserSnapshot(email, displayName, company, organization, hrRoles);
    }
}
