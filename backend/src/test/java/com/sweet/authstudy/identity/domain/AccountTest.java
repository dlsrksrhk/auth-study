package com.sweet.authstudy.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-20T01:00:00Z");

    @Test
    void system_account_rejects_non_system_roles_and_cannot_remove_system_admin() {
        Account account = Account.createSystemAdmin(
                "admin@auth-study.local", "hash", false, CREATED_AT);

        assertThatThrownBy(() -> account.addRole(AccountRole.USER, UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.addRole(AccountRole.COMPANY_ADMIN, UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.removeRole(AccountRole.SYSTEM_ADMIN, UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(account.roles()).containsExactly(AccountRole.SYSTEM_ADMIN);
    }

    @Test
    void company_account_allows_company_admin_transition_while_preserving_user_role() {
        Account account = Account.createCompanyAccount(
                1L, 10L, "kim@acme.example", "hash", CREATED_AT);

        account.addRole(AccountRole.COMPANY_ADMIN, UPDATED_AT);
        assertThat(account.roles()).containsExactlyInAnyOrder(AccountRole.USER, AccountRole.COMPANY_ADMIN);

        account.removeRole(AccountRole.COMPANY_ADMIN, UPDATED_AT);
        assertThat(account.roles()).containsExactly(AccountRole.USER);
    }

    @Test
    void company_account_rejects_system_admin_and_cannot_remove_user() {
        Account account = Account.createCompanyAccount(
                1L, 10L, "kim@acme.example", "hash", CREATED_AT);

        assertThatThrownBy(() -> account.addRole(AccountRole.SYSTEM_ADMIN, UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.removeRole(AccountRole.USER, UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(account.roles()).containsExactly(AccountRole.USER);
    }
}
