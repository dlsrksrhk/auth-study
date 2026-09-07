package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthConsentDecisionService;
import com.sweet.authstudy.oauth.application.OAuthConsentService;
import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
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
import org.springframework.web.util.UriUtils;

@Controller
public class IdpConsentController {
    private static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType("state");

    private final OAuth2AuthorizationService authorizations;
    private final OAuthConsentService consents;
    private final OAuthConsentDecisionService decisionCoordinator;
    private final OAuthProtocolEventService protocolEvents;

    public IdpConsentController(OAuth2AuthorizationService authorizations, OAuthConsentService consents,
                                OAuthConsentDecisionService decisionCoordinator, OAuthProtocolEventService protocolEvents) {
        this.authorizations = authorizations;
        this.consents = consents;
        this.decisionCoordinator = decisionCoordinator;
        this.protocolEvents = protocolEvents;
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
        IdpSessionAuthentication idp = (IdpSessionAuthentication) authentication;
        try {
            OAuthConsentService.ApprovalDecision decision = consents.validateApproval(
                    state, clientId, pending.request().getScopes(), idp.accountId(),
                    idp.companyId(), idp.userId(), idp.sub());
            decisionCoordinator.deny(decision);
            protocolEvents.denied(OAuthProtocolEvent.EventType.CONSENT_DENIED,
                    eventContext(decision, false), "access_denied",
                    OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                            "endpoint", "CONSENT", "scopes", decision.requestedScopes(),
                            "redirect_validated", true, "reason", "ACCESS_DENIED")));
        } catch (RuntimeException exception) {
            return stale(response, model);
        }
        OAuth2AuthorizationRequest request = pending.request();
        StringBuilder callback = new StringBuilder(request.getRedirectUri());
        appendQuery(callback, "error", "access_denied");
        if (request.getState() != null) appendQuery(callback, "state", request.getState());
        return "redirect:" + callback;
    }

    private OAuthProtocolEventService.Context eventContext(
            OAuthConsentService.ApprovalDecision decision, boolean includeAuthorization) {
        return new OAuthProtocolEventService.Context(decision.clientId(), decision.sub(),
                decision.accountId(), decision.companyId(),
                includeAuthorization ? decision.authorizationId() : null);
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

    private void appendQuery(StringBuilder uri, String name, String value) {
        uri.append(uri.indexOf("?") < 0 ? '?' : '&')
                .append(name).append('=')
                .append(UriUtils.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    private record PendingView(OAuth2Authorization authorization,
                               OAuth2AuthorizationRequest request, OAuthConsentService.ConsentReview review) {
    }
}
