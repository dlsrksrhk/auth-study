package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.util.Set;

public final class OAuthAdminRequests {
    private OAuthAdminRequests() {
    }

    public record CreateClientRequest(@NotBlank @Size(max = 100) String displayName,
                                      boolean publicClient, @NotEmpty Set<URI> redirectUris,
                                      Set<URI> postLogoutRedirectUris, @NotEmpty Set<String> scopes,
                                      OAuthClientTrust trust) {
    }

    public record UpdateClientRequest(@NotBlank @Size(max = 100) String displayName,
                                      @NotEmpty Set<URI> redirectUris, Set<URI> postLogoutRedirectUris,
                                      @NotEmpty Set<String> scopes, OAuthClientTrust trust,
                                      @PositiveOrZero long version) {
    }

    public record ExpectedVersionRequest(@PositiveOrZero long version) {
    }
}
