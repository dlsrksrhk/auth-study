package com.sweet.authstudy.shared.security;

import com.sweet.authstudy.authorization.AuthenticatedAccount;

public interface ActorContext {
    AuthenticatedAccount current();
}
