package com.sweet.authstudy.identity;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.application.AuthTokens.LoginResult;
import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.sweet.authstudy.identity.application.AuthCommands.ChangePasswordCommand;
import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthenticationIntegrationTest {

    @Autowired
    AuthenticationService authenticationService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    CompanyService companyService;
    @Autowired
    PositionService positionService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    Clock clock;
    @Autowired
    MockMvc mvc;

    private CompanyView company;
    private Account account;
    private HrUser user;
    private String temporaryPassword;
    private String email;

    @BeforeEach
    void setUpCompanyUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String code = "C" + suffix.toUpperCase();
        String domain = suffix + ".example";
        email = "kim@" + domain;
        company = companyService.create(SYSTEM_ADMIN, new CreateCompanyCommand(code, "Acme", domain));
        long positionId = positionService.list(SYSTEM_ADMIN, code).stream()
                .filter(position -> position.code().equals("EMPLOYEE"))
                .findFirst().orElseThrow().id();
        user = userRepository.save(HrUser.create(
                company.id(), "U001", "E-1001", "Kim", "010-0000-0000",
                LocalDate.parse("2026-08-20"), "Seoul", null, positionId, clock.instant()));
        temporaryPassword = "Temporary1234!";
        account = accountRepository.save(Account.createCompanyAccount(
                company.id(), user.id(), email, passwordEncoder.encode(temporaryPassword), clock.instant()));
    }

    @Test
    void temporary_password_issues_password_change_only_access_token() {
        LoginResult result = authenticationService.login(
                new LoginCommand(email, temporaryPassword, "127.0.0.1"));

        assertThat(result.mustChangePassword()).isTrue();
        assertThat(result.refreshToken()).isNull();
        assertThat(jwtDecoder.decode(result.tokens().accessToken()).getClaimAsString("purpose"))
                .isEqualTo("PASSWORD_CHANGE");
    }

    @Test
    void fifth_bad_password_locks_login_for_fifteen_minutes() {
        var before = clock.instant();

        IntStream.range(0, 5).forEach(i -> assertThatThrownBy(() ->
                authenticationService.login(new LoginCommand(email, "wrong", "127.0.0.1"))));

        Account locked = accountRepository.findCompanyAccount(company.id(), email).orElseThrow();
        assertThat(locked.failedLoginAttempts()).isEqualTo(5);
        assertThat(locked.lockedUntil()).isBetween(
                before.plus(Duration.ofMinutes(15)), clock.instant().plus(Duration.ofMinutes(15)));
    }

    @Test
    void parallel_bad_passwords_are_all_counted_without_conflict_errors() throws Exception {
        Callable<Throwable> attempt = () -> {
            try {
                authenticationService.login(new LoginCommand(email, "wrong", "127.0.0.1"));
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        };

        try (var executor = Executors.newFixedThreadPool(5)) {
            var futures = IntStream.range(0, 5).mapToObj(i -> executor.submit(attempt)).toList();
            for (var future : futures) {
                assertThat(future.get()).isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
            }
        }
        Account locked = accountRepository.findById(account.id()).orElseThrow();
        assertThat(locked.failedLoginAttempts()).isEqualTo(5);
        assertThat(locked.lockedUntil()).isAfter(clock.instant());
    }

    @Test
    void rejects_ascii_password_longer_than_sixty_four_characters() {
        String tooLong = "Aa1!" + "x".repeat(61);

        assertPasswordValidationFailure(tooLong);
    }

    @Test
    void rejects_password_exceeding_seventy_two_utf8_bytes() {
        String tooManyBytes = "Aa1!" + "가".repeat(23);

        assertThat(tooManyBytes.codePointCount(0, tooManyBytes.length())).isLessThanOrEqualTo(64);
        assertThat(tooManyBytes.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeGreaterThan(72);
        assertPasswordValidationFailure(tooManyBytes);
    }

    @Test
    void password_change_revokes_refresh_and_pending_user_stays_blocked_until_active() {
        LoginResult temporaryLogin = authenticationService.login(
                new LoginCommand(email, temporaryPassword, "127.0.0.1"));
        AuthenticatedAccount principal = new AuthenticatedAccount(
                account.id(), company.id(), user.id(), account.roles(), true);

        authenticationService.changePassword(
                principal, new ChangePasswordCommand(temporaryPassword, "ChangedPassword1234!"));

        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand(email, "ChangedPassword1234!", "127.0.0.1")));
        user.changeStatus(UserStatus.ACTIVE, clock.instant());
        userRepository.save(user);
        LoginResult activeLogin = authenticationService.login(
                new LoginCommand(email, "ChangedPassword1234!", "127.0.0.1"));
        assertThat(activeLogin.mustChangePassword()).isFalse();
        assertThat(activeLogin.refreshToken()).isNotBlank();
        assertThat(temporaryLogin.tokens().accessToken()).isNotBlank();
    }

    @Test
    void refresh_cookie_is_http_only_and_cross_origin_refresh_is_rejected() throws Exception {
        Account system = accountRepository.save(Account.createSystemAdmin(
                "admin-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        String loginJson = "{\"email\":\"" + system.loginEmail()
                + "\",\"password\":\"SystemPassword1234!\"}";
        String cookie = mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON).content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getHeader("Set-Cookie");
        String rawRefresh = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));

        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("AUTH_STUDY_REFRESH", rawRefresh))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("AUTH_STUDY_REFRESH="), containsString("HttpOnly"),
                        containsString("SameSite=Lax"), containsString("Path=/api/v1/auth"))));

        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("AUTH_STUDY_REFRESH", rawRefresh))
                        .header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void unauthenticated_security_response_uses_the_public_error_contract() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Authentication is required."));
    }

    @Test
    void password_change_only_jwt_is_filter_restricted_and_password_change_revokes_active_refresh() throws Exception {
        String temporaryLoginBody = mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + temporaryPassword + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String temporaryAccess = com.jayway.jsonpath.JsonPath.read(temporaryLoginBody, "$.accessToken");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + temporaryAccess))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + temporaryAccess)
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + temporaryPassword
                                + "\",\"newPassword\":\"ChangedPassword1234!\"}"))
                .andExpect(status().isNoContent());

        user.changeStatus(UserStatus.ACTIVE, clock.instant());
        userRepository.save(user);
        var activeLogin = mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"password\":\"ChangedPassword1234!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String activeAccess = com.jayway.jsonpath.JsonPath.read(activeLogin.getContentAsString(), "$.accessToken");
        String setCookie = activeLogin.getHeader("Set-Cookie");
        String activeRefresh = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        mvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + activeAccess)
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"ChangedPassword1234!\","
                                + "\"newPassword\":\"FinalPassword1234!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("AUTH_STUDY_REFRESH", activeRefresh))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized());
    }

    private void assertPasswordValidationFailure(String newPassword) {
        AuthenticatedAccount principal = new AuthenticatedAccount(
                account.id(), company.id(), user.id(), account.roles(), true);
        assertThatThrownBy(() -> authenticationService.changePassword(
                principal, new ChangePasswordCommand(temporaryPassword, newPassword)))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
