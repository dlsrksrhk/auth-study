package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthClientCommands.CreateClient;
import com.sweet.authstudy.oauth.application.OAuthClientCommands.UpdateClient;
import com.sweet.authstudy.oauth.application.OAuthClientService;
import com.sweet.authstudy.shared.presentation.Locations;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.security.ActorContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

import static com.sweet.authstudy.oauth.presentation.OAuthAdminRequests.*;
import static com.sweet.authstudy.oauth.presentation.OAuthAdminResponses.ClientResponse;
import static com.sweet.authstudy.oauth.presentation.OAuthAdminResponses.OneTimeClientSecretResponse;

@RestController
@RequestMapping("/api/v1/admin")
public class OAuthClientAdminController {
    private static final Set<String> SORTS = Set.of("clientId");
    private final OAuthClientService service;
    private final ActorContext actors;

    public OAuthClientAdminController(OAuthClientService service, ActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/companies/{companyCode}/oauth-clients")
    public PageResponse<ClientResponse> list(@PathVariable String companyCode,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageRules.validate(page, size, "clientId", SORTS);
        var result = service.list(actors.current(), companyCode, page, size);
        return new PageResponse<>(result.content().stream().map(ClientResponse::from).toList(),
                page, size, result.totalElements(), result.totalPages());
    }

    @GetMapping("/oauth-clients")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public PageResponse<ClientResponse> listAll(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String companyCode) {
        PageRules.validate(page, size, "clientId", SORTS);
        var result = service.listAll(actors.current(), companyCode, page, size);
        return new PageResponse<>(result.content().stream().map(ClientResponse::from).toList(),
                page, size, result.totalElements(), result.totalPages());
    }

    @PostMapping("/companies/{companyCode}/oauth-clients")
    public ResponseEntity<OneTimeClientSecretResponse> create(@PathVariable String companyCode,
                                                              @Valid @RequestBody CreateClientRequest request) {
        var result = service.create(actors.current(), new CreateClient(companyCode, request.displayName(),
                request.publicClient(), request.redirectUris(), nullSafe(request.postLogoutRedirectUris()),
                request.scopes(), request.trust()));
        ClientResponse response = ClientResponse.from(result.client());
        return ResponseEntity.created(Locations.resource("api", "v1", "admin", "companies", companyCode,
                "oauth-clients", response.clientId())).body(new OneTimeClientSecretResponse(response, result.oneTimeSecret()));
    }

    @GetMapping("/companies/{companyCode}/oauth-clients/{clientId}")
    public ClientResponse find(@PathVariable String companyCode, @PathVariable String clientId) {
        service.requireClientInCompany(actors.current(), companyCode, clientId);
        return ClientResponse.from(service.find(actors.current(), clientId));
    }

    @PutMapping("/companies/{companyCode}/oauth-clients/{clientId}")
    public ClientResponse update(@PathVariable String companyCode, @PathVariable String clientId,
                                 @Valid @RequestBody UpdateClientRequest request) {
        service.requireClientInCompany(actors.current(), companyCode, clientId);
        return ClientResponse.from(service.update(actors.current(), clientId, new UpdateClient(request.displayName(),
                request.redirectUris(), nullSafe(request.postLogoutRedirectUris()), request.scopes(), request.trust(),
                null, request.version())));
    }

    @PostMapping("/companies/{companyCode}/oauth-clients/{clientId}/rotate-secret")
    public OneTimeClientSecretResponse rotate(@PathVariable String companyCode,
                                              @PathVariable String clientId) {
        service.requireClientInCompany(actors.current(), companyCode, clientId);
        var result = service.rotateSecret(actors.current(), clientId);
        return new OneTimeClientSecretResponse(ClientResponse.from(result.client()), result.oneTimeSecret());
    }

    @PostMapping("/companies/{companyCode}/oauth-clients/{clientId}/disable")
    public ResponseEntity<Void> disable(@PathVariable String companyCode, @PathVariable String clientId,
                                        @Valid @RequestBody ExpectedVersionRequest request) {
        service.requireClientInCompany(actors.current(), companyCode, clientId);
        service.disable(actors.current(), clientId, request.version());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/companies/{companyCode}/oauth-clients/{clientId}/revoke-secret")
    public ClientResponse revokeSecret(@PathVariable String companyCode, @PathVariable String clientId) {
        service.requireClientInCompany(actors.current(), companyCode, clientId);
        return ClientResponse.from(service.revokeSecret(actors.current(), clientId));
    }

    @PostMapping("/companies/{companyCode}/oauth-clients/{clientId}/enable")
    public ResponseEntity<Void> enable(@PathVariable String companyCode, @PathVariable String clientId,
                                       @Valid @RequestBody ExpectedVersionRequest request) {
        service.requireClientInCompany(actors.current(), companyCode, clientId);
        service.enable(actors.current(), clientId, request.version());
        return ResponseEntity.noContent().build();
    }

    private static <T> Set<T> nullSafe(Set<T> values) {
        return values == null ? Set.of() : values;
    }
}
