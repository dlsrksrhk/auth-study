package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthAdminService;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.security.ActorContext;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

import static com.sweet.authstudy.oauth.presentation.OAuthAdminResponses.CursorResponse;
import static com.sweet.authstudy.oauth.presentation.OAuthAdminResponses.ProtocolEventResponse;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/protocol-events")
public class OAuthProtocolEventAdminController {
    private final OAuthAdminService service;
    private final ActorContext actors;

    public OAuthProtocolEventAdminController(OAuthAdminService service, ActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public CursorResponse<ProtocolEventResponse> list(@PathVariable String companyCode, @PathVariable String clientId,
                                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                                                      @RequestParam(required = false) String cursor, @RequestParam(required = false) OAuthProtocolEvent.EventType type,
                                                      @RequestParam(required = false) OAuthProtocolEvent.Outcome outcome) {
        PageRules.validate(page, size, "occurredAt", Set.of("occurredAt"));
        if (page != 0)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Use cursor pagination for protocol events.");
        var result = service.events(actors.current(), companyCode, clientId, type, outcome, cursor, size);
        return new CursorResponse<>(result.content().stream().map(ProtocolEventResponse::from).toList(), result.nextCursor(), result.hasNext());
    }
}
