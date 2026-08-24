package com.sweet.authstudy.authorization;

import java.io.IOException;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.error.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SameOriginRequestGuard sameOriginRequestGuard(
            AppSecurityProperties properties, SecurityProblemWriter problemWriter) {
        return new SameOriginRequestGuard(properties, problemWriter);
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            SameOriginRequestGuard sameOriginRequestGuard, SecurityProblemWriter problemWriter,
            @Qualifier("hrJwtDecoder") JwtDecoder hrJwtDecoder) throws Exception {
        return http.securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                                request, response, ErrorCode.UNAUTHENTICATED, "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                                request, response, ErrorCode.FORBIDDEN,
                                "You do not have permission to perform this action.")))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                                request, response, ErrorCode.UNAUTHENTICATED, "Authentication is required."))
                        .jwt(jwt -> jwt.decoder(hrJwtDecoder).jwtAuthenticationConverter(converter)))
                .addFilterBefore(sameOriginRequestGuard, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(passwordChangeOnlyFilter(problemWriter), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    JwtEncoder hrJwtEncoder(AppSecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    @Primary
    JwtDecoder hrJwtDecoder(AppSecurityProperties properties) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private SecretKey secretKey(AppSecurityProperties properties) {
        return new SecretKeySpec(Base64.getDecoder().decode(properties.jwt().secret()), "HmacSHA256");
    }

    private OncePerRequestFilter passwordChangeOnlyFilter(SecurityProblemWriter problemWriter) {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                var authentication = org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedAccount account
                        && account.passwordChangeOnly()
                        && !request.getRequestURI().equals("/api/v1/auth/password")) {
                    problemWriter.write(request, response, ErrorCode.FORBIDDEN,
                            "You do not have permission to perform this action.");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

}
