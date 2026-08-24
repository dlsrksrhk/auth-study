package com.sweet.authstudy.authorization;

import java.io.IOException;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.infrastructure.OAuthClientSecretPasswordEncoder;
import com.sweet.authstudy.oauth.infrastructure.AtomicAuthorizationCodeClientAuthenticationProvider;
import com.sweet.authstudy.oauth.infrastructure.SpringOAuth2AuthorizationService;
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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
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
            java.time.Clock clock,
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
                    .oidc(Customizer.withDefaults())
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
        }

        return http.securityMatcher("/.well-known/**", "/oauth2/**", "/userinfo", "/connect/logout", "/idp/**")
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/idp/login", "/.well-known/**", "/oauth2/**", "/userinfo", "/connect/logout")
                        .permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/idp/login")))
                .addFilterBefore(new IdpOriginRequestGuard(properties), ExceptionTranslationFilter.class)
                .build();
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

    private static final class IdpOriginRequestGuard extends OncePerRequestFilter {
        private final String issuerOrigin;

        private IdpOriginRequestGuard(OAuthSecurityProperties properties) {
            this.issuerOrigin = properties.issuer().getScheme() + "://" + properties.issuer().getAuthority();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (requiresOriginCheck(request) && !issuerOrigin.equals(request.getHeader("Origin"))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            chain.doFilter(request, response);
        }

        private boolean requiresOriginCheck(HttpServletRequest request) {
            if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                    || "OPTIONS".equals(request.getMethod())) return false;
            return true;
        }
    }
}
