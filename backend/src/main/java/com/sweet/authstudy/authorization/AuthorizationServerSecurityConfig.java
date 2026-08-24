package com.sweet.authstudy.authorization;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.application.IdpSessionStateService;
import com.sweet.authstudy.oauth.application.OAuthConsentService;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.infrastructure.OAuthClientSecretPasswordEncoder;
import com.sweet.authstudy.oauth.infrastructure.OidcUserInfoMapper;
import com.sweet.authstudy.oauth.infrastructure.AtomicAuthorizationCodeClientAuthenticationProvider;
import com.sweet.authstudy.oauth.infrastructure.SpringOAuth2AuthorizationService;
import com.sweet.authstudy.oauth.presentation.IdpLoginController;
import com.sweet.authstudy.oauth.presentation.IdpSessionAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class AuthorizationServerSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            OAuthSecurityProperties properties,
            AuthorizationServerSettings authorizationServerSettings,
            RegisteredClientRepository registeredClients,
            OAuthClientRepository oauthClients,
            IdpSessionStateService sessionStates,
            OAuthConsentService oauthConsents,
            OidcUserInfoMapper userInfoMapper,
            Clock clock,
            ObjectProvider<OAuth2AuthorizationService> authorizationServices,
            ObjectProvider<OAuth2AuthorizationConsentService> consentServices,
            ObjectProvider<JWKSource<SecurityContext>> jwkSources,
            @Qualifier("oauthJwtEncoder") ObjectProvider<JwtEncoder> oauthJwtEncoders,
            @Qualifier("oauthJwtDecoder") ObjectProvider<JwtDecoder> oauthJwtDecoders) throws Exception {
        JWKSource<SecurityContext> jwkSource = jwkSources.getIfAvailable();
        JwtEncoder oauthJwtEncoder = oauthJwtEncoders.getIfAvailable();
        JwtDecoder oauthJwtDecoder = oauthJwtDecoders.getIfAvailable();
        OAuth2AuthorizationService authorizationService = authorizationServices.getIfAvailable();
        OAuth2AuthorizationConsentService consentService = consentServices.getIfAvailable();
        if (jwkSource != null && oauthJwtEncoder != null && oauthJwtDecoder != null
                && authorizationService != null && consentService != null) {
            OAuth2AuthorizationServerConfigurer authorizationServer =
                    OAuth2AuthorizationServerConfigurer.authorizationServer();
            http.setSharedObject(JwtEncoder.class, oauthJwtEncoder);
            http.setSharedObject(JwtDecoder.class, oauthJwtDecoder);
            http.with(authorizationServer, server -> server
                    .registeredClientRepository(registeredClients)
                    .authorizationService(authorizationService)
                    .authorizationConsentService(consentService)
                    .authorizationServerSettings(authorizationServerSettings)
                    .authorizationEndpoint(endpoint -> endpoint
                            .consentPage("/idp/consent")
                            .authenticationProviders(providers -> providers.stream()
                                    .filter(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::isInstance)
                                    .map(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::cast)
                                    .forEach(provider -> provider.setAuthorizationConsentRequired(context -> {
                                        if (!context.getRegisteredClient().getClientSettings()
                                                .isRequireAuthorizationConsent()) {
                                            return false;
                                        }
                                        var approved = context.getAuthorizationConsent();
                                        return approved == null || !approved.getScopes().containsAll(
                                                context.getAuthorizationRequest().getScopes());
                                    }))))
                    .oidc(oidc -> oidc.userInfoEndpoint(userInfo -> userInfo
                            .userInfoMapper(userInfoMapper)
                            .errorResponseHandler(AuthorizationServerSecurityConfig::writeInvalidToken)))
                    .clientAuthentication(clientAuthentication -> clientAuthentication
                            .authenticationConverters(converters -> {
                                if (authorizationService instanceof SpringOAuth2AuthorizationService) {
                                    converters.add(0,
                                            new AtomicAuthorizationCodeClientAuthenticationProvider.Converter());
                                }
                            })
                            .authenticationProviders(providers -> {
                                if (authorizationService instanceof SpringOAuth2AuthorizationService springService) {
                                    providers.add(0, new AtomicAuthorizationCodeClientAuthenticationProvider(
                                            registeredClients, springService,
                                            new OAuthClientSecretPasswordEncoder(), clock));
                                }
                                providers.stream()
                                            .filter(ClientSecretAuthenticationProvider.class::isInstance)
                                            .map(ClientSecretAuthenticationProvider.class::cast)
                                            .forEach(provider -> provider.setPasswordEncoder(
                                                    new OAuthClientSecretPasswordEncoder()));
                            })));
            http.oauth2ResourceServer(resourceServer -> resourceServer
                    .authenticationEntryPoint(AuthorizationServerSecurityConfig::writeInvalidToken)
                    .jwt(jwt -> jwt.decoder(oauthJwtDecoder)));
        }

        IdpBrowserRequestFilter browserRequestFilter = new IdpBrowserRequestFilter(
                properties, oauthClients, sessionStates, oauthConsents, clock);
        return http.securityMatcher("/.well-known/**", "/oauth2/**", "/userinfo", "/connect/logout", "/idp/**")
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/idp/login", "/idp/error", "/idp/idp.css",
                                "/.well-known/**", "/oauth2/token", "/oauth2/jwks",
                                "/userinfo", "/connect/logout")
                        .permitAll()
                        .requestMatchers("/oauth2/authorize", "/idp/password", "/idp/consent/**")
                        .authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/idp/login")))
                .addFilterAfter(browserRequestFilter, SecurityContextHolderFilter.class)
                .build();
    }

    private static void writeInvalidToken(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        new OAuth2ErrorHttpMessageConverter().write(
                error, MediaType.APPLICATION_JSON, new ServletServerHttpResponse(response));
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(OAuthSecurityProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.issuer().toString())
                .authorizationEndpoint("/oauth2/authorize")
                .tokenEndpoint("/oauth2/token")
                .jwkSetEndpoint("/oauth2/jwks")
                .oidcUserInfoEndpoint("/userinfo")
                .oidcLogoutEndpoint("/connect/logout")
                .build();
    }

    @RestController
    static class ProtocolEndpointPlaceholderController {
        @RequestMapping({"/.well-known/**", "/oauth2/**", "/userinfo", "/connect/logout"})
        @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
        void notImplemented() {
        }
    }

    /**
     * Owns the browser-only state which must exist before SAS can authenticate an authorization request.
     * The pending snapshot is an allowlist: it never accepts a verifier, code, token, secret, or arbitrary URI.
     */
    private static final class IdpBrowserRequestFilter extends OncePerRequestFilter {
        private final OAuthSecurityProperties properties;
        private final OAuthClientRepository clients;
        private final IdpSessionStateService sessionStates;
        private final OAuthConsentService consents;
        private final Clock clock;
        private final String issuerOrigin;

        private IdpBrowserRequestFilter(OAuthSecurityProperties properties,
                OAuthClientRepository clients, IdpSessionStateService sessionStates,
                OAuthConsentService consents, Clock clock) {
            this.properties = properties;
            this.clients = clients;
            this.sessionStates = sessionStates;
            this.consents = consents;
            this.clock = clock;
            this.issuerOrigin = properties.issuer().getScheme() + "://" + properties.issuer().getAuthority();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (requiresOriginCheck(request) && !issuerOrigin.equals(request.getHeader("Origin"))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof IdpSessionAuthentication idpAuthentication
                    && isBrowserSessionRequest(request.getRequestURI())) {
                if (!sessionIsCurrent(request, response, idpAuthentication)) {
                    authentication = null;
                } else if (isIdentitySensitiveBrowserRequest(request.getRequestURI())) {
                    IdpSessionStateService.State state = sessionStates.evaluate(
                            idpAuthentication.accountId(), idpAuthentication.companyId(),
                            idpAuthentication.userId(), idpAuthentication.sub());
                    if (state == IdpSessionStateService.State.INVALID) {
                        invalidateSession(request, response);
                        authentication = null;
                    } else {
                        request.getSession(false).setAttribute(
                                IdpLoginController.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE,
                                state == IdpSessionStateService.State.PASSWORD_CHANGE_REQUIRED);
                    }
                }
            }

            if (authentication instanceof IdpSessionAuthentication
                    && passwordChangeRequired(request)
                    && !passwordChangeRouteAllowed(request.getRequestURI())) {
                if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
                    response.sendRedirect("/idp/password");
                } else {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                return;
            }

            if (authentication instanceof IdpSessionAuthentication idp
                    && isConsentApproval(request)) {
                try {
                    Set<String> submittedScopes = submittedScopes(request);
                    OAuthConsentService.ApprovalDecision decision = consents.validateApproval(
                            one(request, "state"), one(request, "client_id"), submittedScopes,
                            idp.accountId(), idp.companyId(), idp.userId(), idp.sub());
                    request.setAttribute(OAuthConsentService.ApprovalDecision.class.getName(), decision);
                } catch (RuntimeException exception) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
            }

            if (isAuthorizationGet(request) && !authenticated(authentication)) {
                IdpLoginController.PendingAuthorizationRequest pending = capture(request);
                if (pending == null) {
                    IdpLoginController.clearPendingBrowserState(request.getSession(false));
                    if (!hasSafeRegisteredRedirect(request)) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    }
                } else {
                    var session = request.getSession(true);
                    IdpLoginController.clearPendingBrowserState(session);
                    session.setAttribute(IdpLoginController.PENDING_AUTHORIZATION_ATTRIBUTE, pending);
                }
            }
            chain.doFilter(request, response);
        }

        private boolean isBrowserSessionRequest(String requestUri) {
            return requestUri.startsWith("/idp/") || requestUri.equals("/oauth2/authorize")
                    || requestUri.equals("/connect/logout");
        }

        private boolean isIdentitySensitiveBrowserRequest(String requestUri) {
            return requestUri.equals("/oauth2/authorize") || requestUri.startsWith("/idp/consent");
        }

        private boolean requiresOriginCheck(HttpServletRequest request) {
            if (!"POST".equals(request.getMethod())) return false;
            return switch (request.getRequestURI()) {
                case "/idp/login", "/idp/password", "/idp/consent/deny",
                        "/oauth2/authorize", "/connect/logout" -> true;
                default -> false;
            };
        }

        private boolean sessionIsCurrent(HttpServletRequest request, HttpServletResponse response,
                IdpSessionAuthentication authentication) {
            var session = request.getSession(false);
            if (session == null) return false;
            Instant now = clock.instant();
            Object lastAccessValue = session.getAttribute(IdpLoginController.LAST_ACCESS_ATTRIBUTE);
            if (!(lastAccessValue instanceof Instant lastAccess)
                    || !now.isBefore(lastAccess.plus(properties.sessionIdleTimeout()))
                    || !now.isBefore(authentication.authenticatedAt().plus(properties.sessionAbsoluteTimeout()))) {
                invalidateSession(request, response);
                return false;
            }
            session.setAttribute(IdpLoginController.LAST_ACCESS_ATTRIBUTE, now);
            return true;
        }

        private void invalidateSession(HttpServletRequest request, HttpServletResponse response) {
            SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // A concurrent invalidation winner already removed the same server-side session.
                }
            }
            IdpLoginController.expireSessionCookie(response, properties);
        }

        private boolean passwordChangeRequired(HttpServletRequest request) {
            var session = request.getSession(false);
            return session != null && Boolean.TRUE.equals(
                    session.getAttribute(IdpLoginController.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE));
        }

        private boolean passwordChangeRouteAllowed(String requestUri) {
            return requestUri.equals("/idp/password") || requestUri.equals("/idp/login")
                    || requestUri.equals("/idp/error") || requestUri.equals("/idp/idp.css");
        }

        private boolean isAuthorizationGet(HttpServletRequest request) {
            return "GET".equals(request.getMethod()) && "/oauth2/authorize".equals(request.getRequestURI());
        }

        private boolean isConsentApproval(HttpServletRequest request) {
            return "POST".equals(request.getMethod())
                    && "/oauth2/authorize".equals(request.getRequestURI());
        }

        private Set<String> submittedScopes(HttpServletRequest request) {
            String[] values = request.getParameterValues("scope");
            if (values == null || values.length == 0) throw new IllegalArgumentException("scope is required");
            Set<String> scopes = new LinkedHashSet<>(Arrays.asList(values));
            if (scopes.size() != values.length || scopes.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("scope must be an exact set");
            }
            return scopes;
        }

        private boolean authenticated(Authentication authentication) {
            return authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal());
        }

        private IdpLoginController.PendingAuthorizationRequest capture(HttpServletRequest request) {
            if (!singleValue(request, "response_type", "code")
                    || !singleValue(request, "code_challenge_method", "S256")) return null;
            String clientId = one(request, "client_id");
            String redirectUri = one(request, "redirect_uri");
            String scope = one(request, "scope");
            String state = one(request, "state");
            String nonce = one(request, "nonce");
            String challenge = one(request, "code_challenge");
            if (clientId == null || redirectUri == null || scope == null
                    || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) return null;
            if (request.getParameterValues("state") != null
                    && request.getParameterValues("state").length != 1) return null;
            if (request.getParameterValues("nonce") != null
                    && request.getParameterValues("nonce").length != 1) return null;
            Set<String> requestedScopes = new LinkedHashSet<>(Arrays.asList(scope.trim().split("\\s+")));
            if (requestedScopes.isEmpty() || requestedScopes.stream().anyMatch(String::isBlank)) return null;
            OAuthClient client = clients.findByClientId(clientId)
                    .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                    .orElse(null);
            URI parsedRedirect;
            try {
                parsedRedirect = URI.create(redirectUri);
            } catch (IllegalArgumentException exception) {
                return null;
            }
            if (client == null || client.id() == null || !client.allowsRedirect(parsedRedirect)
                    || !client.scopes().containsAll(requestedScopes)) return null;
            StringBuilder original = new StringBuilder("/oauth2/authorize");
            appendQuery(original, "response_type", "code");
            appendQuery(original, "client_id", clientId);
            appendQuery(original, "redirect_uri", redirectUri);
            appendQuery(original, "scope", String.join(" ", requestedScopes));
            if (state != null) appendQuery(original, "state", state);
            if (nonce != null) appendQuery(original, "nonce", nonce);
            appendQuery(original, "code_challenge", challenge);
            appendQuery(original, "code_challenge_method", "S256");
            String originalUri = original.toString();
            return new IdpLoginController.PendingAuthorizationRequest(
                    client.id(), clientId, redirectUri, state, nonce, requestedScopes,
                    challenge, "S256", originalUri);
        }

        /**
         * A malformed request with an exact registered callback belongs to SAS so it can return the
         * protocol error to that callback. Only an unknown client/redirect is rejected locally.
         */
        private boolean hasSafeRegisteredRedirect(HttpServletRequest request) {
            String clientId = one(request, "client_id");
            String redirectUri = one(request, "redirect_uri");
            if (clientId == null || redirectUri == null) return false;
            OAuthClient client = clients.findByClientId(clientId)
                    .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                    .orElse(null);
            try {
                return client != null && client.allowsRedirect(URI.create(redirectUri));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private void appendQuery(StringBuilder uri, String name, String value) {
            uri.append(uri.indexOf("?") < 0 ? '?' : '&')
                    .append(name).append('=')
                    .append(org.springframework.web.util.UriUtils.encode(
                            value, java.nio.charset.StandardCharsets.UTF_8));
        }

        private boolean singleValue(HttpServletRequest request, String name, String expected) {
            String[] values = request.getParameterValues(name);
            return values != null && values.length == 1 && expected.equals(values[0]);
        }

        private String one(HttpServletRequest request, String name) {
            String[] values = request.getParameterValues(name);
            return values != null && values.length == 1 && values[0] != null && !values[0].isBlank()
                    ? values[0] : null;
        }
    }
}
