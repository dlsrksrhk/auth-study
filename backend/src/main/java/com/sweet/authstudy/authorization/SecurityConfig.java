package com.sweet.authstudy.authorization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    SameOriginRequestGuard sameOriginRequestGuard(AppSecurityProperties properties) {
        return new SameOriginRequestGuard(properties);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            SameOriginRequestGuard sameOriginRequestGuard) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityProblem(
                                response, 401, "UNAUTHENTICATED", "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityProblem(
                                response, 403, "FORBIDDEN", "You do not have permission to perform this action.")))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityProblem(
                                response, 401, "UNAUTHENTICATED", "Authentication is required."))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .addFilterBefore(sameOriginRequestGuard, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(passwordChangeOnlyFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(AppSecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(AppSecurityProperties properties) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private SecretKey secretKey(AppSecurityProperties properties) {
        return new SecretKeySpec(Base64.getDecoder().decode(properties.jwt().secret()), "HmacSHA256");
    }

    private OncePerRequestFilter passwordChangeOnlyFilter() {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                var authentication = org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedAccount account
                        && account.passwordChangeOnly()
                        && !request.getRequestURI().equals("/api/v1/auth/password")) {
                    writeSecurityProblem(response, HttpServletResponse.SC_FORBIDDEN,
                            "FORBIDDEN", "You do not have permission to perform this action.");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

    static void writeSecurityProblem(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        String body = "{\"status\":" + status + ",\"detail\":\"" + detail
                + "\",\"code\":\"" + code + "\",\"traceId\":\"" + UUID.randomUUID()
                + "\",\"fieldErrors\":[]}";
        response.getWriter().write(body);
    }
}
