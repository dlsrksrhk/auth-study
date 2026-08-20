package com.sweet.authstudy.identity;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.UUID;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.security.refresh-cookie.name=CUSTOM_REFRESH")
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class CustomRefreshCookieIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    @Test
    void refresh_reads_the_configured_cookie_name() throws Exception {
        Account account = accountRepository.save(Account.createSystemAdmin(
                "cookie-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        String setCookie = mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + account.loginEmail()
                                + "\",\"password\":\"SystemPassword1234!\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("CUSTOM_REFRESH=")))
                .andReturn().getResponse().getHeader("Set-Cookie");
        String rawRefresh = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("CUSTOM_REFRESH", rawRefresh))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("CUSTOM_REFRESH=")));
    }
}
