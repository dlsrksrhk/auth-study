package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthConsentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class IdpConsentController {
    private static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType("state");

    private final OAuth2AuthorizationService authorizations;
    private final OAuthConsentService consents;

    public IdpConsentController(OAuth2AuthorizationService authorizations, OAuthConsentService consents) {
        this.authorizations = authorizations;
        this.consents = consents;
    }

    @GetMapping("/idp/consent")
    String consent(@RequestParam("client_id") String clientId,
            @RequestParam String state,
            Authentication authentication,
            HttpServletResponse response,
            Model model) {
        PendingView pending = pending(clientId, state, authentication);
        if (pending == null) return stale(response, model);
        model.addAttribute("clientId", clientId);
        model.addAttribute("state", state);
        model.addAttribute("clientDisplayName", pending.review().clientDisplayName());
        model.addAttribute("requestedScopes", pending.review().requestedScopes());
        model.addAttribute("newScopes", pending.review().newlyRequestedScopes());
        return "idp/consent";
    }

    @PostMapping("/idp/consent/deny")
    String deny(@RequestParam("client_id") String clientId,
            @RequestParam String state,
            Authentication authentication,
            HttpServletResponse response,
            Model model) {
        PendingView pending = pending(clientId, state, authentication);
        if (pending == null) return stale(response, model);
        authorizations.remove(pending.authorization());
        OAuth2AuthorizationRequest request = pending.request();
        UriComponentsBuilder callback = UriComponentsBuilder.fromUriString(request.getRedirectUri())
                .queryParam("error", "access_denied");
        if (request.getState() != null) callback.queryParam("state", request.getState());
        return "redirect:" + callback.build().encode().toUriString();
    }

    private PendingView pending(String clientId, String state, Authentication authentication) {
        if (!(authentication instanceof IdpSessionAuthentication idp)
                || clientId == null || clientId.isBlank() || state == null || state.isBlank()
                || idp.companyId() == null) return null;
        OAuth2Authorization pending = authorizations.findByToken(state, STATE_TOKEN_TYPE);
        if (pending == null || !idp.getName().equals(pending.getPrincipalName())) return null;
        OAuth2AuthorizationRequest request = pending.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (request == null || !clientId.equals(request.getClientId())) return null;
        try {
            OAuthConsentService.ConsentReview review = consents.reviewPending(
                    idp.accountId(), idp.companyId(), idp.sub(), pending.getId(), clientId, request.getScopes());
            return new PendingView(pending, request, review);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String stale(HttpServletResponse response, Model model) {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        model.addAttribute("message", "동의 요청이 만료되었거나 이미 처리되었습니다.");
        return "idp/error";
    }

    private record PendingView(OAuth2Authorization authorization,
            OAuth2AuthorizationRequest request, OAuthConsentService.ConsentReview review) { }
}
