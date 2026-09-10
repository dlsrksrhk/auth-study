package com.sweet.referenceapp.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthTokenLifecycleProperties.class)
public class OAuthTokenHttpConfiguration {
    @Bean OAuthTokenRevoker oauthTokenRevoker(OAuthTokenLifecycleProperties properties) { return new OAuthTokenRevoker(properties); }
    @Bean OAuthSessionTokenService oauthSessionTokenService(OAuthTokenLifecycleProperties properties,
            OidcExternalIdentityMapper mapper, OAuthTokenRevoker revoker) {
        return new OAuthSessionTokenService(properties, mapper, revoker);
    }
}
