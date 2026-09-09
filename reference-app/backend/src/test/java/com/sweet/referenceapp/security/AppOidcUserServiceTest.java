package com.sweet.referenceapp.security;
import com.sweet.referenceapp.user.application.*;
import com.sweet.referenceapp.user.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AppOidcUserServiceTest {
    final OidcIdToken token = new OidcIdToken("secret-id-token", Instant.now(), Instant.now().plusSeconds(300), Map.of("iss", "https://issuer.example", "sub", "external-sub"));
    final OidcUser oidc = new DefaultOidcUser(Set.of(new SimpleGrantedAuthority("SCOPE_admin")), token, new OidcUserInfo(Map.of("sub", "external-sub", "name", "External Name")));
    final OidcUserRequest request = new OidcUserRequest(ClientRegistration.withRegistrationId("reference-app").clientId("client").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://app.example/callback").authorizationUri("https://issuer.example/auth").tokenUri("https://issuer.example/token").build(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "secret-access-token", Instant.now(), Instant.now().plusSeconds(300)), token);
    @SuppressWarnings("unchecked") final OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
    final AppLocalLoginService login = mock(AppLocalLoginService.class);
    final AppOidcUserService service = new AppOidcUserService(delegate, new OidcExternalIdentityMapper(), login);
    static AppUserView local(Set<AppRole> roles) {
        var now = Instant.now();
        return new AppUserView(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), "https://issuer.example", "external-sub", new ExternalUserSnapshot(null, "Local Name", null, null, Set.of("EXTERNAL_ADMIN")), AppUserStatus.ACTIVE, roles, now, now, now, 0);
    }
    @Test void validatedIdentityIsProvisionedOnceWithOnlyLocalAuthorities() {
        when(delegate.loadUser(request)).thenReturn(oidc);
        when(login.login(any())).thenReturn(local(Set.of(AppRole.APP_USER)));
        var result = (AppOidcUser) service.loadUser(request);
        assertThat(result.getName()).isEqualTo("external-sub");
        assertThat(result.localUserId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("APP_USER");
        var order = inOrder(delegate, login);
        order.verify(delegate).loadUser(request);
        order.verify(login).login(new ExternalIdentityProfile(java.net.URI.create("https://issuer.example"), "external-sub", null, "External Name", null, null, Set.of()));
        verifyNoMoreInteractions(delegate, login);
    }
    @Test void currentUserCreatesNewImmutablePrincipalAndKeepsProtocolIdentity() {
        var original = new AppOidcUser(oidc, local(Set.of(AppRole.APP_USER)));
        var refreshed = original.withLocalUser(local(Set.of(AppRole.APP_USER, AppRole.APP_ADMIN)));
        assertThat(refreshed).isNotSameAs(original);
        assertThat(refreshed.getName()).isEqualTo("external-sub");
        assertThat(refreshed.getIdToken()).isSameAs(token);
        assertThat(refreshed.getUserInfo()).isSameAs(oidc.getUserInfo());
        assertThat(refreshed.getClaims()).isEqualTo(oidc.getClaims());
        assertThat(refreshed.getAttributes()).isEqualTo(oidc.getAttributes());
        assertThat(refreshed.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("APP_ADMIN", "APP_USER");
        assertThat(original.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("APP_USER");
        assertThatThrownBy(() -> refreshed.getAuthorities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(refreshed.toString()).doesNotContain("secret-id-token", "External Name", "external-sub");
    }
    @Test void malformedUserInfoNeverReachesLocalLogin() {
        when(delegate.loadUser(request)).thenReturn(new DefaultOidcUser(Set.of(), token, new OidcUserInfo(Map.of("sub", "different"))));
        assertGenericFailure(); verifyNoInteractions(login);
    }
    @Test void delegateFailureCannotImpersonateLocalDisabledError() {
        when(delegate.loadUser(request)).thenThrow(new OAuth2AuthenticationException(new OAuth2Error("local_user_disabled"), "secret-protocol-value"));
        assertGenericFailure(); verifyNoInteractions(login);
    }
    @Test void databaseFailureIsSanitized() {
        when(delegate.loadUser(request)).thenReturn(oidc);
        when(login.login(any())).thenThrow(new IllegalStateException("secret-database-value"));
        assertGenericFailure();
    }
    @Test void disabledLocalLoginHasTrustedMarker() {
        when(delegate.loadUser(request)).thenReturn(oidc);
        when(login.login(any())).thenThrow(new LocalUserDisabledException());
        assertThatThrownBy(() -> service.loadUser(request)).isInstanceOf(AppOidcUserService.LocalUserLoginAuthenticationException.class)
                .satisfies(error -> assertThat(((OAuth2AuthenticationException) error).getError().getErrorCode()).isEqualTo("local_user_disabled"));
    }
    void assertGenericFailure() {
        assertThatThrownBy(() -> service.loadUser(request)).isInstanceOf(OAuth2AuthenticationException.class).isNotInstanceOf(AppOidcUserService.LocalUserLoginAuthenticationException.class)
                .satisfies(error -> { assertThat(((OAuth2AuthenticationException) error).getError().getErrorCode()).isEqualTo("oidc_login_failed"); assertThat(error.getMessage()).doesNotContain("secret-"); assertThat(error.getCause()).isNull(); });
    }
}
