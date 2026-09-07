package com.sweet.authstudy.identity.application;

import java.time.Instant;

public record AuthTokens(String accessToken, Instant accessTokenExpiresAt) {
    public record LoginResult(AuthTokens tokens, boolean mustChangePassword, String refreshToken) {
        public String accessToken() {
            return tokens.accessToken();
        }

        public Instant accessTokenExpiresAt() {
            return tokens.accessTokenExpiresAt();
        }
    }

    public record RefreshResult(AuthTokens tokens, String refreshToken) {
        public String accessToken() {
            return tokens.accessToken();
        }

        public Instant accessTokenExpiresAt() {
            return tokens.accessTokenExpiresAt();
        }
    }
}
