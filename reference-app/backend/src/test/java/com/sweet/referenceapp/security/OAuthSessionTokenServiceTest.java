package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.domain.AppRole;
import java.net.URI;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import static org.assertj.core.api.Assertions.*;

class OAuthSessionTokenServiceTest {
    MockOidcIssuer issuer;
    @BeforeEach void start() { issuer = new MockOidcIssuer(); }
    @AfterEach void stop() { issuer.close(); }

    @Test void refreshesOverHttpThenFetchesStrictUserInfoWithoutPersistence() {
        var service = service();
        var candidate = service.refresh(current(), principal());
        assertThat(candidate.client().getAccessToken().getTokenValue()).isEqualTo("test-access-token-refreshed");
        assertThat(candidate.client().getRefreshToken().getTokenValue()).isEqualTo("test-refresh-token-refreshed");
        assertThat(candidate.profile().subject()).isEqualTo("external-user-1");
        assertThat(issuer.tokenForm).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "old-refresh-secret");
        assertThat(issuer.clientAuthorization).startsWith("Basic ");
        assertThat(issuer.userInfoRequestCount()).isEqualTo(1);
        assertThat(issuer.userInfoAuthorization).isEqualTo("Bearer test-access-token-refreshed");
        assertThat(candidate.toString()).doesNotContain("test-access", "test-refresh");
    }

    @Test void invalidGrantMalformedJsonAndMissingRotatedTokenFailOnce() {
        for (var fault : new String[]{"token-error", "token-malformed", "refresh-missing-token"}) {
            issuer.reset(); issuer.fault = fault;
            assertThatThrownBy(() -> service().refresh(current(), principal())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("OAuth token refresh failed").hasMessageNotContaining("secret").hasMessageNotContaining("invalid_grant");
            assertThat(issuer.tokenRequestCount()).isEqualTo(1);
            assertThat(issuer.userInfoRequestCount()).isZero();
        }
    }

    @Test void invalidUserInfoRevokesRotatedRefreshTokenExactlyOnce() {
        issuer.fault = "mismatched-sub";
        assertThatThrownBy(() -> service().refresh(current(), principal())).isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth token refresh failed");
        assertThat(issuer.tokenRequestCount()).isEqualTo(1);
        assertThat(issuer.userInfoRequestCount()).isEqualTo(1);
        assertThat(issuer.revocationRequestCount()).isEqualTo(1);
        assertThat(issuer.revocationForm).containsEntry("token", "test-refresh-token-refreshed");
    }

    @Test void refreshReadTimeoutMakesOneTokenRequestAndNoRetry() {
        issuer.fault = "token-delay";
        assertThatThrownBy(() -> service().refresh(current(), principal())).isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth token refresh failed");
        assertThat(issuer.tokenRequestCount()).isEqualTo(1);
        assertThat(issuer.userInfoRequestCount()).isZero();
    }

    @Test void droppedRefreshConnectionIsNeverRetried() {
        issuer.fault = "token-drop";
        assertThatThrownBy(() -> service().refresh(current(), principal())).isInstanceOf(IllegalStateException.class);
        assertThat(issuer.tokenRequestCount()).isEqualTo(1);
    }

    @Test void droppedUserInfoConnectionIsNeverRetriedAndSuccessorIsRevoked() {
        issuer.fault = "userinfo-drop";
        assertThatThrownBy(() -> service().refresh(current(), principal())).isInstanceOf(IllegalStateException.class);
        assertThat(issuer.tokenRequestCount()).isEqualTo(1);
        assertThat(issuer.userInfoRequestCount()).isEqualTo(1);
        assertThat(issuer.revocationRequestCount()).isEqualTo(1);
    }

    private OAuthSessionTokenService service() { var p = properties(); return new OAuthSessionTokenService(p, new OidcExternalIdentityMapper(), new OAuthTokenRevoker(p)); }
    private OAuthTokenLifecycleProperties properties() { return new OAuthTokenLifecycleProperties(Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(60), 1000, URI.create(issuer.origin()+"/revoke"), URI.create(issuer.origin()+"/logout")); }
    private ClientRegistration registration() { return ClientRegistration.withRegistrationId("reference-app").clientId(MockOidcIssuer.CLIENT_ID).clientSecret(MockOidcIssuer.CLIENT_SECRET).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://rp/callback").scope("openid", "profile", "email").authorizationUri(issuer.origin()+"/authorize").tokenUri(issuer.origin()+"/token").userInfoUri(issuer.origin()+"/userinfo").userNameAttributeName("sub").clientName("test").build(); }
    private OAuth2AuthorizedClient current() { var now=Instant.now(); return new OAuth2AuthorizedClient(registration(), "external-user-1", new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"old-access-secret",now.minusSeconds(60),now.plusSeconds(5)), new OAuth2RefreshToken("old-refresh-secret",now.minusSeconds(60))); }
    private AppOidcUser principal() { var now=Instant.now(); var id = new OidcIdToken("id-secret",now.minusSeconds(60),now.plusSeconds(300),Map.of("iss",issuer.origin(),"sub","external-user-1")); var delegate = new DefaultOidcUser(java.util.List.of(), id, new OidcUserInfo(Map.of("sub","external-user-1")), "sub"); return new AppOidcUser(delegate, AppOidcUserServiceTest.local(java.util.Set.of(AppRole.APP_USER))); }
}
