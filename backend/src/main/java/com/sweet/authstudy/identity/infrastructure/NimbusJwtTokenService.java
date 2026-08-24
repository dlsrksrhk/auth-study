package com.sweet.authstudy.identity.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.application.AuthTokens;
import com.sweet.authstudy.identity.application.JwtTokenService;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class NimbusJwtTokenService implements JwtTokenService {
    private final JwtEncoder encoder;
    private final AppSecurityProperties properties;
    private final Clock clock;

    public NimbusJwtTokenService(@Qualifier("hrJwtEncoder") JwtEncoder encoder,
            AppSecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AuthTokens issue(AuthenticatedAccount account, boolean passwordChangeOnly) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.jwt().accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(Long.toString(account.accountId()))
                .issuedAt(issuedAt).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", account.roles().stream().map(Enum::name).sorted().toList());
        if (account.companyId() != null) claims.claim("company_id", account.companyId());
        if (account.userId() != null) claims.claim("user_id", account.userId());
        if (passwordChangeOnly) claims.claim("purpose", "PASSWORD_CHANGE");
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return new AuthTokens(token, expiresAt);
    }
}
