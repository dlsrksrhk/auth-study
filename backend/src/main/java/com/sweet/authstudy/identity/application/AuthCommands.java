package com.sweet.authstudy.identity.application;

public final class AuthCommands {
    private AuthCommands() {
    }

    public record LoginCommand(String email, String password, String ipAddress) {
    }

    public record ChangePasswordCommand(String currentPassword, String newPassword) {
    }
}
