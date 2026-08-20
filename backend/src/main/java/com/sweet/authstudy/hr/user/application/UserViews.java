package com.sweet.authstudy.hr.user.application;

import java.time.Instant;
import java.time.LocalDate;

import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserStatus;

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
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            long positionId,
            UserStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static UserView from(HrUser user, String loginEmail) {
            return new UserView(
                    user.id(), user.companyId(), user.code(), user.employeeNumber(), user.name(), loginEmail,
                    user.phone(), user.hiredAt(), user.workplace(), user.profileImageUrl(), user.positionId(),
                    user.status(), user.version(), user.createdAt(), user.updatedAt());
        }
    }

    public record CreatedUserView(UserView user, String temporaryPassword) {
    }
}
