package com.sweet.referenceapp.security;

import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class BffLoginController {
    private final ReferenceSecurityProperties properties;

    BffLoginController(ReferenceSecurityProperties properties) { this.properties = properties; }

    @GetMapping("/bff/login")
    ResponseEntity<Void> login() {
        return ResponseEntity.status(302).cacheControl(CacheControl.noStore())
                .location(URI.create(properties.bffOrigin() + "/oauth2/authorization/reference-app")).build();
    }
}
