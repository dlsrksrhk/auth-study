package com.sweet.referenceapp.user.presentation;

import com.sweet.referenceapp.security.CurrentAppUser;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    @GetMapping("/bff/session")
    public ResponseEntity<?> session(HttpServletRequest request) {
        var current = CurrentAppUser.find(request);
        if (current.isEmpty()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AnonymousSession(false));
        }
        var user = current.orElseThrow();
        var csrf = Objects.requireNonNull((CsrfToken) request.getAttribute(CsrfToken.class.getName()));
        var responseUser = new SessionUser(user.id(), user.snapshot().displayName(), user.snapshot().email(),
                user.status(), user.roles().stream().map(Enum::name).sorted().toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthenticatedSession(true, responseUser, csrf.getHeaderName(), csrf.getToken()));
    }

    record AnonymousSession(boolean authenticated) { }
    record SessionUser(UUID id, String displayName, String email, AppUserStatus status, List<String> roles) { }
    record AuthenticatedSession(boolean authenticated, SessionUser user, String csrfHeaderName, String csrfToken) { }
}
