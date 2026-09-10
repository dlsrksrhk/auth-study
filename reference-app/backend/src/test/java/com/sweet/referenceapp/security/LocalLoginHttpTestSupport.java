package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.referenceapp.ReferenceApplication;
import com.sweet.referenceapp.support.PostgresContainerConfiguration;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.*;
import java.net.http.*;
import java.util.*;

@SpringBootTest(
        classes = ReferenceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({PostgresContainerConfiguration.class, LocalLoginHttpTestSupport.ProbeConfiguration.class})
@ActiveProfiles("test")
abstract class LocalLoginHttpTestSupport {
    static final MockOidcIssuer ISSUER = new MockOidcIssuer();
    static final int PORT = reservePort();
    static final String BFF = "http://127.0.0.1:" + PORT;
    static final String SPA = "http://127.0.0.1:3100";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static final String HR_ROLES = "https://auth-study.local/claims/roles";
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    static volatile java.util.concurrent.CountDownLatch snapshotCommitted = new java.util.concurrent.CountDownLatch(0);
    static volatile java.util.concurrent.CountDownLatch snapshotRelease = new java.util.concurrent.CountDownLatch(0);
    static volatile boolean snapshotFailure;
    static final java.util.List<jakarta.servlet.http.HttpSession> createdSessions = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.concurrent.atomic.AtomicInteger businessCalls = new java.util.concurrent.atomic.AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(ProbeController.class)
    static class ProbeConfiguration {
        @Bean
        org.springframework.boot.web.servlet.ServletListenerRegistrationBean<jakarta.servlet.http.HttpSessionListener> sessionObserver() {
            return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(new jakarta.servlet.http.HttpSessionListener() {
                @Override public void sessionCreated(jakarta.servlet.http.HttpSessionEvent event) {
                    createdSessions.add(event.getSession());
                }
            });
        }
        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor committedSnapshotGate() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof com.sweet.referenceapp.user.application.AppExternalSnapshotService)) return bean;
                    var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        if (invocation.getMethod().getName().equals("refresh")) {
                            // This decorator invokes the transaction proxy first: its return means commit is complete.
                            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            snapshotCommitted.countDown();
                            assertThat(snapshotRelease.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                            if (snapshotFailure) throw new IllegalStateException("snapshot failure");
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
        @Bean(destroyMethod = "close")
        MockOidcIssuer localIssuer() {
            return ISSUER;
        }
    }

    @TestComponent
    @RestController
    static class ProbeController {
        private final OAuth2AuthorizedClientRepository clients;

        ProbeController(OAuth2AuthorizedClientRepository clients) {
            this.clients = clients;
        }

        @GetMapping("/bff/test-admin")
        @PreAuthorize("hasAuthority('APP_ADMIN')")
        Map<String, Boolean> admin() {
            return Map.of("allowed", true);
        }

        @GetMapping("/bff/test-client")
        Map<String, Boolean> client(HttpServletRequest request, Authentication authentication) {
            OAuth2AuthorizedClient client =
                    clients.loadAuthorizedClient("reference-app", authentication, request);
            return Map.of(
                    "matchesExpectedClient",
                    client != null
                            && client.getAccessToken()
                                    .getTokenValue()
                                    .equals(
                                            "test-access-token-"
                                                    + request.getParameter("exchange")));
        }

        @PostMapping("/bff/test-mutate")
        Map<String, Boolean> mutate() {
            businessCalls.incrementAndGet();
            return Map.of("mutated", true);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("reference.security.bff-origin", () -> BFF);
        registry.add("reference.security.spa-origin", () -> SPA);
        registry.add("reference.token-lifecycle.revocation-uri", () -> ISSUER.origin() + "/revoke");
        registry.add("reference.token-lifecycle.refresh-timeout", () -> "1500ms");
        registry.add("reference.token-lifecycle.read-timeout", () -> "5s");
        String provider = "spring.security.oauth2.client.provider.reference-app.";
        registry.add(provider + "issuer-uri", ISSUER::origin);
        registry.add(provider + "authorization-uri", () -> ISSUER.origin() + "/authorize");
        registry.add(provider + "token-uri", () -> ISSUER.origin() + "/token");
        registry.add(provider + "jwk-set-uri", () -> ISSUER.origin() + "/jwks");
        registry.add(provider + "user-info-uri", () -> ISSUER.origin() + "/userinfo");
        String registration = "spring.security.oauth2.client.registration.reference-app.";
        registry.add(registration + "client-id", () -> MockOidcIssuer.CLIENT_ID);
        registry.add(registration + "client-secret", () -> MockOidcIssuer.CLIENT_SECRET);
        registry.add(registration + "client-authentication-method", () -> "client_secret_basic");
        registry.add(registration + "authorization-grant-type", () -> "authorization_code");
        registry.add(registration + "redirect-uri", () -> BFF + "/login/oauth2/code/reference-app");
        registry.add(
                registration + "scope",
                () -> "openid,profile,email,hr.company,hr.organization,hr.roles");
    }

    @BeforeEach
    void resetDatabaseAndIssuer() {
        ISSUER.reset();
        snapshotFailure = false;
        createdSessions.clear();
        snapshotCommitted = new java.util.concurrent.CountDownLatch(0);
        snapshotRelease = new java.util.concurrent.CountDownLatch(0);
        businessCalls.set(0);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.update("delete from app_bootstrap_state");
                            jdbc.update("delete from app_user");
                            jdbc.update(
                                    "insert into app_bootstrap_state(singleton_key) values (1)");
                        });
    }

    static int reservePort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static HttpResponse<String> send(
            String method, String path, String cookie, String body, String... headers)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(BFF + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (headers.length > 0) request.headers(headers);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("RP_SESSION="))
                .findFirst()
                .orElseThrow()
                .split(";", 2)[0];
    }

    record Pending(String cookie, String state, Map<String, String> parameters) {}

    static Pending begin(String existingCookie) throws Exception {
        var response = send("GET", "/oauth2/authorization/reference-app", existingCookie, "");
        assertThat(response.statusCode()).isEqualTo(302);
        var params =
                MockOidcIssuer.parameters(
                        URI.create(response.headers().firstValue("Location").orElseThrow())
                                .getRawQuery());
        ISSUER.nonce = params.get("nonce");
        return new Pending(
                existingCookie == null ? cookie(response) : existingCookie,
                params.get("state"),
                params);
    }

    static HttpResponse<String> callback(Pending pending, String query) throws Exception {
        return send("GET", "/login/oauth2/code/reference-app?" + query, pending.cookie(), "");
    }

    static String login() throws Exception {
        var pending = begin(null);
        var response = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(response.headers().firstValue("Location")).contains(SPA + "/");
        return cookie(response);
    }

    static String csrfToken(String cookie) throws Exception {
        return JSON.readTree(send("GET", "/bff/csrf", cookie, "").body()).path("csrfToken").asText();
    }

    static HttpResponse<String> logout(String path, String cookie) throws Exception {
        return send("POST", path, cookie, "", "Origin", SPA, "X-CSRF-TOKEN", csrfToken(cookie));
    }

    static void assertNoTokenLeak(HttpResponse<String> response) {
        assertThat(response.body() + response.headers().map())
                .doesNotContain(
                        "test-access-token",
                        "test-refresh-token",
                        MockOidcIssuer.CLIENT_SECRET,
                        "eyJ",
                        "code_verifier");
    }

    static void assertFailure(HttpResponse<String> response, String oldCookie, String code)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .contains(SPA + "/login-error?code=" + code);
        assertDeletion(response);
        var profile = send("GET", "/bff/profile", oldCookie, "");
        assertThat(profile.statusCode()).isEqualTo(401);
        assertThat(profile.headers().allValues("Set-Cookie")).isEmpty();
        assertNoTokenLeak(response);
        assertThat(send("GET", "/bff/test-client?exchange=1", oldCookie, "").statusCode())
                .isEqualTo(401);
    }

    static void assertDeletion(HttpResponse<?> response) {
        assertThat(response.headers().allValues("Set-Cookie"))
                .singleElement()
                .asString()
                .startsWith("RP_SESSION=")
                .contains("Max-Age=0");
    }

    UUID userId(String cookie) throws Exception {
        return UUID.fromString(
                JSON.readTree(send("GET", "/bff/session", cookie, "").body())
                        .path("user")
                        .path("id")
                        .asText());
    }

    Map<String, Object> databaseState() {
        return Map.of(
                "users",
                jdbc.queryForList("select * from app_user order by id"),
                "roles",
                jdbc.queryForList("select * from app_user_role order by app_user_id, role"),
                "bootstrap",
                jdbc.queryForList("select * from app_bootstrap_state"));
    }

    void assertEmptyDatabase() {
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select bootstrapped_user_id from app_bootstrap_state", UUID.class))
                .isNull();
    }
}
