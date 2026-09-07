package com.sweet.authstudy.hr.user.presentation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.shared.validation.ValidCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class UserRequests {
    private UserRequests() {
    }

    public record CreateUserRequest(
            @ValidCode String code,
            @NotBlank @Size(max = 50) String employeeNumber,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String loginEmail,
            @NotBlank @Size(max = 50) String phone,
            @NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate hiredAt,
            @NotBlank @Size(max = 100) String workplace,
            @Size(max = 2048) String profileImageUrl,
            @ValidCode String positionCode) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 50) String phone,
            @NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate hiredAt,
            @NotBlank @Size(max = 100) String workplace,
            @Size(max = 2048) String profileImageUrl,
            @ValidCode String positionCode,
            @NotNull Long version) {
    }

    public record ChangeUserStatusRequest(
            @NotNull UserStatus status,
            @NotNull Long version) {
    }

    public record TemporaryPasswordResponse(String temporaryPassword) {
    }
}
