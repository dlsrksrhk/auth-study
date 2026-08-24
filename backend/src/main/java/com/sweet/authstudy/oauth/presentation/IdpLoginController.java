package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IdpLoginController {
    private final OAuthSecurityProperties properties;

    public IdpLoginController(OAuthSecurityProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/idp/login")
    String login(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = request.getSession(true).getId();
        ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), sessionId)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "idp/login";
    }
}
