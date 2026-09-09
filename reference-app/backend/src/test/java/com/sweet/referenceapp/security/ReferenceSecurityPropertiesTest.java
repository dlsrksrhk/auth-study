package com.sweet.referenceapp.security;

import org.springframework.boot.web.servlet.server.Session.SessionTrackingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReferenceSecurityPropertiesTest {
    static { System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host"); }
    private static final URI VALID = URI.create("https://rp.example");

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ftp://rp.example", "//rp.example", "https:/rp.example", "https://user:pass@rp.example",
            "https://rp.example/", "https://rp.example/path", "https://rp.example?next=x", "https://rp.example#fragment",
            "https://*.example", "https://rp.example:", "https://rp.example:0", "https://rp.example:65536"})
    void rejectsAnyNonOriginConfigurationForEitherPublicAddress(String value) {
        var origin = value == null ? null : URI.create(value);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReferenceSecurityProperties(origin, VALID));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReferenceSecurityProperties(VALID, origin));
    }

    @Test
    void derivesOnlyFixedPathsFromValidatedOrigins() {
        var properties = new ReferenceSecurityProperties(URI.create("http://rp.localhost:8180"), URI.create("http://rp.localhost:3100"));
        assertThat(properties.callbackUri()).hasToString("http://rp.localhost:8180/login/oauth2/code/reference-app");
        assertThat(properties.successUri()).hasToString("http://rp.localhost:3100/");
        assertThat(properties.failureUri()).hasToString("http://rp.localhost:3100/login-error?code=oidc_login_failed");
        var withoutPort = new ReferenceSecurityProperties(VALID, URI.create("https://[::1]"));
        assertThat(withoutPort.callbackUri()).hasToString("https://rp.example/login/oauth2/code/reference-app");
        assertThat(withoutPort.successUri()).hasToString("https://[::1]/");
    }

    @Test
    void defaultProfilePolicyIssuesSecureHostOnlyCookiesAndExpiresThemWithoutNewSession() throws Exception {
        try (var context = new SpringApplicationBuilder(CookieApplication.class).profiles("cookie-policy-test").run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--reference.security.bff-origin=http://127.0.0.1:8180", "--reference.security.spa-origin=https://rp.example",
                "--spring.security.oauth2.client.registration.reference-app.client-id=cookie-test-client",
                "--spring.security.oauth2.client.registration.reference-app.client-secret=cookie-test-secret",
                "--spring.security.oauth2.client.registration.reference-app.authorization-grant-type=authorization_code",
                "--spring.security.oauth2.client.registration.reference-app.redirect-uri=http://127.0.0.1:8180/login/oauth2/code/reference-app",
                "--spring.security.oauth2.client.registration.reference-app.scope=openid",
                "--spring.security.oauth2.client.provider.reference-app.authorization-uri=https://idp.example/authorize",
                "--spring.security.oauth2.client.provider.reference-app.token-uri=https://idp.example/token",
                "--spring.security.oauth2.client.provider.reference-app.jwk-set-uri=https://idp.example/jwks")) {
            var server = context.getBean(ServerProperties.class);
            assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(server.getServlet().getSession().getTrackingModes()).containsExactly(SessionTrackingMode.COOKIE);
            assertThat(server.getServlet().getSession().isPersistent()).isFalse();
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var csrf = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/bff/csrf")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(csrf.statusCode()).isEqualTo(200);
            assertThat(csrf.headers().allValues("Set-Cookie")).singleElement().asString()
                    .startsWith("RP_SESSION=").contains("Path=/", "HttpOnly", "SameSite=Lax", "Secure").doesNotContain("Domain=");
            var failure = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login/oauth2/code/reference-app?code=one&state=unknown")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(failure.statusCode()).isEqualTo(302);
            assertThat(failure.headers().firstValue("Location")).contains("https://rp.example/login-error?code=oidc_login_failed");
            assertThat(failure.headers().allValues("Set-Cookie")).singleElement().asString()
                    .startsWith("RP_SESSION=").contains("Max-Age=0", "Path=/", "HttpOnly", "SameSite=Lax", "Secure").doesNotContain("Domain=");
        }
    }

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({OAuth2ClientSecurityConfig.class, BffLoginController.class, CsrfController.class, AppOidcUserService.class, OidcExternalIdentityMapper.class})
    static class CookieApplication {
        @org.springframework.context.annotation.Bean
        com.sweet.referenceapp.user.application.CurrentAppUserService currentUsers() {
            return org.mockito.Mockito.mock(com.sweet.referenceapp.user.application.CurrentAppUserService.class);
        }
        @org.springframework.context.annotation.Bean
        com.sweet.referenceapp.user.application.AppLocalLoginService localLogin() {
            return org.mockito.Mockito.mock(com.sweet.referenceapp.user.application.AppLocalLoginService.class);
        }
    }
}
