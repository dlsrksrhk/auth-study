package com.sweet.authstudy.support;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.domain.AccountRole;

import java.util.Set;

public final class TestActors {
    public static final AuthenticatedAccount SYSTEM_ADMIN =
            new AuthenticatedAccount(-1, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false);

    private TestActors() {
    }
}
