package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppLocalLoginService;
import com.sweet.referenceapp.user.application.CurrentAppUserService;
import com.sweet.referenceapp.user.application.LocalUserDisabledException;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.presentation.ProfileController;
import com.sweet.referenceapp.user.presentation.SessionController;
import org.springframework.beans.factory.annotation.Autowired;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ClientConfigurationIntegrationTest extends HttpSecurityTestSupport {
    @Test
    void disabledLocalLoginUsesDistinctFixedErrorAndInvalidatesExistingSession() throws Exception {
        var authenticated = login();
        var pending = begin(authenticated);
        when(localLogin.login(any())).thenThrow(new LocalUserDisabledException());
        var response = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(SPA + "/login-error?code=local_user_disabled");
        assertThat(response.headers().allValues("Set-Cookie")).singleElement().asString().contains("Max-Age=0");
        assertThat(send("GET", "/bff/test", authenticated, "").statusCode()).isEqualTo(401);
        assertNoTokenLeak(response);
        assertSecurityHeaders(response);
    }

    @Test
    void localDatabaseFailureUsesGenericCleanupWithoutLeakingDetails() throws Exception {
        var pending = begin(null);
        when(localLogin.login(any())).thenThrow(new IllegalStateException("private-database-secret"));
        var response = callback(pending, "code=valid-code&state=" + pending.state());
        assertFailed(response, pending.cookie());
        assertThat(response.body() + response.headers().map()).doesNotContain("private-database-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"numeric-name", "numeric-email", "numeric-sub", "missing-sub", "mismatched-sub", "userinfo-error"})
    void invalidUserInfoFailsBeforeLocalLogin(String fault) throws Exception {
        var pending = begin(null);
        ISSUER.fault = fault;
        assertFailed(callback(pending, "code=valid-code&state=" + pending.state()), pending.cookie());
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(1);
        verifyNoInteractions(localLogin);
    }

    @Test
    void externalDisabledErrorCodeCannotSelectLocalDisabledRedirect() throws Exception {
        var pending = begin(null);
        assertFailed(callback(pending, "error=local_user_disabled&state=" + pending.state()), pending.cookie());
        verifyNoInteractions(localLogin);
    }

    @Test
    void signedLoginRotatesSessionAndKeepsTokensOnlyInThatSession() throws Exception {
        var pending = begin(null);
        var response = callback(pending, "code=valid-code&state=" + pending.state() + "&returnTo=https://attacker.example");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(SPA + "/");
        var authenticatedCookie = cookie(response);
        assertThat(authenticatedCookie).isNotEqualTo(pending.cookie());
        assertThat(send("GET", "/bff/test", pending.cookie(), "").statusCode()).isEqualTo(401);
        var session = send("GET", "/bff/test", authenticatedCookie, "");
        assertThat(session.statusCode()).isEqualTo(200);
        var info = JSON.readTree(session.body());
        assertThat(info.get("authorizedClient").asBoolean()).isTrue();
        assertThat(info.get("principalType").asText()).isEqualTo("OAuth2AuthenticationToken");
        assertThat(info.get("principalName").asText()).isEqualTo("external-user-1");
        assertThat(info.get("registrationId").asText()).isEqualTo("reference-app");
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(1);
        assertThat(info.get("authorities").toString()).isEqualTo("[\"APP_USER\"]");
        assertThat(info.get("timeout").asInt()).isEqualTo(1800);
        assertThat(info.get("encodedUrl").asText()).isEqualTo("/bff/test");
        var expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(ISSUER.tokenForm.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
        assertThat(expectedChallenge).isEqualTo(pending.parameters().get("code_challenge"));
        assertThat(ISSUER.tokenForm).containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "valid-code")
                .containsEntry("redirect_uri", BFF + "/login/oauth2/code/reference-app");
        assertThat(ISSUER.clientAuthorization).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                (MockOidcIssuer.CLIENT_ID + ":" + MockOidcIssuer.CLIENT_SECRET).getBytes(StandardCharsets.US_ASCII)));
        assertNoTokenLeak(response);
        assertNoTokenLeak(session);
        assertSecurityHeaders(response);
        assertSessionCookie(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "mismatch", "duplicate", "empty-state", "missing-code", "empty-code", "duplicate-code", "duplicate-error", "code-and-error"})
    void invalidCallbackShapeFailsBeforeTokenExchangeAndDiscardsSession(String scenario) throws Exception {
        var pending = begin(null);
        String query = switch (scenario) {
            case "missing" -> "code=valid-code";
            case "mismatch" -> "code=valid-code&state=wrong-state";
            case "duplicate" -> "code=valid-code&state=" + pending.state() + "&state=" + pending.state();
            case "empty-state" -> "code=valid-code&state=";
            case "missing-code" -> "state=" + pending.state();
            case "empty-code" -> "code=&state=" + pending.state();
            case "duplicate-code" -> "code=one&code=two&state=" + pending.state();
            case "duplicate-error" -> "error=access_denied&error=access_denied&state=" + pending.state();
            default -> "code=valid-code&error=access_denied&state=" + pending.state();
        };
        assertFailed(callback(pending, query), pending.cookie());
        assertThat(ISSUER.tokenRequestCount()).isZero();
    }

    @Test
    void callbackWithoutSessionFailsWithoutCreatingOne() throws Exception {
        var response = send("GET", "/login/oauth2/code/reference-app?code=one&state=unknown", null, "");
        assertFailed(response, null);
        assertThat(ISSUER.tokenRequestCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example", "127.0.0.1:1"})
    void callbackAuthorityMismatchCannotBeOverriddenByForwardedHeaders(String authority) throws Exception {
        var pending = begin(null);
        var response = send("GET", "/login/oauth2/code/reference-app?code=valid-code&state=" + pending.state(), pending.cookie(), "",
                "Host", authority,
                "Forwarded", "host=127.0.0.1:" + PORT + ";proto=http",
                "X-Forwarded-Host", "127.0.0.1", "X-Forwarded-Port", Integer.toString(PORT), "X-Forwarded-Proto", "http");
        assertFailed(response, pending.cookie());
        assertThat(ISSUER.tokenRequestCount()).isZero();
    }

    @Test
    void onlyExactRegistrationCallbackPathAndGetAreAccepted() throws Exception {
        var pending = begin(null);
        var otherPath = send("GET", "/login/oauth2/code/other?code=valid-code&state=" + pending.state(), pending.cookie(), "");
        assertThat(otherPath.statusCode()).isIn(401, 403);
        assertThat(ISSUER.tokenRequestCount()).isZero();
        var post = send("POST", "/login/oauth2/code/reference-app", pending.cookie(), "code=valid-code&state=" + pending.state(),
                "Content-Type", "application/x-www-form-urlencoded");
        assertFailed(post, pending.cookie());
        assertThat(ISSUER.tokenRequestCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-nonce", "wrong-nonce", "wrong-issuer", "wrong-audience", "wrong-azp",
            "wrong-signature", "unknown-kid", "missing-exp", "missing-iat", "expired", "future-iat", "token-error"})
    void invalidTokenOrExchangeFailureUsesCommonCleanup(String fault) throws Exception {
        var pending = begin(null);
        ISSUER.fault = fault;
        var response = callback(pending, "code=valid-code&state=" + pending.state());
        assertFailed(response, pending.cookie());
        assertThat(ISSUER.tokenRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"within-skew", "iat-within-skew"})
    void standardOidcValidationAllowsTimeClaimsWithinSixtySecondSkew(String fault) throws Exception {
        var pending = begin(null);
        ISSUER.fault = fault;
        var response = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(response.headers().firstValue("Location")).contains(SPA + "/");
        assertThat(send("GET", "/bff/test", cookie(response), "").statusCode()).isEqualTo(200);
    }

    @Test
    void callbackReplayInvalidatesAuthenticatedSessionWithoutAnotherExchange() throws Exception {
        var pending = begin(null);
        var success = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(success.headers().firstValue("Location")).contains(SPA + "/");
        String authenticatedCookie = cookie(success);
        var replay = send("GET", "/login/oauth2/code/reference-app?code=valid-code&state=" + pending.state(), authenticatedCookie, "");
        assertFailed(replay, authenticatedCookie);
        assertThat(ISSUER.tokenRequestCount()).isEqualTo(1);
    }

    @Test
    void idpDenialDiscardsExistingAuthenticationAndNeverReflectsErrorDescription() throws Exception {
        var authenticated = login();
        var pending = begin(authenticated);
        int previousExchanges = ISSUER.tokenRequestCount();
        var denial = callback(pending, "error=access_denied&error_description=untrusted-secret-value&state=" + pending.state());
        assertFailed(denial, authenticated);
        assertThat(ISSUER.tokenRequestCount()).isEqualTo(previousExchanges);
        assertThat(denial.body() + denial.headers().map()).doesNotContain("untrusted-secret-value");
    }

    @Test
    void startingNewLoginReplacesSinglePendingRequest() throws Exception {
        var first = begin(null);
        var second = begin(first.cookie());
        assertThat(second.state()).isNotEqualTo(first.state());
        assertFailed(callback(first, "code=valid-code&state=" + first.state()), first.cookie());
        assertThat(ISSUER.tokenRequestCount()).isZero();
    }

    @Test
    void sameSubjectInTwoBrowsersHasIndependentAuthorizedClientsAndSessionLifetime() throws Exception {
        var firstCookie = login();
        var secondCookie = login();
        assertThat(secondCookie).isNotEqualTo(firstCookie);
        var first = send("GET", "/bff/test?expectedExchange=1", firstCookie, "");
        assertThat(JSON.readTree(first.body()).get("matchesExpectedClient").asBoolean()).isTrue();
        var pending = begin(firstCookie);
        assertFailed(callback(pending, "error=access_denied&state=" + pending.state()), firstCookie);
        var second = send("GET", "/bff/test?expectedExchange=2", secondCookie, "");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(second.body()).get("authorizedClient").asBoolean()).isTrue();
        assertThat(JSON.readTree(second.body()).get("matchesExpectedClient").asBoolean()).isTrue();
    }

    @Test
    void loginUsesFixedOriginAndIgnoresRedirectInputsAndForwardedHeaders() throws Exception {
        var response = send("GET", "/bff/login?returnTo=https://attacker.example", null, "",
                "Host", "attacker.example", "Forwarded", "host=attacker.example;proto=https", "X-Forwarded-Host", "attacker.example");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(BFF + "/oauth2/authorization/reference-app");
        assertSecurityHeaders(response);
    }

    @Test
    void authorizationUsesDiscoveryAndConfidentialClientPkce() throws Exception {
        var response = send("GET", "/oauth2/authorization/reference-app?returnTo=https://attacker.example", null, "",
                "Host", "attacker.example", "Forwarded", "host=attacker.example;proto=https", "X-Forwarded-Host", "attacker.example");
        assertThat(response.statusCode()).isEqualTo(302);
        var location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.toString()).startsWith(ISSUER.origin() + "/authorize?");
        var params = MockOidcIssuer.parameters(location.getRawQuery());
        assertThat(params.get("response_type")).isEqualTo("code");
        assertThat(params.get("client_id")).isEqualTo(MockOidcIssuer.CLIENT_ID);
        assertThat(params.get("redirect_uri")).isEqualTo(BFF + "/login/oauth2/code/reference-app");
        assertThat(params.get("scope").split(" ")).containsExactlyInAnyOrder(
                "openid", "profile", "email", "hr.company", "hr.organization", "hr.roles");
        assertThat(params.get("state")).isNotBlank();
        assertThat(params.get("nonce")).isNotBlank();
        assertThat(params.get("code_challenge_method")).isEqualTo("S256");
        assertThat(params.get("code_challenge")).matches("[A-Za-z0-9_-]{43}");
        assertThat(params).doesNotContainKey("code_verifier");
        assertSessionCookie(response);
    }

    @Test
    void protectedApiReturns401WithoutCreatingSessionAndDefaultLoginIsUnavailable() throws Exception {
        for (var path : new String[]{"/bff/test", "/bff/profile"}) {
            var response = send("GET", path, null, "");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(send("GET", "/login", null, "").statusCode()).isIn(401, 403);
        assertThat(send("GET", "/logout", null, "").statusCode()).isIn(401, 403);
        assertThat(send("TRACE", "/bff/test", null, "").statusCode()).isIn(400, 403, 405);
    }
}

@SpringBootTest(classes = HttpSecurityTestSupport.Application.class,
        properties = "server.servlet.session.cookie.secure=false",
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("oidc-http-test")
abstract class HttpSecurityTestSupport {
    static final MockOidcIssuer ISSUER = new MockOidcIssuer();
    static final int PORT = reservePort();
    static final String BFF = "http://127.0.0.1:" + PORT;
    static final String SPA = "http://127.0.0.1:3100";
    static final ObjectMapper JSON = new ObjectMapper();
    static { System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host"); }
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @ComponentScan(basePackages = "com.sweet.referenceapp.security", excludeFilters =
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestComponent.class))
    @Import({ProbeController.class, SessionController.class, ProfileController.class})
    static class Application {
        @Bean(destroyMethod = "close") MockOidcIssuer issuer() { return ISSUER; }
        @Bean AppLocalLoginService localLogin() { return mock(AppLocalLoginService.class); }
        @Bean CurrentAppUserService currentUser() { return mock(CurrentAppUserService.class); }
    }

    @TestComponent
    @RestController
    static class ProbeController {
        private final OAuth2AuthorizedClientRepository clients;
        ProbeController(OAuth2AuthorizedClientRepository clients) { this.clients = clients; }
        @GetMapping("/bff/test")
        Map<String, Object> session(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Authentication authentication) {
            OAuth2AuthorizedClient client = clients.loadAuthorizedClient("reference-app", authentication, request);
            return Map.of("authorizedClient", client != null, "timeout", request.getSession().getMaxInactiveInterval(),
                    "principalType", authentication.getClass().getSimpleName(),
                    "principalName", authentication.getName(),
                    "registrationId", ((org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId(),
                    "encodedUrl", response.encodeURL("/bff/test"),
                    "matchesExpectedClient", client != null && client.getAccessToken().getTokenValue().equals(
                            "test-access-token-" + request.getParameter("expectedExchange")),
                    "authorities", authentication.getAuthorities().stream().map(Object::toString).toList());
        }
        @PostMapping("/bff/test") Map<String, Boolean> mutate() { return Map.of("mutated", true); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("reference.security.bff-origin", () -> BFF);
        registry.add("reference.security.spa-origin", () -> SPA);
        String provider = "spring.security.oauth2.client.provider.reference-app.";
        registry.add(provider + "issuer-uri", ISSUER::origin);
        String registration = "spring.security.oauth2.client.registration.reference-app.";
        registry.add(registration + "client-id", () -> MockOidcIssuer.CLIENT_ID);
        registry.add(registration + "client-secret", () -> MockOidcIssuer.CLIENT_SECRET);
        registry.add(registration + "client-authentication-method", () -> "client_secret_basic");
        registry.add(registration + "authorization-grant-type", () -> "authorization_code");
        registry.add(registration + "redirect-uri", () -> BFF + "/login/oauth2/code/reference-app");
        registry.add(registration + "scope", () -> "openid,profile,email,hr.company,hr.organization,hr.roles");
    }

    @Autowired AppLocalLoginService localLogin;
    @Autowired CurrentAppUserService currentUser;

    @BeforeEach void resetIssuer() {
        ISSUER.reset();
        reset(localLogin, currentUser);
        var template = AppOidcUserServiceTest.local(java.util.Set.of(AppRole.APP_USER));
        var local = new com.sweet.referenceapp.user.application.AppUserView(template.id(), ISSUER.origin(), "external-user-1",
                template.snapshot(), template.status(), template.roles(), template.createdAt(), template.updatedAt(), template.lastLoginAt(), template.version());
        when(localLogin.login(any())).thenReturn(local);
        when(currentUser.find(any())).thenReturn(java.util.Optional.of(local));
    }

    static int reservePort() {
        try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }

    static HttpResponse<String> send(String method, String path, String cookie, String body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BFF + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (headers.length > 0) request.headers(headers);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("RP_SESSION=")).findFirst().orElseThrow().split(";", 2)[0];
    }

    static JsonNode csrf(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }

    record Pending(String cookie, Map<String, String> parameters) {
        String state() { return parameters.get("state"); }
    }

    static Pending begin(String existingCookie) throws Exception {
        var response = send("GET", "/oauth2/authorization/reference-app", existingCookie, "");
        assertThat(response.statusCode()).isEqualTo(302);
        var params = MockOidcIssuer.parameters(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
        ISSUER.nonce = params.get("nonce");
        return new Pending(existingCookie == null ? cookie(response) : existingCookie, params);
    }

    static HttpResponse<String> callback(Pending pending, String query) throws Exception {
        return send("GET", "/login/oauth2/code/reference-app?" + query, pending.cookie(), "");
    }

    static String login() throws Exception {
        var pending = begin(null);
        var success = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(success.headers().firstValue("Location")).contains(SPA + "/");
        return cookie(success);
    }

    static void assertFailed(HttpResponse<String> response, String oldCookie) throws Exception {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(SPA + "/login-error?code=oidc_login_failed");
        var cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.getFirst()).startsWith("RP_SESSION=").contains("Max-Age=0", "Path=/", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=", "Secure");
        assertThat(send("GET", "/bff/test", oldCookie, "").statusCode()).isEqualTo(401);
        var after = send("GET", "/bff/test", oldCookie, "");
        assertThat(after.headers().allValues("Set-Cookie")).isEmpty();
        assertNoTokenLeak(response);
        assertSecurityHeaders(response);
    }

    static void assertNoTokenLeak(HttpResponse<String> response) {
        assertThat(response.body() + response.headers().map()).doesNotContain("test-access-token", "test-refresh-token",
                MockOidcIssuer.CLIENT_SECRET, "eyJ", "code_verifier", "error_description");
    }

    static void assertSessionCookie(HttpResponse<?> response) {
        var value = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith("RP_SESSION=")).findFirst().orElseThrow();
        assertThat(value).contains("Path=/", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=", "Secure", "Max-Age=");
    }

    static void assertSecurityHeaders(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .contains("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }
}
