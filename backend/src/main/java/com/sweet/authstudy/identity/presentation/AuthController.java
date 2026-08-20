package com.sweet.authstudy.identity.presentation;

import static com.sweet.authstudy.identity.application.AuthCommands.ChangePasswordCommand;
import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static com.sweet.authstudy.identity.presentation.AuthRequests.ChangePasswordRequest;
import static com.sweet.authstudy.identity.presentation.AuthRequests.LoginRequest;
import static com.sweet.authstudy.identity.presentation.AuthResponses.MeResponse;
import static com.sweet.authstudy.identity.presentation.AuthResponses.TokenResponse;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.application.AuthTokens.LoginResult;
import com.sweet.authstudy.identity.application.AuthTokens.RefreshResult;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationService authenticationService;
    private final AppSecurityProperties properties;

    public AuthController(AuthenticationService authenticationService, AppSecurityProperties properties) {
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        LoginResult result = authenticationService.login(
                new LoginCommand(request.email(), request.password(), servletRequest.getRemoteAddr()));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.refreshToken() != null) response.header(HttpHeaders.SET_COOKIE, cookie(result.refreshToken()).toString());
        return response.body(TokenResponse.from(result.tokens(), result.mustChangePassword()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        RefreshResult result = authenticationService.refresh(readRefreshCookie(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(result.refreshToken()).toString())
                .body(TokenResponse.from(result.tokens(), false));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authenticationService.logout(readRefreshCookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, deleteCookie().toString()).build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthenticatedAccount principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        authenticationService.changePassword(principal,
                new ChangePasswordCommand(request.currentPassword(), request.newPassword()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return MeResponse.from(authenticationService.me(principal));
    }

    private ResponseCookie cookie(String value) {
        var configured = properties.refreshCookie();
        return ResponseCookie.from(configured.name(), value).httpOnly(configured.httpOnly())
                .secure(configured.secure()).sameSite(configured.sameSite()).path(configured.path())
                .maxAge(properties.jwt().refreshTokenTtl()).build();
    }

    private ResponseCookie deleteCookie() {
        var configured = properties.refreshCookie();
        return ResponseCookie.from(configured.name(), "").httpOnly(configured.httpOnly())
                .secure(configured.secure()).sameSite(configured.sameSite()).path(configured.path())
                .maxAge(0).build();
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String configuredName = properties.refreshCookie().name();
        for (Cookie cookie : cookies) {
            if (configuredName.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
