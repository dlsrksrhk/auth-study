package com.sweet.authstudy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerJwtAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = OAuth2AuthorizationServerJwtAutoConfiguration.class)
@ConfigurationPropertiesScan
public class AuthStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthStudyApplication.class, args);
    }

}
