package com.sweet.authstudy.oauth.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * Handles authorization-code client authentication before Spring's built-in PKCE authenticator.
 * This is necessary because Spring Authorization Server 1.5 validates PKCE during client
 * authentication, before the grant provider can atomically consume a code. Confirmed protocol
 * failures are instead returned from the repository's short atomic callback so consumption commits.
 */
public final class AtomicAuthorizationCodeClientAuthenticationProvider implements AuthenticationProvider {

    private static final String S256 = "S256";

    private final RegisteredClientRepository registeredClients;
    private final SpringOAuth2AuthorizationService authorizations;
    private final PasswordEncoder secretEncoder;
    private final Clock clock;

    public AtomicAuthorizationCodeClientAuthenticationProvider(
            RegisteredClientRepository registeredClients,
            SpringOAuth2AuthorizationService authorizations,
            PasswordEncoder secretEncoder,
            Clock clock) {
        this.registeredClients = registeredClients;
        this.authorizations = authorizations;
        this.secretEncoder = secretEncoder;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken clientAuthentication =
                (OAuth2ClientAuthenticationToken) authentication;
        Map<String, Object> parameters = clientAuthentication.getAdditionalParameters();
        if (!"authorization_code".equals(parameters.get(OAuth2ParameterNames.GRANT_TYPE))) {
            return null;
        }

        String clientId = clientAuthentication.getPrincipal().toString();
        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null
                || !client.getClientAuthenticationMethods().contains(
                        clientAuthentication.getClientAuthenticationMethod())) {
            throwInvalidClient("authentication_method");
        }
        authenticateSecret(clientAuthentication, client);

        String rawCode = text(parameters.get(OAuth2ParameterNames.CODE));
        if (!StringUtils.hasText(rawCode)) throwInvalidGrant("code");

        String redirectUri = text(parameters.get(OAuth2ParameterNames.REDIRECT_URI));
        String verifier = text(parameters.get("code_verifier"));
        var consumption = authorizations.consumeAuthorizationCode(rawCode, locked -> {
            OAuthAuthorization authorization = locked.authorization();
            OAuthClient currentClient = locked.client();
            OAuthAuthorization.AuthorizationRequest request =
                    authorization.attributes().authorizationRequest();
            Instant now = clock.instant();
            boolean clientMatches = currentClient.status() == OAuthClientStatus.ACTIVE
                    && currentClient.id().toString().equals(client.getId())
                    && currentClient.clientId().equals(client.getClientId())
                    && authorization.registeredClientId() == currentClient.id()
                    && authorization.companyId() == currentClient.companyId()
                    && authorization.activeAt(now)
                    && currentAuthenticationSnapshotMatches(clientAuthentication, client, currentClient, now);
            boolean requestMatches = request != null
                    && currentClient.allowsRedirect(URI.create(request.redirectUri()));
            boolean redirectMatches = requestMatches
                    && locked.code().redirectUri().toString().equals(redirectUri)
                    && locked.code().redirectUri().toString().equals(request.redirectUri());
            boolean verifierMatches = requestMatches
                    && locked.code().codeChallenge().equals(request.codeChallenge())
                    && validS256(verifier, locked.code().codeChallenge())
                    && S256.equals(request.codeChallengeMethod());
            return new ExchangeValidation(
                    clientMatches && redirectMatches && verifierMatches, authorization);
        }).orElse(null);
        if (consumption == null
                || consumption.consumption()
                        != com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode.Consumption.CONSUMED
                || consumption.exchangeResult().isEmpty()
                || !consumption.exchangeResult().orElseThrow().valid()) {
            throwInvalidGrant("code_verifier");
        }

        org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization =
                authorizations.reconstructConsumedAuthorization(
                rawCode, consumption.exchangeResult().orElseThrow().authorization());
        if (authorization == null) throwInvalidGrant("code");
        authorizations.cacheConsumedAuthorization(rawCode, authorization);
        return new OAuth2ClientAuthenticationToken(client,
                clientAuthentication.getClientAuthenticationMethod(),
                clientAuthentication.getCredentials());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private void authenticateSecret(OAuth2ClientAuthenticationToken authentication,
            RegisteredClient client) {
        ClientAuthenticationMethod method = authentication.getClientAuthenticationMethod();
        if (ClientAuthenticationMethod.NONE.equals(method)) return;
        if (!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(method)
                && !ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(method)) {
            throwInvalidClient("authentication_method");
        }
        if (authentication.getCredentials() == null
                || !secretEncoder.matches(authentication.getCredentials().toString(), client.getClientSecret())) {
            throwInvalidClient("client_secret");
        }
        if (client.getClientSecretExpiresAt() != null
                && clock.instant().isAfter(client.getClientSecretExpiresAt())) {
            throwInvalidClient("client_secret_expires_at");
        }
    }

    private boolean validS256(String verifier, String expectedChallenge) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9\\-._~]{43,128}")) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    expectedChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
        }
    }

    private boolean currentAuthenticationSnapshotMatches(
            OAuth2ClientAuthenticationToken authentication, RegisteredClient registeredClient,
            OAuthClient currentClient, Instant now) {
        ClientAuthenticationMethod method = authentication.getClientAuthenticationMethod();
        if (currentClient.publicClient()) return ClientAuthenticationMethod.NONE.equals(method);
        if (!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(method)
                && !ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(method)) {
            return false;
        }
        String authenticatedHash = registeredClient.getClientSecret();
        return authenticatedHash != null && currentClient.secrets().stream().anyMatch(secret ->
                authenticatedHash.equals(secret.secretHash())
                        && secret.revokedAt() == null
                        && (secret.expiresAt() == null || secret.expiresAt().isAfter(now)));
    }

    private String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private void throwInvalidClient(String parameter) {
        throw new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_CLIENT, "Invalid client: " + parameter, null));
    }

    private void throwInvalidGrant(String parameter) {
        throw new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_GRANT, "Invalid grant: " + parameter, null));
    }

    private record ExchangeValidation(boolean valid, OAuthAuthorization authorization) { }

    public static final class Converter implements AuthenticationConverter {
        @Override
        public Authentication convert(HttpServletRequest request) {
            if (!"authorization_code".equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                    || request.getHeader("Authorization") != null) {
                return null;
            }
            String clientId = single(request, OAuth2ParameterNames.CLIENT_ID, true);
            if (clientId == null) return null;
            Map<String, Object> additional = new HashMap<>();
            request.getParameterMap().forEach((name, values) -> {
                if (!OAuth2ParameterNames.CLIENT_ID.equals(name)) {
                    additional.put(name, values.length == 1 ? values[0] : values.clone());
                }
            });
            return new OAuth2ClientAuthenticationToken(
                    clientId, ClientAuthenticationMethod.NONE, null, additional);
        }

        private String single(HttpServletRequest request, String name, boolean required) {
            String[] values = request.getParameterValues(name);
            if (values == null || values.length == 0) {
                if (required) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
                return null;
            }
            if (values.length != 1 || !StringUtils.hasText(values[0])) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
            }
            return values[0];
        }
    }
}
