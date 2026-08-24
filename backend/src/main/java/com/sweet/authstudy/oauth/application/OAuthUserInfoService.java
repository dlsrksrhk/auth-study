package com.sweet.authstudy.oauth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public final class OAuthUserInfoService {

    private final OAuthUserInfoClaimSource claimSource;
    private final Clock clock;

    public OAuthUserInfoService(OAuthUserInfoClaimSource claimSource, Clock clock) {
        this.claimSource = claimSource;
        this.clock = clock;
    }

    public OAuthUserInfoView userInfo(Request request) {
        Objects.requireNonNull(request, "request");
        OAuthUserInfoClaimSource.Snapshot snapshot = claimSource.load(request.rawAccessToken())
                .orElseThrow(InvalidTokenException::new);
        validate(request, snapshot, clock.instant());

        Set<String> scopes = snapshot.authorization().grantedScopes();
        Optional<OAuthUserInfoView.Profile> profile = scopes.contains("profile")
                ? Optional.of(new OAuthUserInfoView.Profile(snapshot.user().name(), snapshot.user().code()))
                : Optional.empty();
        Optional<OAuthUserInfoView.Email> email = scopes.contains("email")
                ? Optional.of(new OAuthUserInfoView.Email(snapshot.account().email(), false))
                : Optional.empty();
        Optional<OAuthUserInfoView.Company> company = scopes.contains("hr.company")
                ? Optional.of(new OAuthUserInfoView.Company(snapshot.company().code(), snapshot.company().name()))
                : Optional.empty();
        Optional<OAuthUserInfoView.Organization> organization = scopes.contains("hr.organization")
                ? Optional.of(organization(snapshot))
                : Optional.empty();
        Optional<List<String>> roles = scopes.contains("hr.roles")
                ? Optional.of(snapshot.account().roles().stream().sorted().toList())
                : Optional.empty();
        return new OAuthUserInfoView(
                snapshot.subject().subject(), profile, email, company, organization, roles);
    }

    private void validate(Request request, OAuthUserInfoClaimSource.Snapshot snapshot, Instant now) {
        OAuthUserInfoClaimSource.Authorization authorization = snapshot.authorization();
        OAuthUserInfoClaimSource.AccessToken accessToken = snapshot.accessToken();
        OAuthUserInfoClaimSource.Client client = snapshot.client();
        OAuthUserInfoClaimSource.Subject subject = snapshot.subject();
        OAuthUserInfoClaimSource.Account account = snapshot.account();
        OAuthUserInfoClaimSource.Company company = snapshot.company();
        OAuthUserInfoClaimSource.User user = snapshot.user();

        long requestedClientId = positiveLong(request.registeredClientId());
        long requestedAccountId = positiveLong(request.principalName());
        boolean activeLoginLock = account.lockedUntil().filter(now::isBefore).isPresent();
        boolean systemAdministrator = account.roles().contains("SYSTEM_ADMIN");

        if (!authorization.id().equals(request.authorizationId())
                || authorization.registeredClientId() != requestedClientId
                || authorization.accountId() != requestedAccountId
                || !client.clientId().equals(request.authorizationClientId())
                || !authorization.subject().equals(subject.subject())
                || !subject.subject().equals(request.accessTokenSubject())
                || !subject.subject().equals(request.idTokenSubject())
                || !client.clientId().equals(request.accessTokenClientId())
                || !request.accessTokenAudiences().equals(Set.of(accessToken.audience()))
                || !authorization.grantedScopes().equals(request.authorizationScopes())
                || !authorization.grantedScopes().equals(request.accessTokenScopes())
                || !authorization.grantedScopes().equals(request.signedAccessTokenScopes())
                || authorization.status() != OAuthUserInfoClaimSource.Status.ACTIVE
                || authorization.revokedAt().isPresent()
                || !now.isBefore(authorization.expiresAt())
                || !authorization.id().equals(accessToken.authorizationId())
                || accessToken.revokedAt().isPresent()
                || !now.isBefore(accessToken.expiresAt())
                || client.status() != OAuthUserInfoClaimSource.Status.ACTIVE
                || account.status() != OAuthUserInfoClaimSource.Status.ACTIVE
                || account.mustChangePassword()
                || activeLoginLock
                || user.status() != OAuthUserInfoClaimSource.Status.ACTIVE
                || company.status() != OAuthUserInfoClaimSource.Status.ACTIVE
                || systemAdministrator
                || account.companyId().isEmpty()
                || account.userId().isEmpty()
                || authorization.registeredClientId() != client.id()
                || authorization.accountId() != account.id()
                || authorization.accountId() != subject.accountId()
                || authorization.companyId() != client.companyId()
                || authorization.companyId() != account.companyId().orElseThrow()
                || authorization.companyId() != company.id()
                || authorization.companyId() != user.companyId()
                || account.userId().orElseThrow() != user.id()) {
            throw new InvalidTokenException();
        }
    }

    private OAuthUserInfoView.Organization organization(OAuthUserInfoClaimSource.Snapshot snapshot) {
        List<OAuthUserInfoClaimSource.Membership> active = snapshot.memberships().stream()
                .filter(membership -> membership.endedAt().isEmpty())
                .filter(membership -> membership.departmentStatus() == OAuthUserInfoClaimSource.Status.ACTIVE)
                .filter(membership -> membership.companyId() == snapshot.company().id())
                .filter(membership -> membership.userId() == snapshot.user().id())
                .toList();
        List<OAuthUserInfoClaimSource.Membership> primary = active.stream()
                .filter(OAuthUserInfoClaimSource.Membership::primary)
                .toList();
        if (primary.size() > 1) throw new InvalidTokenException();
        Optional<OAuthUserInfoView.CodeName> primaryDepartment = primary.stream().findFirst()
                .map(OAuthUserInfoService::codeName);
        List<OAuthUserInfoView.CodeName> secondary = active.stream()
                .filter(membership -> !membership.primary())
                .map(OAuthUserInfoService::codeName)
                .distinct()
                .sorted(Comparator.comparing(OAuthUserInfoView.CodeName::code)
                        .thenComparing(OAuthUserInfoView.CodeName::name))
                .toList();
        Optional<OAuthUserInfoView.CodeName> position = snapshot.position()
                .map(value -> new OAuthUserInfoView.CodeName(value.code(), value.name()));
        return new OAuthUserInfoView.Organization(position, primaryDepartment, secondary);
    }

    private static OAuthUserInfoView.CodeName codeName(OAuthUserInfoClaimSource.Membership membership) {
        return new OAuthUserInfoView.CodeName(
                membership.department().code(), membership.department().name());
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException("non-positive");
            return parsed;
        } catch (RuntimeException exception) {
            throw new InvalidTokenException();
        }
    }

    public record Request(
            String rawAccessToken,
            String authorizationId,
            String registeredClientId,
            String principalName,
            String authorizationClientId,
            Set<String> authorizationScopes,
            Set<String> accessTokenScopes,
            String accessTokenSubject,
            String idTokenSubject,
            String accessTokenClientId,
            Set<String> signedAccessTokenScopes,
            Set<String> accessTokenAudiences) {
        public Request {
            rawAccessToken = requireText(rawAccessToken);
            authorizationId = requireText(authorizationId);
            registeredClientId = requireText(registeredClientId);
            principalName = requireText(principalName);
            authorizationClientId = requireText(authorizationClientId);
            authorizationScopes = Set.copyOf(Objects.requireNonNull(authorizationScopes));
            accessTokenScopes = Set.copyOf(Objects.requireNonNull(accessTokenScopes));
            accessTokenSubject = requireText(accessTokenSubject);
            idTokenSubject = requireText(idTokenSubject);
            accessTokenClientId = requireText(accessTokenClientId);
            signedAccessTokenScopes = Set.copyOf(Objects.requireNonNull(signedAccessTokenScopes));
            accessTokenAudiences = Set.copyOf(Objects.requireNonNull(accessTokenAudiences));
        }

        private static String requireText(String value) {
            if (value == null || value.isBlank()) throw new InvalidTokenException();
            return value;
        }
    }

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() {
            super("Invalid UserInfo token.");
        }
    }
}
