package com.sweet.authstudy.oauth.application;

/**
 * Application boundary for consuming a pending browser consent decision.
 */
public interface OAuthConsentDecisionService {
    void deny(OAuthConsentService.ApprovalDecision decision);
}
