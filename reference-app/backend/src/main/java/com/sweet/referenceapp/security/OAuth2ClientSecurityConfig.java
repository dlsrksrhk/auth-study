package com.sweet.referenceapp.security;

import jakarta.servlet.DispatcherType;
import com.sweet.referenceapp.user.application.CurrentAppUserService;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReferenceSecurityProperties.class)
public class OAuth2ClientSecurityConfig {
    @Bean
    org.springframework.boot.web.servlet.ServletListenerRegistrationBean<org.springframework.web.util.HttpSessionMutexListener> sessionMutexListener() {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(new org.springframework.web.util.HttpSessionMutexListener());
    }

    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService sessionRefreshWorkers() {
        return new java.util.concurrent.ThreadPoolExecutor(0, 32, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(), Thread.ofPlatform().daemon().name("session-refresh-", 0).factory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    OAuthSessionRefreshCoordinator sessionRefreshCoordinator(OAuth2AuthorizedClientRepository clients,
            OAuthSessionTokenService tokens, com.sweet.referenceapp.user.application.AppExternalSnapshotService snapshots,
            OAuthTokenRevoker revoker, java.util.concurrent.ExecutorService sessionRefreshWorkers, OAuthTokenLifecycleProperties properties) {
        return new OAuthSessionRefreshCoordinator(clients, tokens, snapshots, revoker, sessionRefreshWorkers,
                java.time.Clock.systemUTC(), properties.refreshTimeout());
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClients() { return new HttpSessionOAuth2AuthorizedClientRepository(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ReferenceSecurityProperties properties,
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients,
            ServerProperties server, AppOidcUserService appOidcUserService, CurrentAppUserService currentUsers, OAuthSessionRefreshCoordinator coordinator, OAuthTokenRevoker revoker) throws Exception {
        var cleaner = new RpSessionCleaner(Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure()), authorizedClients, revoker);
        var csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setHeaderName("X-CSRF-TOKEN");
        var requests = new SessionAuthorizationRequestRepository();
        var failureHandler = new OidcLoginFailureHandler(properties, server);
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.TRACE, "/**").denyAll()
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/bff/login", "/bff/csrf", "/bff/session",
                                "/oauth2/authorization/reference-app", "/login/oauth2/code/reference-app").permitAll()
                        .requestMatchers("/bff/**").authenticated()
                        .anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()))
                .addFilterBefore(new OAuthSessionLifecycleFilter(coordinator, currentUsers, cleaner), AuthorizationFilter.class)
                .addFilterBefore(new CurrentAppUserFilter(currentUsers, cleaner), OAuthSessionLifecycleFilter.class)
                .addFilterBefore(new BffOriginGuard(properties), CsrfFilter.class)
                .addFilterBefore(new OidcCallbackGuard(properties, requests, failureHandler), CsrfFilter.class)
                .oauth2Login(login -> login.authorizedClientRepository(authorizedClients)
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(appOidcUserService))
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(new PkceAuthorizationRequestResolver(registrations))
                                .authorizationRequestRepository(requests))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri("/login/oauth2/code/reference-app"))
                        .failureHandler(failureHandler)
                        .successHandler((request, response, authentication) -> response.sendRedirect(properties.successUri().toString())))
                .headers(headers -> headers
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")));
        return http.build();
    }
}
