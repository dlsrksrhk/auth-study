package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class OidcCallbackGuardFailureTest {
    final ReferenceSecurityProperties properties = new ReferenceSecurityProperties(
            URI.create("http://localhost:8080"), URI.create("http://localhost:3100"));
    final SessionAuthorizationRequestRepository requests = new SessionAuthorizationRequestRepository();
    final OidcLoginFailureHandler failureHandler = new OidcLoginFailureHandler(properties, new ServerProperties());
    final OidcCallbackGuard guard = new OidcCallbackGuard(properties, requests, failureHandler);
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", properties.callbackUri().getPath());

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    MockHttpSession prepare() {
        request.setServletPath(properties.callbackUri().getPath());
        request.setServerPort(8080);
        request.addParameter("state", "expected");
        request.addParameter("code", "code");
        requests.saveAuthorizationRequest(OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://issuer/authorize").clientId("client").state("expected").build(),
                request, new MockHttpServletResponse());
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "secret"));
        return (MockHttpSession) request.getSession(false);
    }

    void assertClean(MockHttpSession session) {
        assertThat(session.isInvalid()).isTrue();
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callbackRuntimeOrServletFailureUsesFixedFailureAndExpiresOnlySessionCookie(boolean servlet) throws Exception {
        var session = prepare();
        var response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "RP_SESSION=partially-issued; Path=/");
        response.addHeader("Set-Cookie", "unrelated=keep; Path=/");
        guard.doFilter(request, response, (req, res) -> {
            if (servlet) throw new ServletException("private detail");
            throw new IllegalStateException("private detail");
        });
        assertClean(session);
        assertThat(response.getRedirectedUrl()).isEqualTo(properties.failureUri().toString());
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .contains("unrelated=keep; Path=/").noneMatch(value -> value.contains("partially-issued"));
        assertThat(response.getContentAsString()).doesNotContain("private detail");
    }

    @Test void successRedirectRuntimeFailureCanRedirectOnceToFixedFailure() throws Exception {
        var session = prepare();
        var response = new MockHttpServletResponse() {
            @Override public void sendRedirect(String location) throws IOException {
                if (location.equals(properties.successUri().toString())) throw new IllegalStateException("private detail");
                super.sendRedirect(location);
            }
        };
        guard.doFilter(request, response, (req, res) -> response.sendRedirect(properties.successUri().toString()));
        assertClean(session);
        assertThat(response.getRedirectedUrl()).isEqualTo(properties.failureUri().toString());
    }

    @Test void redirectIoFailureCleansWithoutRetry() throws Exception {
        var session = prepare();
        var failure = new IOException("disconnected");
        var response = new MockHttpServletResponse() {
            int attempts;
            @Override public void sendRedirect(String location) throws IOException {
                assertThat(++attempts).isEqualTo(1);
                throw failure;
            }
        };
        assertThatThrownBy(() -> guard.doFilter(request, response,
                (req, res) -> response.sendRedirect(properties.successUri().toString()))).isSameAs(failure);
        assertClean(session);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void committedResponseFailureCleansWithoutRedirect(boolean servlet) throws Exception {
        var session = prepare();
        var response = new MockHttpServletResponse();
        Exception failure = servlet ? new ServletException("private") : new IllegalStateException("private");
        assertThatThrownBy(() -> guard.doFilter(request, response, (req, res) -> {
            response.flushBuffer();
            if (failure instanceof ServletException exception) throw exception;
            throw (RuntimeException) failure;
        })).isSameAs(failure);
        assertClean(session);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void exceptionsOutsideCallbackAreNotHandled() {
        request.setServletPath("/bff/profile");
        var session = (MockHttpSession) request.getSession();
        var failure = new IllegalStateException("controller failure");
        assertThatThrownBy(() -> guard.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test void failedFailureRedirectIsNotRetriedAndDoesNotCreateFailureSession() throws Exception {
        var session = prepare();
        var failure = new IllegalStateException("redirect unavailable");
        var response = new MockHttpServletResponse() {
            int attempts;
            @Override public void sendRedirect(String location) {
                assertThat(++attempts).isEqualTo(1);
                throw failure;
            }
        };
        assertThatThrownBy(() -> guard.doFilter(request, response, (req, res) ->
                failureHandler.onAuthenticationFailure(request, response,
                        new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_nonce"))))
                .isSameAs(failure);
        assertClean(session);
        assertThat(response.getHeaders("Set-Cookie")).singleElement().asString().contains("Max-Age=0");
    }
}
