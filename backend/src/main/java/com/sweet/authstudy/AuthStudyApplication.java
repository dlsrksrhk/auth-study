package com.sweet.authstudy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthStudyApplication.class, args);
    }

}
