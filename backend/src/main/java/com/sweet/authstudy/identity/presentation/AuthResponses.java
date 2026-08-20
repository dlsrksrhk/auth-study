package com.sweet.authstudy.identity.presentation;

import java.time.Instant;
import java.util.Set;

import com.sweet.authstudy.identity.application.AuthenticationService.MeResult;
import com.sweet.authstudy.identity.application.AuthTokens;
import com.sweet.authstudy.identity.domain.AccountRole;

public final class AuthResponses {
    private AuthResponses() {}
    public record TokenResponse(String accessToken, Instant accessTokenExpiresAt, boolean mustChangePassword) {
        static TokenResponse from(AuthTokens tokens, boolean mustChangePassword) {
            return new TokenResponse(tokens.accessToken(), tokens.accessTokenExpiresAt(), mustChangePassword);
        }
    }
    public record MeResponse(long accountId, String email, Set<AccountRole> roles,
            String userCode, String userName, String companyCode) {
        static MeResponse from(MeResult result) {
            return new MeResponse(result.accountId(), result.email(), result.roles(),
                    result.userCode(), result.userName(), result.companyCode());
        }
    }
}
