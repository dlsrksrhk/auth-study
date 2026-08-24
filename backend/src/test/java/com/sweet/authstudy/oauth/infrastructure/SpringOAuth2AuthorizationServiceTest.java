package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.application.OAuthConsentService;
import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCodeExchangeBinding;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.oauth.domain.OAuthConsent;
import com.sweet.authstudy.oauth.domain.OAuthConsentRepository;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class SpringOAuth2AuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final String CODE = "raw-authorization-code-that-must-never-be-persisted";
    private static final String ACCESS = "raw-access-token-that-must-never-be-persisted";
    private static final String REFRESH = "raw-refresh-token-that-must-never-be-persisted";
    private static final String CONSENT_STATE = "server-generated-consent-state";
    private static final String CHALLENGE = challenge("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~");
    private static final URI CALLBACK = URI.create("https://rp.example/callback?exact=true");
    private static final UUID SUBJECT = UUID.fromString("efb84d88-1b98-4c80-8ab7-45c86ee4ef51");

    @Mock private OAuthAuthorizationRepository authorizations;
    @Mock private OAuthConsentRepository consents;
    @Mock private OAuthClientRepository clients;
    @Mock private OAuthSubjectRepository subjects;
    @Mock private AccountRepository accounts;

    private OAuthAuthorizationMapper mapper;
    private SpringOAuth2AuthorizationService service;
    private SpringOAuth2AuthorizationConsentService consentService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mapper = new OAuthAuthorizationMapper(clients, subjects, accounts, properties(), clock);
        service = new SpringOAuth2AuthorizationService(authorizations, mapper, clock);
        consentService = new SpringOAuth2AuthorizationConsentService(
                new OAuthConsentService(consents, clients, authorizations, clock));
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void authorization_code_token_finalization_without_a_verified_consume_cache_fails_closed() {
        assertThatThrownBy(() -> service.save(springAuthorization()))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));

        verify(authorizations, never()).save(any());
        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
    }

    @Test
    void a_verified_consume_cache_is_single_use_for_authorization_code_finalization() {
        when(authorizations.finalizeAuthorizationCodeExchange(any(), any()))
                .thenReturn(OAuthAuthorizationRepository.CodeFinalizationResult.FINALIZED);
        service.cacheConsumedAuthorization(springAuthorization(), null, binding(CODE));

        service.save(springAuthorization());

        assertThatThrownBy(() -> service.save(springAuthorization()))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));
        verify(authorizations).finalizeAuthorizationCodeExchange(any(), any());
        verify(authorizations, never()).save(any());
    }

    @Test
    void refresh_shaped_save_with_a_consume_cache_uses_the_normal_path_and_clears_the_cache() {
        stubOwnership(activeClient());
        OAuthAuthorization persisted = domainAuthorization();
        persisted.authorizationCode().orElseThrow().consume(NOW.minusSeconds(1));
        when(authorizations.findByRefreshTokenHash(sha256(REFRESH)))
                .thenReturn(Optional.of(persisted.refreshToken().orElseThrow()));
        when(authorizations.findById("authorization-1")).thenReturn(Optional.of(persisted));
        when(authorizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.cacheConsumedAuthorization(springAuthorization(), null, binding(CODE));
        var persistedRefresh = service.findByToken(REFRESH, OAuth2TokenType.REFRESH_TOKEN);

        service.save(refreshSaveCandidate(persistedRefresh));

        verify(authorizations).save(any());
        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
        assertThatThrownBy(() -> service.save(springAuthorization()))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));
    }

    @Test
    void an_unmarked_code_grant_candidate_cannot_bypass_the_required_cache_by_invalidating_the_code() {
        assertThatThrownBy(() -> service.save(refreshSaveCandidate(springAuthorization())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));

        verify(authorizations, never()).save(any());
        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
    }

    @Test
    void a_stale_consume_cache_with_another_code_hash_fails_closed_before_finalization() {
        String staleCode = "another-consumed-authorization-code";
        service.cacheConsumedAuthorization(
                springAuthorization(staleCode), null, binding(staleCode));

        assertThatThrownBy(() -> service.save(springAuthorization()))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));

        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
        verify(authorizations, never()).save(any());
    }

    @Test
    void a_candidate_spoofing_the_consumed_authorization_id_fails_closed_before_finalization() {
        service.cacheConsumedAuthorization(springAuthorization(), null, binding(CODE));
        var spoofed = org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .from(springAuthorization())
                .id("another-authorization")
                .build();

        assertThatThrownBy(() -> service.save(spoofed))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));
        assertThatThrownBy(() -> service.save(springAuthorization()))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));

        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
        verify(authorizations, never()).save(any());
    }

    @Test
    void a_failed_cache_replacement_removes_the_previous_verified_cache() {
        service.cacheConsumedAuthorization(springAuthorization(), null, binding(CODE));

        assertThatThrownBy(() -> service.cacheConsumedAuthorization(
                springAuthorization("different-code"), null, binding(CODE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(springAuthorization()))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));

        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
        verify(authorizations, never()).save(any());
    }

    @Test
    void a_non_code_grant_with_a_consume_cache_uses_the_normal_path() {
        stubOwnership(activeClient());
        when(authorizations.findById("authorization-1")).thenReturn(Optional.of(domainAuthorization()));
        when(authorizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.cacheConsumedAuthorization(springAuthorization(), null, binding(CODE));
        var clientCredentials = org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .from(springAuthorization())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();

        service.save(clientCredentials);

        verify(authorizations).save(any());
        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
    }

    @Test
    void authorization_code_finalization_hashes_every_raw_token_at_the_adapter_boundary() {
        when(authorizations.finalizeAuthorizationCodeExchange(any(), any()))
                .thenReturn(OAuthAuthorizationRepository.CodeFinalizationResult.FINALIZED);
        service.cacheConsumedAuthorization(springAuthorization(), null, binding(CODE));

        service.save(springAuthorization());

        ArgumentCaptor<OAuthAuthorizationRepository.CodeFinalization> saved =
                ArgumentCaptor.forClass(OAuthAuthorizationRepository.CodeFinalization.class);
        verify(authorizations).finalizeAuthorizationCodeExchange(saved.capture(), any());
        OAuthAuthorizationRepository.CodeFinalization finalization = saved.getValue();
        assertThat(finalization.consumedBinding().codeHash()).isEqualTo(sha256(CODE));
        assertThat(finalization.candidateBinding()).isEqualTo(finalization.consumedBinding());
        assertThat(finalization.consumedBinding().codeHash()).doesNotContain(CODE);
        assertThat(finalization.consumedBinding().authorizationId()).isEqualTo("authorization-1");
        assertThat(finalization.consumedBinding().registeredClientId()).isEqualTo(22L);
        assertThat(finalization.accessToken()).satisfies(token -> {
            assertThat(token.accessTokenHash()).isEqualTo(sha256(ACCESS));
            assertThat(token.jti()).isEqualTo("access-jti");
            assertThat(token.audience()).isEqualTo("auth-study-userinfo");
        });
        assertThat(finalization.refreshToken()).satisfies(token ->
                assertThat(token.refreshTokenHash()).isEqualTo(sha256(REFRESH)));
        verify(authorizations, never()).save(any());
    }

    @Test
    void pending_consent_authorization_round_trips_the_allowlisted_request_by_hashed_server_state() {
        stubOwnership(activeClient());
        when(authorizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.springframework.security.oauth2.server.authorization.OAuth2Authorization pending =
                org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                        .withRegisteredClient(registeredClient())
                        .id("pending-authorization")
                        .principalName("42")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest())
                        .attribute(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(
                                "42", "N/A", List.of()))
                        .attribute("state", CONSENT_STATE)
                        .build();

        service.save(pending);

        ArgumentCaptor<OAuthAuthorization> saved = ArgumentCaptor.forClass(OAuthAuthorization.class);
        verify(authorizations).save(saved.capture());
        OAuthAuthorization persisted = saved.getValue();
        assertThat(persisted.authorizationCode()).isEmpty();
        assertThat(persisted.serverStateHash()).isEqualTo(sha256(CONSENT_STATE));
        assertThat(persisted.attributes().authorizationRequest()).satisfies(request -> {
            assertThat(request.redirectUri()).isEqualTo(CALLBACK.toString());
            assertThat(request.requestedScopes()).containsExactlyInAnyOrder("openid", "profile");
            assertThat(request.rpState()).isEqualTo("opaque-state");
            assertThat(request.codeChallenge()).isEqualTo(CHALLENGE);
            assertThat(request.codeChallengeMethod()).isEqualTo("S256");
            assertThat(request.nonce()).isEqualTo("opaque-nonce");
        });

        when(authorizations.findByServerStateHash(sha256(CONSENT_STATE)))
                .thenReturn(Optional.of(persisted));
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));

        org.springframework.security.oauth2.server.authorization.OAuth2Authorization found =
                service.findByToken(CONSENT_STATE, new OAuth2TokenType("state"));

        assertThat(found.<String>getAttribute("state")).isEqualTo(CONSENT_STATE);
        assertThat(found.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class))
                .isNull();
        assertThat(found.<OAuth2AuthorizationRequest>getAttribute(OAuth2AuthorizationRequest.class.getName()))
                .satisfies(request -> {
                    assertThat(request.getRedirectUri()).isEqualTo(CALLBACK.toString());
                    assertThat(request.getScopes()).containsExactlyInAnyOrder("openid", "profile");
                    assertThat(request.getState()).isEqualTo("opaque-state");
                    assertThat(request.getAdditionalParameters())
                            .containsEntry("code_challenge", CHALLENGE)
                            .containsEntry("code_challenge_method", "S256")
                            .containsEntry("nonce", "opaque-nonce");
                });
    }

    @Test
    void find_by_id_reconstructs_the_spring_contract_without_recovering_raw_secrets() {
        OAuthAuthorization persisted = domainAuthorization();
        when(authorizations.findById("authorization-1")).thenReturn(Optional.of(persisted));
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));

        org.springframework.security.oauth2.server.authorization.OAuth2Authorization found =
                service.findById("authorization-1");

        assertThat(found.getId()).isEqualTo("authorization-1");
        assertThat(found.getRegisteredClientId()).isEqualTo("22");
        assertThat(found.getPrincipalName()).isEqualTo("42");
        assertThat(found.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(found.getAuthorizedScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(found.<OAuth2AuthorizationRequest>getAttribute(OAuth2AuthorizationRequest.class.getName()))
                .satisfies(request -> {
                    assertThat(request.getAuthorizationUri()).isEqualTo("http://idp.localhost:8080/oauth2/authorize");
                    assertThat(request.getClientId()).isEqualTo("public-id");
                    assertThat(request.getRedirectUri()).isEqualTo(CALLBACK.toString());
                    assertThat(request.getState()).isEqualTo("opaque-state");
                    assertThat(request.getAdditionalParameters()).containsEntry("code_challenge", CHALLENGE)
                            .containsEntry("code_challenge_method", "S256")
                            .containsEntry("nonce", "opaque-nonce");
                });
        assertThat(found.<org.springframework.security.core.Authentication>getAttribute(Principal.class.getName())
                .getName()).isEqualTo("42");
        assertThat(found.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class)
                .getToken().getTokenValue()).isEqualTo(sha256(CODE));
        assertThat(found.getAccessToken().getToken().getTokenValue()).isEqualTo(sha256(ACCESS));
        assertThat(found.getRefreshToken().getToken().getTokenValue()).isEqualTo(sha256(REFRESH));
        assertThat(found.getAttributes().toString()).doesNotContain(CODE, ACCESS, REFRESH);
    }

    @Test
    void find_by_each_known_token_type_hashes_then_uses_only_its_indexed_lookup() {
        OAuthAuthorization persisted = domainAuthorization();
        when(authorizations.findByCodeHash(sha256(CODE)))
                .thenReturn(Optional.of(persisted.authorizationCode().orElseThrow()));
        when(authorizations.findByAccessTokenHash(sha256(ACCESS)))
                .thenReturn(Optional.of(persisted.accessToken().orElseThrow()));
        when(authorizations.findByRefreshTokenHash(sha256(REFRESH)))
                .thenReturn(Optional.of(persisted.refreshToken().orElseThrow()));
        when(authorizations.findById("authorization-1")).thenReturn(Optional.of(persisted));
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));

        assertThat(service.findByToken(CODE, new OAuth2TokenType("code"))
                .getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class)
                .getToken().getTokenValue()).isEqualTo(CODE);
        assertThat(service.findByToken(ACCESS, OAuth2TokenType.ACCESS_TOKEN)
                .getAccessToken().getToken().getTokenValue()).isEqualTo(ACCESS);
        assertThat(service.findByToken(REFRESH, OAuth2TokenType.REFRESH_TOKEN)
                .getRefreshToken().getToken().getTokenValue()).isEqualTo(REFRESH);

        verify(authorizations).findByCodeHash(sha256(CODE));
        verify(authorizations).findByAccessTokenHash(sha256(ACCESS));
        verify(authorizations).findByRefreshTokenHash(sha256(REFRESH));
    }

    @Test
    void null_token_type_uses_indexed_lookups_and_rejects_cross_type_hash_ambiguity() {
        OAuthAuthorization persisted = domainAuthorization();
        when(authorizations.findByServerStateHash(sha256(CODE))).thenReturn(Optional.empty());
        when(authorizations.findByCodeHash(sha256(CODE)))
                .thenReturn(Optional.of(persisted.authorizationCode().orElseThrow()));
        when(authorizations.findByAccessTokenHash(sha256(CODE)))
                .thenReturn(Optional.of(OAuthAccessToken.issue(
                        "another-authorization", sha256(CODE), "other-jti", "auth-study-userinfo",
                        NOW, NOW.plusSeconds(300))));
        when(authorizations.findByRefreshTokenHash(sha256(CODE))).thenReturn(Optional.empty());

        assertThat(service.findByToken(CODE, null)).isNull();

        verify(authorizations).findByServerStateHash(sha256(CODE));
        verify(authorizations).findByCodeHash(sha256(CODE));
        verify(authorizations).findByAccessTokenHash(sha256(CODE));
        verify(authorizations).findByRefreshTokenHash(sha256(CODE));
        verify(authorizations, never()).findById(any());
    }

    @Test
    void unknown_token_type_returns_null_without_a_repository_scan() {
        assertThat(service.findByToken("opaque-token", new OAuth2TokenType("device_code"))).isNull();
        verifyNoInteractions(authorizations);
    }

    @Test
    void disabled_or_removed_authorizations_are_invisible_and_remove_delegates_by_id() {
        OAuthAuthorization persisted = domainAuthorization();
        when(authorizations.findById("disabled-authorization")).thenReturn(Optional.of(persisted));
        when(clients.findById(22L)).thenReturn(Optional.of(disabledClient()));

        assertThat(service.findById("disabled-authorization")).isNull();
        assertThat(service.findById("removed-authorization")).isNull();

        org.springframework.security.oauth2.server.authorization.OAuth2Authorization spring = springAuthorization();
        service.remove(spring);
        verify(authorizations).remove("authorization-1");
    }

    @Test
    void revoked_authorizations_are_invisible_even_when_the_client_is_still_active() {
        OAuthAuthorization revoked = domainAuthorization();
        revoked.revoke("ACCOUNT_REVOKED", NOW);
        when(authorizations.findById("revoked-authorization")).thenReturn(Optional.of(revoked));

        assertThat(service.findById("revoked-authorization")).isNull();
    }

    @Test
    void malformed_spring_principal_name_is_rejected_instead_of_being_serialized() {
        RegisteredClient client = registeredClient();
        org.springframework.security.oauth2.server.authorization.OAuth2Authorization malformed =
                org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                        .withRegisteredClient(client)
                        .id("malformed")
                        .principalName("email@example.com")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizedScopes(Set.of("openid"))
                        .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest())
                        .build();

        assertThatThrownBy(() -> service.save(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account id");
        verify(authorizations, never()).save(any());
    }

    @Test
    void save_rejects_cross_company_ownership_from_the_authoritative_account_before_persistence() {
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));
        when(subjects.findByAccountId(42L)).thenReturn(Optional.of(
                OAuthSubject.restore(7L, 42L, SUBJECT, NOW.minusSeconds(300))));
        when(accounts.findById(42L)).thenReturn(Optional.of(account(303L)));

        assertThatThrownBy(() -> service.save(springAuthorizationWithoutIssuedTokens()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OAuth authorization ownership is invalid.");
        verify(authorizations, never()).save(any());
    }

    @Test
    void save_rejects_access_tokens_without_real_jti_and_audience_claims_instead_of_synthesizing_them() {
        Instant codeIssued = NOW.minusSeconds(30);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, ACCESS, NOW, NOW.plusSeconds(300),
                Set.of("openid", "profile"));
        org.springframework.security.oauth2.server.authorization.OAuth2Authorization missingClaims =
                org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                        .withRegisteredClient(registeredClient())
                        .id("authorization-1")
                        .principalName("42")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizedScopes(Set.of("openid", "profile"))
                        .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest())
                        .token(new org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode(
                                CODE, codeIssued, codeIssued.plusSeconds(60)))
                        .accessToken(accessToken)
                        .build();

        service.cacheConsumedAuthorization(missingClaims, null, binding(CODE));

        assertThatThrownBy(() -> service.save(missingClaims))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant"));
        verify(authorizations, never()).save(any());
        verify(authorizations, never()).finalizeAuthorizationCodeExchange(any(), any());
    }

    @Test
    void consent_save_find_and_remove_use_the_account_and_registered_client_key() {
        OAuth2AuthorizationConsent spring = OAuth2AuthorizationConsent.withId("22", "42")
                .scope("openid").scope("profile").build();
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));
        when(consents.findByAccountIdAndRegisteredClientId(42L, 22L)).thenReturn(Optional.empty());
        when(consents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        consentService.save(spring);

        ArgumentCaptor<OAuthConsent> saved = ArgumentCaptor.forClass(OAuthConsent.class);
        verify(consents).save(saved.capture());
        assertThat(saved.getValue().principalAccountId()).isEqualTo(42L);
        assertThat(saved.getValue().registeredClientId()).isEqualTo(22L);
        assertThat(saved.getValue().scopes()).containsExactlyInAnyOrder("openid", "profile");

        when(consents.findByAccountIdAndRegisteredClientId(42L, 22L))
                .thenReturn(Optional.of(saved.getValue()));
        assertThat(consentService.findById("22", "42").getScopes())
                .containsExactlyInAnyOrder("openid", "profile");

        consentService.remove(spring);
        verify(consents).remove(42L, 22L);
    }

    @Test
    void consent_save_replaces_the_existing_scope_snapshot_instead_of_unioning_it() {
        OAuthConsent existing = OAuthConsent.restore(
                9L, 42L, 22L, Set.of("openid", "profile"), NOW.minusSeconds(60), NOW.minusSeconds(30));
        OAuth2AuthorizationConsent narrowed = OAuth2AuthorizationConsent.withId("22", "42")
                .scope("openid").build();
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));
        when(consents.findByAccountIdAndRegisteredClientId(42L, 22L)).thenReturn(Optional.of(existing));
        when(consents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        consentService.save(narrowed);

        ArgumentCaptor<OAuthConsent> saved = ArgumentCaptor.forClass(OAuthConsent.class);
        verify(consents).save(saved.capture());
        assertThat(saved.getValue().scopes()).containsExactly("openid");
    }

    @Test
    void disabled_removed_or_malformed_consent_keys_are_invisible() {
        when(clients.findById(22L)).thenReturn(Optional.of(disabledClient()));

        assertThat(consentService.findById("22", "42")).isNull();
        assertThat(consentService.findById("not-a-client-id", "42")).isNull();
        assertThat(consentService.findById("22", "not-an-account-id")).isNull();
        verifyNoInteractions(consents);
    }

    @Test
    void removed_consent_is_not_reconstructed_for_an_active_client() {
        when(clients.findById(22L)).thenReturn(Optional.of(activeClient()));
        when(consents.findByAccountIdAndRegisteredClientId(42L, 22L)).thenReturn(Optional.empty());

        assertThat(consentService.findById("22", "42")).isNull();
    }

    private void stubOwnership(OAuthClient client) {
        when(clients.findById(22L)).thenReturn(Optional.of(client));
        when(subjects.findByAccountId(42L)).thenReturn(Optional.of(
                OAuthSubject.restore(7L, 42L, SUBJECT, NOW.minusSeconds(300))));
        when(accounts.findById(42L)).thenReturn(Optional.of(account(202L)));
    }

    private Account account(long companyId) {
        return Account.restore(
                42L, companyId, 77L, "member@example.com", "hash", AccountStatus.ACTIVE,
                false, 0, null, Set.of(AccountRole.USER), 0,
                NOW.minusSeconds(300), NOW.minusSeconds(60));
    }

    private org.springframework.security.oauth2.server.authorization.OAuth2Authorization springAuthorization() {
        return springAuthorization(CODE);
    }

    private org.springframework.security.oauth2.server.authorization.OAuth2Authorization springAuthorization(
            String rawCode) {
        Instant codeIssued = NOW.minusSeconds(30);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, ACCESS, NOW, NOW.plusSeconds(300),
                Set.of("openid", "profile"));
        return org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .withRegisteredClient(registeredClient())
                .id("authorization-1")
                .principalName("42")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid", "profile"))
                .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest())
                .attribute(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(
                        "42", "N/A", List.of()))
                .token(new org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode(
                        rawCode, codeIssued, codeIssued.plusSeconds(60)))
                .token(accessToken, metadata -> metadata.put(
                        org.springframework.security.oauth2.server.authorization.OAuth2Authorization.Token
                                .CLAIMS_METADATA_NAME,
                        Map.of("jti", "access-jti", "aud", List.of("auth-study-userinfo"))))
                .refreshToken(new OAuth2RefreshToken(REFRESH, NOW, NOW.plus(Duration.ofDays(7))))
                .build();
    }

    private OAuthAuthorizationCodeExchangeBinding binding(String rawCode) {
        return new OAuthAuthorizationCodeExchangeBinding(
                sha256(rawCode), NOW.minusSeconds(30), NOW.plusSeconds(30),
                "authorization-1", 22L, "42",
                AuthorizationGrantType.AUTHORIZATION_CODE.getValue(), Set.of("openid", "profile"),
                new OAuthAuthorizationCodeExchangeBinding.AuthorizationRequest(
                        "http://idp.localhost:8080/oauth2/authorize", "public-id", CALLBACK.toString(),
                        Set.of("openid", "profile"), "opaque-state", "opaque-nonce",
                        CHALLENGE, "S256"));
    }

    private org.springframework.security.oauth2.server.authorization.OAuth2Authorization
            springAuthorizationWithoutIssuedTokens() {
        Instant codeIssued = NOW.minusSeconds(30);
        return org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .withRegisteredClient(registeredClient())
                .id("authorization-1")
                .principalName("42")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid", "profile"))
                .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest())
                .attribute(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(
                        "42", "N/A", List.of()))
                .token(new org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode(
                        CODE, codeIssued, codeIssued.plusSeconds(60)))
                .build();
    }

    private org.springframework.security.oauth2.server.authorization.OAuth2Authorization refreshSaveCandidate(
            org.springframework.security.oauth2.server.authorization.OAuth2Authorization source) {
        var code = source.getToken(
                org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class).getToken();
        OAuth2AccessToken replacement = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, ACCESS + "-refresh", NOW.plusSeconds(1),
                NOW.plusSeconds(301), Set.of("openid", "profile"));
        return org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(source)
                .token(code, metadata -> metadata.put(
                        org.springframework.security.oauth2.server.authorization.OAuth2Authorization.Token
                                .INVALIDATED_METADATA_NAME,
                        true))
                .token(replacement, metadata -> metadata.put(
                        org.springframework.security.oauth2.server.authorization.OAuth2Authorization.Token
                                .CLAIMS_METADATA_NAME,
                        Map.of("jti", "refresh-access-jti", "aud", List.of("auth-study-userinfo"))))
                .build();
    }

    private OAuthAuthorization domainAuthorization() {
        OAuthAuthorization authorization = OAuthAuthorization.restore(
                "authorization-1", 22L, SUBJECT, 42L, 202L,
                AuthorizationGrantType.AUTHORIZATION_CODE.getValue(), Set.of("openid", "profile"),
                new OAuthAuthorization.Attributes(
                        "42", "http://idp.localhost:8080/oauth2/authorize",
                        new OAuthAuthorization.AuthorizationRequest(
                                CALLBACK.toString(), Set.of("openid", "profile"), "opaque-state",
                                CHALLENGE, "S256", "opaque-nonce")),
                null, NOW.minusSeconds(120), OAuthAuthorization.Status.ACTIVE, null,
                NOW.minusSeconds(60), NOW.plus(Duration.ofDays(7)), null, null, null, null);
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), sha256(CODE), CALLBACK, CHALLENGE, "opaque-nonce",
                NOW.minusSeconds(30), NOW.plusSeconds(30)));
        authorization.attachAccessToken(OAuthAccessToken.issue(
                authorization.id(), sha256(ACCESS), "access-jti", "auth-study-userinfo",
                NOW, NOW.plusSeconds(300)));
        authorization.attachRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), sha256(REFRESH), UUID.fromString("8bf63cf0-8206-4daa-9943-63847162301a"),
                NOW, NOW.plus(Duration.ofDays(7))));
        return authorization;
    }

    private OAuth2AuthorizationRequest authorizationRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://idp.localhost:8080/oauth2/authorize")
                .clientId("public-id")
                .redirectUri(CALLBACK.toString())
                .scopes(Set.of("openid", "profile"))
                .state("opaque-state")
                .additionalParameters(parameters -> {
                    parameters.put("code_challenge", CHALLENGE);
                    parameters.put("code_challenge_method", "S256");
                    parameters.put("nonce", "opaque-nonce");
                })
                .build();
    }

    private RegisteredClient registeredClient() {
        return RegisteredClient.withId("22")
                .clientId("public-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(CALLBACK.toString())
                .scope("openid").scope("profile")
                .build();
    }

    private OAuthClient activeClient() {
        return client(OAuthClientStatus.ACTIVE);
    }

    private OAuthClient disabledClient() {
        return client(OAuthClientStatus.DISABLED);
    }

    private OAuthClient client(OAuthClientStatus status) {
        return OAuthClient.restore(
                22L, 202L, "public-id", "Public RP", status,
                OAuthClientTrust.TRUSTED_FIRST_PARTY, true, 0,
                Set.of(CALLBACK), Set.of(), Set.of("openid", "profile"), Set.of(),
                NOW.minusSeconds(300), NOW.minusSeconds(60));
    }

    private OAuthSecurityProperties properties() {
        return new OAuthSecurityProperties(
                URI.create("http://idp.localhost:8080"), Duration.ofSeconds(60),
                Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(7),
                Duration.ofMinutes(30), Duration.ofHours(8), "IDP_AUTH_SESSION",
                "auth-study-userinfo");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
