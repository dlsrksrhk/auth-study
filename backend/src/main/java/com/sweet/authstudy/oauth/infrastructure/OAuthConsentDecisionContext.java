package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthConsentService;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Request-local hand-off between SAS's consent and authorization persistence calls. */
@Component
final class OAuthConsentDecisionContext {
    private static final String VALIDATED = OAuthConsentService.ApprovalDecision.class.getName();
    private static final String STAGED = OAuthConsentDecisionContext.class.getName() + ".STAGED";

    OAuthConsentService.ApprovalDecision validated() {
        return attribute(VALIDATED);
    }

    OAuthConsentService.ApprovalDecision staged() {
        return attribute(STAGED);
    }

    void stage(OAuthConsentService.ApprovalDecision decision) {
        RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
        attributes.setAttribute(STAGED, decision, RequestAttributes.SCOPE_REQUEST);
    }

    void clear() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) attributes.removeAttribute(STAGED, RequestAttributes.SCOPE_REQUEST);
    }

    private OAuthConsentService.ApprovalDecision attribute(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        return value instanceof OAuthConsentService.ApprovalDecision decision ? decision : null;
    }
}
