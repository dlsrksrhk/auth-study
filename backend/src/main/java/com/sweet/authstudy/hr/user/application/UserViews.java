package com.sweet.authstudy.hr.user.application;

import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRole;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class UserViews {

    private UserViews() {
    }

    public record UserView(
            Long id,
            long companyId,
            String code,
            String employeeNumber,
            String name,
            String loginEmail,
            Set<AccountRole> roles,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            long positionId,
            UserStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static UserView from(HrUser user, Account account) {
            return new UserView(
                    user.id(), user.companyId(), user.code(), user.employeeNumber(), user.name(), account.loginEmail(),
                    account.roles(),
                    user.phone(), user.hiredAt(), user.workplace(), user.profileImageUrl(), user.positionId(),
                    user.status(), user.version(), user.createdAt(), user.updatedAt());
        }
    }

    public record CreatedUserView(UserView user, String temporaryPassword) {
    }

    public record UserPage(List<UserView> content, long totalElements, int totalPages) {
    }
}
