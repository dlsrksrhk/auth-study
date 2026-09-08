package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

final class HeaderOnlyCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
        delegate.handle(request, response, token);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        return request.getHeader(token.getHeaderName()) == null ? null : delegate.resolveCsrfTokenValue(request, token);
    }
}
