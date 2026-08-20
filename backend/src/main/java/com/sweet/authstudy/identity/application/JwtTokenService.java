package com.sweet.authstudy.identity.application;

import com.sweet.authstudy.authorization.AuthenticatedAccount;

public interface JwtTokenService {
    AuthTokens issue(AuthenticatedAccount account, boolean passwordChangeOnly);
}
