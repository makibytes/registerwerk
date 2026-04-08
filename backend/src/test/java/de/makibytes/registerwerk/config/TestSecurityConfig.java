package de.makibytes.registerwerk.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test-only security configuration that disables JWT validation.
 * Import with {@code @Import(TestSecurityConfig.class)} in integration tests that need
 * to bypass the production OAuth2 resource-server setup.
 */
@TestConfiguration
@EnableWebSecurity
public class TestSecurityConfig {

    /**
     * Replaces the production SecurityFilterChain with one that:
     * <ul>
     *   <li>disables CSRF (stateless test calls)</li>
     *   <li>permits every request so that {@code @WithMockUser} role-based tests work
     *       without a real JWT</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * A no-op {@link JwtDecoder} that accepts any token string and returns a synthetic
     * {@link Jwt} with {@code ROLE_REGISTRY_ADMIN} so that method-security annotations
     * are satisfied when an actual JWT bearer is sent.
     */
    @Bean
    public JwtDecoder testJwtDecoder() {
        return token -> Jwt.withTokenValue(token)
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("sub", "00000000-0000-0000-0000-000000000001")
            .claim("roles", List.of("REGISTRY_ADMIN"))
            .build();
    }
}
