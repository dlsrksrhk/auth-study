package com.sweet.authstudy.oauth.infrastructure;

import com.nimbusds.jose.jwk.RSAKey;

record OAuthSigningKeySnapshot(String kid, RSAKey privateJwk) { }
