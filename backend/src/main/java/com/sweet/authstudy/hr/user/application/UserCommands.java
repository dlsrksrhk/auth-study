package com.sweet.authstudy.hr.user.application;

import java.time.LocalDate;

public final class UserCommands {

    private UserCommands() {
    }

    public record CreateUserCommand(
            String companyCode,
            String code,
            String employeeNumber,
            String name,
            String loginEmail,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            String positionCode) {
    }

    public record UpdateUserCommand(
            String name,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            String positionCode,
            long version) {
    }
}
