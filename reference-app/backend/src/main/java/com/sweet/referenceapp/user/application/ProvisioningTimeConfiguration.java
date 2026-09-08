package com.sweet.referenceapp.user.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProvisioningTimeConfiguration {
    @Bean
    Clock provisioningClock() {
        return Clock.systemUTC();
    }
}
