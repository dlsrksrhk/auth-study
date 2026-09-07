package com.sweet.authstudy;

import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthStudyApplicationTests {

    @Autowired
    private AppSecurityProperties appSecurityProperties;

    @Test
    void contextLoads() {
    }

    @Test
    void binds_refresh_cookie_security_settings() {
        AppSecurityProperties.RefreshCookie refreshCookie = appSecurityProperties.refreshCookie();

        assertThat(refreshCookie.name()).isEqualTo("AUTH_STUDY_REFRESH");
        assertThat(refreshCookie.path()).isEqualTo("/api/v1/auth");
        assertThat(refreshCookie.httpOnly()).isTrue();
        assertThat(refreshCookie.sameSite()).isEqualTo("Lax");
        assertThat(refreshCookie.secure()).isFalse();
    }

}
