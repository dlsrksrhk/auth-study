package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import java.time.Instant;
import java.util.List;

public interface OAuthProtocolEventQuery {
    List<OAuthProtocolEvent> find(long companyId, String clientId, OAuthProtocolEvent.EventType type,
            OAuthProtocolEvent.Outcome outcome, Instant beforeTime, Long beforeId, int limit);
}
