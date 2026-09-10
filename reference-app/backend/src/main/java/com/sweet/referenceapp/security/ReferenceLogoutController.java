package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ReferenceLogoutController {
    private final ReferenceLogoutService service;
    private final LogoutHandoffStore handoffs;
    public ReferenceLogoutController(ReferenceLogoutService service, LogoutHandoffStore handoffs) {
        this.service = service;
        this.handoffs = handoffs;
    }
    @PostMapping("/bff/logout")
    ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response, OAuth2AuthenticationToken authentication) {
        privacy(response);
        service.logout(request, response, authentication, false);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/bff/logout/identity-provider")
    ResponseEntity<?> identityProvider(HttpServletRequest request, HttpServletResponse response, OAuth2AuthenticationToken authentication) {
        privacy(response);
        try {
            return ResponseEntity.ok(Map.of("continueUrl", service.logout(request, response, authentication, true).orElseThrow().toString()));
        } catch (ReferenceLogoutService.ContinuationUnavailableException unavailable) {
            return ResponseEntity.status(503).body(Map.of("code", "logout_continuation_unavailable"));
        }
    }
    @GetMapping("/bff/logout/continue/{ticket}")
    ResponseEntity<Void> continuation(@PathVariable String ticket, HttpServletResponse response) {
        privacy(response);
        return handoffs.consume(ticket).map(uri -> ResponseEntity.status(303).location(uri).<Void>build())
                .orElseGet(() -> ResponseEntity.status(410).build());
    }
    private static void privacy(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
