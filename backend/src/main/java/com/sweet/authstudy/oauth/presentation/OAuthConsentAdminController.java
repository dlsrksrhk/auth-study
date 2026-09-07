package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthAdminService;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.security.ActorContext;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.sweet.authstudy.oauth.presentation.OAuthAdminResponses.*;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}")
public class OAuthConsentAdminController {
    private final OAuthAdminService service;
    private final ActorContext actors;
    public OAuthConsentAdminController(OAuthAdminService service,ActorContext actors) { this.service=service; this.actors=actors; }
    @GetMapping("/consents")
    public PageResponse<ConsentResponse> list(@PathVariable String companyCode,@PathVariable String clientId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        PageRules.validate(page,size,"createdAt",Set.of("createdAt"));
        var result=service.consents(actors.current(),companyCode,clientId,page,size);
        return new PageResponse<>(result.content().stream().map(ConsentResponse::from).toList(),page,size,result.totalElements(),result.totalPages());
    }
    @DeleteMapping("/consents/{subject}")
    public ResponseEntity<Void> revokeConsent(@PathVariable String companyCode,@PathVariable String clientId,@PathVariable UUID subject) {
        service.revokeConsent(actors.current(),companyCode,clientId,subject);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/revoke-authorizations")
    public ResponseEntity<Void> revokeAuthorizations(@PathVariable String companyCode,@PathVariable String clientId) {
        service.revokeAuthorizations(actors.current(),companyCode,clientId);
        return ResponseEntity.noContent().build();
    }
}
