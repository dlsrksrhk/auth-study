package com.sweet.authstudy.oauth.infrastructure;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

final class OAuthSigningJwtEncoder implements JwtEncoder {

    private final OAuthSigningKeySnapshotSource signingKeys;

    OAuthSigningJwtEncoder(OAuthSigningKeySnapshotSource signingKeys) {
        this.signingKeys = signingKeys;
    }

    @Override
    public Jwt encode(JwtEncoderParameters parameters) {
        OAuthSigningKeySnapshot snapshot = signingKeys.requireActive();
        JwsHeader header = JwsHeader.from(parameters.getJwsHeader())
                .algorithm(SignatureAlgorithm.RS256)
                .keyId(snapshot.kid())
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(snapshot.privateJwk())));
        return encoder.encode(JwtEncoderParameters.from(header, parameters.getClaims()));
    }
}
