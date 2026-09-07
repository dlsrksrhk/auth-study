package com.sweet.authstudy.oauth.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Application port for a fresh, authoritative UserInfo snapshot.
 * Implementations translate persistence/domain objects into these immutable values and never expose JPA entities.
 */
public interface OAuthUserInfoClaimSource {

    Optional<Snapshot> load(String rawAccessToken);

    enum Status {ACTIVE, INACTIVE}

    record Authorization(
            String id,
            long registeredClientId,
            long accountId,
            long companyId,
            String subject,
            Set<String> grantedScopes,
            Status status,
            Instant expiresAt,
            Optional<Instant> revokedAt) {
        public Authorization {
            id = requireText(id, "authorization id");
            subject = requireText(subject, "authorization subject");
            grantedScopes = Set.copyOf(Objects.requireNonNull(grantedScopes, "grantedScopes"));
            status = Objects.requireNonNull(status, "status");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        }
    }

    record AccessToken(
            String authorizationId,
            String audience,
            Instant expiresAt,
            Optional<Instant> revokedAt) {
        public AccessToken {
            authorizationId = requireText(authorizationId, "access authorization id");
            audience = requireText(audience, "access audience");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        }
    }

    record Client(long id, long companyId, String clientId, Status status) {
        public Client {
            clientId = requireText(clientId, "clientId");
            status = Objects.requireNonNull(status, "status");
        }
    }

    record Subject(long accountId, String subject) {
        public Subject {
            subject = requireText(subject, "subject");
        }
    }

    record Account(
            long id,
            Optional<Long> companyId,
            Optional<Long> userId,
            Optional<String> email,
            Status status,
            boolean mustChangePassword,
            Optional<Instant> lockedUntil,
            List<String> roles) {
        public Account {
            companyId = Objects.requireNonNull(companyId, "companyId");
            userId = Objects.requireNonNull(userId, "userId");
            email = Objects.requireNonNull(email, "email");
            status = Objects.requireNonNull(status, "status");
            lockedUntil = Objects.requireNonNull(lockedUntil, "lockedUntil");
            roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
        }
    }

    record Company(long id, String code, String name, Status status) {
        public Company {
            code = requireText(code, "company code");
            name = requireText(name, "company name");
            status = Objects.requireNonNull(status, "status");
        }
    }

    record User(long id, long companyId, String name, Status status) {
        public User {
            name = requireText(name, "user name");
            status = Objects.requireNonNull(status, "status");
        }
    }

    record CodeName(String code, String name) {
        public CodeName {
            code = requireText(code, "code");
            name = requireText(name, "name");
        }
    }

    record Membership(
            long companyId,
            long userId,
            CodeName department,
            Status departmentStatus,
            boolean primary,
            Optional<Instant> endedAt) {
        public Membership {
            department = Objects.requireNonNull(department, "department");
            departmentStatus = Objects.requireNonNull(departmentStatus, "departmentStatus");
            endedAt = Objects.requireNonNull(endedAt, "endedAt");
        }
    }

    record Snapshot(
            Authorization authorization,
            AccessToken accessToken,
            Client client,
            Subject subject,
            Account account,
            Company company,
            User user,
            Optional<CodeName> position,
            List<Membership> memberships) {
        public Snapshot {
            authorization = Objects.requireNonNull(authorization, "authorization");
            accessToken = Objects.requireNonNull(accessToken, "accessToken");
            client = Objects.requireNonNull(client, "client");
            subject = Objects.requireNonNull(subject, "subject");
            account = Objects.requireNonNull(account, "account");
            company = Objects.requireNonNull(company, "company");
            user = Objects.requireNonNull(user, "user");
            position = Objects.requireNonNull(position, "position");
            memberships = List.copyOf(Objects.requireNonNull(memberships, "memberships"));
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
