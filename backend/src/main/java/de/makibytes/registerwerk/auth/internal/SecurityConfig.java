package de.makibytes.registerwerk.auth.internal;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_DEV_SECRET = "registerwerk-dev-jwt-secret-change-in-production!!";

    /**
     * Provides a JwtDecoder from the configured OIDC issuer URI, or falls back to an HS256
     * dev decoder when no issuer is configured (e.g. in local Docker Compose without OIDC).
     * The dev key comes from {@link RegisterwerkAuthProperties#getDevSecret()}.
     * Fails fast on startup if the default secret is used outside of the 'dev' Spring profile.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            RegisterwerkAuthProperties props,
            Environment env) {
        if (issuerUri != null && !issuerUri.isBlank()) {
            log.info("Configuring JWT decoder from issuer: {}", issuerUri);
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        String devSecret = props.getDevSecret();
        boolean isDevProfile = List.of(env.getActiveProfiles()).contains("dev")
                || List.of(env.getActiveProfiles()).contains("test");
        if (DEFAULT_DEV_SECRET.equals(devSecret) && !isDevProfile) {
            throw new IllegalStateException(
                "SECURITY: JWT_ISSUER_URI is not set and JWT_DEV_SECRET is the default factory value. " +
                "Set a strong JWT_DEV_SECRET or configure JWT_ISSUER_URI before running in any non-dev environment.");
        }
        log.warn("JWT_ISSUER_URI not set — using HS256 mode. Ensure JWT_DEV_SECRET is set to a strong random value.");
        SecretKey key = new SecretKeySpec(devSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/public/**",
                    "/api/v1/onboarding/token-info/**",
                    "/api/v1/onboarding/complete",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api-docs/**",
                    // /actuator/health/** (not just the exact /actuator/health path) since
                    // Kubernetes probes hit /actuator/health/liveness and /.../readiness —
                    // kubelet sends no JWT, so an exact-path-only matcher makes every pod fail
                    // its readiness probe and crash-loop under the Helm chart.
                    "/actuator/health/**",
                    // Network-restricted to Prometheus/Kong pods only via NetworkPolicy
                    // (deploy/helm/registerwerk/templates/networkpolicy.yaml) — permitting it
                    // here too is defense-in-depth, not the only gate.
                    "/actuator/prometheus"
                ).permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return Collections.emptyList();
        }
        return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toList());
    }
}
