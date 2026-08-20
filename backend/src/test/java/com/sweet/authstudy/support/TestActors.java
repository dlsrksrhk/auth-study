package com.sweet.authstudy.support;

import java.util.Set;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.domain.AccountRole;

public final class TestActors {
    public static final AuthenticatedAccount SYSTEM_ADMIN =
            new AuthenticatedAccount(-1, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false);

    private TestActors() {}
}
