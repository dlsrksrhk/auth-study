package com.sweet.authstudy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.sweet.authstudy.support.PostgresContainerConfiguration;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthStudyApplicationTests {

    @Test
    void contextLoads() {
    }

}
