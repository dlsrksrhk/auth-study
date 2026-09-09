package com.sweet.referenceapp.user.presentation;

import com.sweet.referenceapp.security.CurrentAppUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProfileController {
    @GetMapping("/bff/profile")
    public ResponseEntity<?> profile(HttpServletRequest request) {
        var current = CurrentAppUser.find(request);
        if (current.isEmpty()) {
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).build();
        }
        var user = current.orElseThrow();
        var snapshot = user.snapshot();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ProfileResponse(snapshot.displayName(), snapshot.email(), snapshot.company(),
                        snapshot.organization(), snapshot.hrRoles().stream().sorted().toList(),
                        user.roles().stream().map(Enum::name).sorted().toList()));
    }

    record ProfileResponse(String displayName, String email, Map<String, Object> company,
            Map<String, Object> organization, List<String> hrRoles, List<String> roles) { }
}
