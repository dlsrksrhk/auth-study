package com.sweet.authstudy.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OAuthUserInfoServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");
    private static final String SUBJECT = "5eb01ca2-d9c4-43bb-bdf3-bf55b7f01c21";
    private static final Set<String> ALL_SCOPES = Set.of(
            "openid", "profile", "email", "hr.company", "hr.organization", "hr.roles");

    @Test
    void application_service_owns_the_exact_scope_to_typed_claim_matrix() {
        OAuthUserInfoView openid = view(Set.of("openid"));
        assertThat(openid).isEqualTo(new OAuthUserInfoView(
                SUBJECT, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

        OAuthUserInfoView profile = view(Set.of("openid", "profile"));
        assertThat(profile.profile()).contains(new OAuthUserInfoView.Profile("Ada Lovelace"));
        assertThat(profile.email()).isEmpty();

        OAuthUserInfoView email = view(Set.of("openid", "email"));
        assertThat(email.email()).contains(new OAuthUserInfoView.Email(Optional.of("ada@acme.example"), false));
        assertThat(email.profile()).isEmpty();

        OAuthUserInfoView company = view(Set.of("openid", "hr.company"));
        assertThat(company.company()).contains(new OAuthUserInfoView.Company("ACME", "Acme Corp"));
        assertThat(company.organization()).isEmpty();

        OAuthUserInfoView organization = view(Set.of("openid", "hr.organization"));
        assertThat(organization.organization()).contains(new OAuthUserInfoView.Organization(
                Optional.of(new OAuthUserInfoView.CodeName("ENG", "Engineer")),
                Optional.of(new OAuthUserInfoView.CodeName("PLATFORM", "Platform")),
                List.of(
                        new OAuthUserInfoView.CodeName("ALPHA", "Alpha"),
                        new OAuthUserInfoView.CodeName("ZETA", "Zeta"))));
        assertThat(organization.company()).isEmpty();

        OAuthUserInfoView roles = view(Set.of("openid", "hr.roles"));
        assertThat(roles.roles()).contains(List.of("COMPANY_ADMIN", "USER"));
        assertThat(roles.profile()).isEmpty();

        OAuthUserInfoView all = view(ALL_SCOPES);
        assertThat(all.profile()).isPresent();
        assertThat(all.email()).isPresent();
        assertThat(all.company()).isPresent();
        assertThat(all.organization()).isPresent();
        assertThat(all.roles()).isPresent();
    }

    @Test
    void organization_uses_only_current_owned_active_memberships_in_stable_order() {
        SnapshotBuilder snapshot = new SnapshotBuilder();
        snapshot.grantedScopes = Set.of("openid", "hr.organization");
        snapshot.memberships = List.of(
                membership("ZETA", "Zeta", false),
                membership("PLATFORM", "Platform", true),
                membership("ALPHA", "Alpha", false),
                membership("ENDED", "Ended", false, OAuthUserInfoClaimSource.Status.ACTIVE, Optional.of(NOW)),
                membership("INACTIVE", "Inactive", false, OAuthUserInfoClaimSource.Status.INACTIVE, Optional.empty()),
                new OAuthUserInfoClaimSource.Membership(
                        999L, snapshot.userId, new OAuthUserInfoClaimSource.CodeName("OTHER", "Other"),
                        OAuthUserInfoClaimSource.Status.ACTIVE, false, Optional.empty()));
        OAuthUserInfoView view = service(new MutableSource(snapshot.build()))
                .userInfo(request(Set.of("openid", "hr.organization")));

        OAuthUserInfoView.Organization organization = view.organization().orElseThrow();
        assertThat(organization.primaryDepartment()).contains(
                new OAuthUserInfoView.CodeName("PLATFORM", "Platform"));
        assertThat(organization.secondaryDepartments()).containsExactly(
                new OAuthUserInfoView.CodeName("ALPHA", "Alpha"),
                new OAuthUserInfoView.CodeName("ZETA", "Zeta"));
    }

    @Test
    void optional_email_position_and_primary_membership_never_become_null_claim_values() {
        SnapshotBuilder snapshot = new SnapshotBuilder();
        snapshot.grantedScopes = Set.of("openid", "email", "hr.organization");
        snapshot.email = Optional.empty();
        snapshot.position = Optional.empty();
        snapshot.memberships = List.of();
        OAuthUserInfoView view = service(new MutableSource(snapshot.build()))
                .userInfo(request(Set.of("openid", "email", "hr.organization")));

        assertThat(view.email()).contains(new OAuthUserInfoView.Email(Optional.empty(), false));
        assertThat(view.organization()).contains(new OAuthUserInfoView.Organization(
                Optional.empty(), Optional.empty(), List.of()));
    }

    @Test
    void a_login_lock_ends_at_the_exact_clock_boundary() {
        SnapshotBuilder snapshot = new SnapshotBuilder();
        snapshot.grantedScopes = Set.of("openid");
        snapshot.lockedUntil = Optional.of(NOW);

        OAuthUserInfoView view = service(new MutableSource(snapshot.build()))
                .userInfo(request(Set.of("openid")));

        assertThat(view.subject()).isEqualTo(SUBJECT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCurrentStateMutations")
    void every_current_state_or_ownership_failure_is_the_same_generic_invalid_token(
            String ignoredName, Consumer<SnapshotBuilder> mutation) {
        SnapshotBuilder snapshot = new SnapshotBuilder();
        mutation.accept(snapshot);

        assertThatThrownBy(() -> service(new MutableSource(snapshot.build())).userInfo(request(ALL_SCOPES)))
                .isExactlyInstanceOf(OAuthUserInfoService.InvalidTokenException.class)
                .hasMessage("Invalid UserInfo token.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProtocolBindingMutations")
    void bearer_authorization_id_subject_client_audience_and_scopes_are_exactly_bound(
            String ignoredName, Consumer<RequestBuilder> mutation) {
        RequestBuilder request = new RequestBuilder(ALL_SCOPES);
        mutation.accept(request);

        assertThatThrownBy(() -> service(new MutableSource(new SnapshotBuilder().build()))
                .userInfo(request.build()))
                .isExactlyInstanceOf(OAuthUserInfoService.InvalidTokenException.class)
                .hasMessage("Invalid UserInfo token.");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidCurrentStateMutations() {
        return Stream.of(
                args("missing source snapshot", builder -> builder.present = false),
                args("authorization inactive", builder -> builder.authorizationStatus = OAuthUserInfoClaimSource.Status.INACTIVE),
                args("authorization revoked", builder -> builder.authorizationRevokedAt = Optional.of(NOW.minusSeconds(1))),
                args("authorization expired", builder -> builder.authorizationExpiresAt = NOW),
                args("access token revoked", builder -> builder.accessRevokedAt = Optional.of(NOW.minusSeconds(1))),
                args("access token expired", builder -> builder.accessExpiresAt = NOW),
                args("client inactive", builder -> builder.clientStatus = OAuthUserInfoClaimSource.Status.INACTIVE),
                args("account inactive", builder -> builder.accountStatus = OAuthUserInfoClaimSource.Status.INACTIVE),
                args("active login lock", builder -> builder.lockedUntil = Optional.of(NOW.plusSeconds(1))),
                args("password change required", builder -> builder.mustChangePassword = true),
                args("user inactive", builder -> builder.userStatus = OAuthUserInfoClaimSource.Status.INACTIVE),
                args("company inactive", builder -> builder.companyStatus = OAuthUserInfoClaimSource.Status.INACTIVE),
                args("system administrator", builder -> {
                    builder.accountCompanyId = Optional.empty();
                    builder.accountUserId = Optional.empty();
                    builder.roles = List.of("SYSTEM_ADMIN");
                }),
                args("authorization token ownership", builder -> builder.accessAuthorizationId = "other-authorization"),
                args("authorization client ownership", builder -> builder.authorizationClientId = 999L),
                args("authorization account ownership", builder -> builder.authorizationAccountId = 999L),
                args("authorization company ownership", builder -> builder.authorizationCompanyId = 999L),
                args("subject account ownership", builder -> builder.subjectAccountId = 999L),
                args("subject value ownership", builder -> builder.currentSubject = "9fae3567-7435-4cd9-b607-2872f3b40f01"),
                args("client company ownership", builder -> builder.clientCompanyId = 999L),
                args("account company ownership", builder -> builder.accountCompanyId = Optional.of(999L)),
                args("account user ownership", builder -> builder.accountUserId = Optional.of(999L)),
                args("user company ownership", builder -> builder.userCompanyId = 999L));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidProtocolBindingMutations() {
        return Stream.of(
                requestArgs("authorization id", request -> request.authorizationId = "other-authorization"),
                requestArgs("registered client id", request -> request.registeredClientId = "999"),
                requestArgs("principal name", request -> request.principalName = "999"),
                requestArgs("authorization public client id", request -> request.authorizationClientId = "other-client"),
                requestArgs("access-token subject", request -> request.accessTokenSubject = "other-subject"),
                requestArgs("id-token subject", request -> request.idTokenSubject = "other-subject"),
                requestArgs("access-token client", request -> request.accessTokenClientId = "other-client"),
                requestArgs("access-token audience", request -> request.accessTokenAudiences = Set.of("other-audience")),
                requestArgs("authorization scopes", request -> request.authorizationScopes = Set.of("openid")),
                requestArgs("access-token object scopes", request -> request.accessTokenScopes = Set.of("openid")),
                requestArgs("signed access-token scopes", request -> request.signedAccessTokenScopes = Set.of("openid")));
    }

    private OAuthUserInfoService service(OAuthUserInfoClaimSource source) {
        return new OAuthUserInfoService(source, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OAuthUserInfoView view(Set<String> scopes) {
        SnapshotBuilder snapshot = new SnapshotBuilder();
        snapshot.grantedScopes = scopes;
        return service(new MutableSource(snapshot.build())).userInfo(request(scopes));
    }

    private OAuthUserInfoService.Request request(Set<String> scopes) {
        return new RequestBuilder(scopes).build();
    }

    private static org.junit.jupiter.params.provider.Arguments args(
            String name, Consumer<SnapshotBuilder> mutation) {
        return org.junit.jupiter.params.provider.Arguments.of(name, mutation);
    }

    private static org.junit.jupiter.params.provider.Arguments requestArgs(
            String name, Consumer<RequestBuilder> mutation) {
        return org.junit.jupiter.params.provider.Arguments.of(name, mutation);
    }

    private static OAuthUserInfoClaimSource.Membership membership(
            String code, String name, boolean primary) {
        return membership(code, name, primary, OAuthUserInfoClaimSource.Status.ACTIVE, Optional.empty());
    }

    private static OAuthUserInfoClaimSource.Membership membership(
            String code, String name, boolean primary, OAuthUserInfoClaimSource.Status departmentStatus,
            Optional<Instant> endedAt) {
        return new OAuthUserInfoClaimSource.Membership(
                11L, 31L, new OAuthUserInfoClaimSource.CodeName(code, name),
                departmentStatus, primary, endedAt);
    }

    private static final class MutableSource implements OAuthUserInfoClaimSource {
        private final Snapshot snapshot;

        private MutableSource(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<Snapshot> load(String rawAccessToken) {
            return "access-token".equals(rawAccessToken) ? Optional.ofNullable(snapshot) : Optional.empty();
        }
    }

    private static final class RequestBuilder {
        private String rawAccessToken = "access-token";
        private String authorizationId = "authorization-1";
        private String registeredClientId = "21";
        private String principalName = "41";
        private String authorizationClientId = "client-1";
        private Set<String> authorizationScopes;
        private Set<String> accessTokenScopes;
        private String accessTokenSubject = SUBJECT;
        private String idTokenSubject = SUBJECT;
        private String accessTokenClientId = "client-1";
        private Set<String> signedAccessTokenScopes;
        private Set<String> accessTokenAudiences = Set.of("auth-study-userinfo");

        private RequestBuilder(Set<String> scopes) {
            authorizationScopes = scopes;
            accessTokenScopes = scopes;
            signedAccessTokenScopes = scopes;
        }

        private OAuthUserInfoService.Request build() {
            return new OAuthUserInfoService.Request(
                    rawAccessToken, authorizationId, registeredClientId, principalName,
                    authorizationClientId, authorizationScopes, accessTokenScopes,
                    accessTokenSubject, idTokenSubject, accessTokenClientId,
                    signedAccessTokenScopes, accessTokenAudiences);
        }
    }

    private static final class SnapshotBuilder {
        private boolean present = true;
        private String authorizationId = "authorization-1";
        private long authorizationClientId = 21L;
        private long authorizationAccountId = 41L;
        private long authorizationCompanyId = 11L;
        private String authorizationSubject = SUBJECT;
        private Set<String> grantedScopes = ALL_SCOPES;
        private OAuthUserInfoClaimSource.Status authorizationStatus = OAuthUserInfoClaimSource.Status.ACTIVE;
        private Instant authorizationExpiresAt = NOW.plusSeconds(600);
        private Optional<Instant> authorizationRevokedAt = Optional.empty();
        private String accessAuthorizationId = "authorization-1";
        private String accessAudience = "auth-study-userinfo";
        private Instant accessExpiresAt = NOW.plusSeconds(300);
        private Optional<Instant> accessRevokedAt = Optional.empty();
        private long clientId = 21L;
        private long clientCompanyId = 11L;
        private String publicClientId = "client-1";
        private OAuthUserInfoClaimSource.Status clientStatus = OAuthUserInfoClaimSource.Status.ACTIVE;
        private long subjectAccountId = 41L;
        private String currentSubject = SUBJECT;
        private long accountId = 41L;
        private Optional<Long> accountCompanyId = Optional.of(11L);
        private Optional<Long> accountUserId = Optional.of(31L);
        private Optional<String> email = Optional.of("ada@acme.example");
        private OAuthUserInfoClaimSource.Status accountStatus = OAuthUserInfoClaimSource.Status.ACTIVE;
        private boolean mustChangePassword;
        private Optional<Instant> lockedUntil = Optional.empty();
        private List<String> roles = List.of("USER", "COMPANY_ADMIN");
        private long companyId = 11L;
        private String companyCode = "ACME";
        private String companyName = "Acme Corp";
        private OAuthUserInfoClaimSource.Status companyStatus = OAuthUserInfoClaimSource.Status.ACTIVE;
        private long userId = 31L;
        private long userCompanyId = 11L;
        private String userCode = "USR-ADA";
        private String userName = "Ada Lovelace";
        private OAuthUserInfoClaimSource.Status userStatus = OAuthUserInfoClaimSource.Status.ACTIVE;
        private Optional<OAuthUserInfoClaimSource.CodeName> position = Optional.of(
                new OAuthUserInfoClaimSource.CodeName("ENG", "Engineer"));
        private List<OAuthUserInfoClaimSource.Membership> memberships = List.of(
                membership("ZETA", "Zeta", false),
                membership("PLATFORM", "Platform", true),
                membership("ALPHA", "Alpha", false));

        private OAuthUserInfoClaimSource.Snapshot build() {
            if (!present) return null;
            return new OAuthUserInfoClaimSource.Snapshot(
                    new OAuthUserInfoClaimSource.Authorization(
                            authorizationId, authorizationClientId, authorizationAccountId,
                            authorizationCompanyId, authorizationSubject, grantedScopes,
                            authorizationStatus, authorizationExpiresAt, authorizationRevokedAt),
                    new OAuthUserInfoClaimSource.AccessToken(
                            accessAuthorizationId, accessAudience, accessExpiresAt, accessRevokedAt),
                    new OAuthUserInfoClaimSource.Client(clientId, clientCompanyId, publicClientId, clientStatus),
                    new OAuthUserInfoClaimSource.Subject(subjectAccountId, currentSubject),
                    new OAuthUserInfoClaimSource.Account(
                            accountId, accountCompanyId, accountUserId, email, accountStatus,
                            mustChangePassword, lockedUntil, roles),
                    new OAuthUserInfoClaimSource.Company(
                            companyId, companyCode, companyName, companyStatus),
                    new OAuthUserInfoClaimSource.User(
                            userId, userCompanyId, userName, userStatus),
                    position, memberships);
        }
    }
}
