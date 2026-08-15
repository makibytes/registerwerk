package de.makibytes.registerwerk.auth.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.session.SessionManagementFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_DEV_SECRET = "registerwerk-dev-jwt-secret-change-in-production!!";

    /**
     * The HS256 decoder for tokens Registerwerk minted itself — session, impersonation and
     * step-up tokens.
     *
     * <p>Exposed as a distinct, named bean so that {@code StepUpTokenValidator} and
     * {@code StepUpTokenIssuer} can inject it <em>by qualifier</em>. Injecting the primary
     * {@link JwtDecoder} there would route a self-minted dual-control token through whichever
     * branch {@link DelegatingJwtDecoder} picked, and — before this existed — through the JWKS
     * decoder outright whenever an OIDC issuer was configured, which made every 4-eyes endpoint
     * unreachable in Entra mode.
     */
    @Bean("localHs256JwtDecoder")
    public JwtDecoder localHs256JwtDecoder(RegisterwerkAuthProperties props, Environment env) {
        String devSecret = props.getDevSecret();
        boolean isDevProfile = List.of(env.getActiveProfiles()).contains("dev")
                || List.of(env.getActiveProfiles()).contains("test");
        if (DEFAULT_DEV_SECRET.equals(devSecret) && !isDevProfile) {
            throw new IllegalStateException(
                "SECURITY: JWT_DEV_SECRET is the default factory value. Set a strong JWT_DEV_SECRET " +
                "before running in any non-dev environment — it signs operator session tokens and " +
                "step-up tokens even when Entra sign-in is enabled.");
        }
        return JwtDecoderFactory.localHs256(devSecret);
    }

    /**
     * The decoder the resource server uses. Routes on the JWS {@code alg} header so a
     * deployment can run Entra sign-in for customers while operators keep built-in HS256
     * login and local TOTP step-up — both portals share URLs, so path-scoped filter chains
     * cannot make that distinction.
     *
     * <p>Fails fast on the factory-default dev secret outside the {@code dev}/{@code test}
     * profiles. That guard now applies even when an OIDC issuer <em>is</em> configured, because
     * locally minted tokens remain accepted in that mode.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Qualifier("localHs256JwtDecoder") JwtDecoder localDecoder,
            RegisterwerkAuthProperties props) {
        if (issuerUri != null && !issuerUri.isBlank()) {
            log.info("Configuring JWT decoder: OIDC issuer {} for RS*/ES* tokens, HS256 for locally minted tokens",
                    issuerUri);
            return new DelegatingJwtDecoder(localDecoder, JwtDecoderFactory.issuerBacked(issuerUri, props));
        }
        log.warn("JWT_ISSUER_URI not set — HS256 mode only. Ensure JWT_DEV_SECRET is a strong random value.");
        return new DelegatingJwtDecoder(localDecoder, null);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PrincipalResolver principalResolver,
            SessionCookieService sessionCookieService,
            // Named explicitly: two JwtDecoder beans exist (this one and localHs256JwtDecoder),
            // so resolving the resource server's decoder by type alone is ambiguous.
            @Qualifier("jwtDecoder") JwtDecoder jwtDecoder) throws Exception {
        http
            // The session token moved from the login response body into an httpOnly
            // `rw_session` cookie (SessionCookieService) — an ambient credential the browser
            // now attaches automatically, which is exactly what CSRF protection exists for.
            // Login/logout/impersonate (under /public) stay exempt: they either have no
            // session yet to protect, or (impersonate) their "proof" is a freshly-minted,
            // unguessable token in the request body rather than the ambient cookie, the same
            // reasoning step-up/dual-control endpoints already rely on.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfConfig.SpaCsrfTokenRequestHandler())
                .ignoringRequestMatchers("/api/v1/public/**", "/actuator/**"))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/public/**",
                    "/api/v1/onboarding/token-info/**",
                    "/api/v1/onboarding/complete",
                    "/api/v1/demo/onchain",
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
                // Deliberately NOT given a cookie-aware bearerTokenResolver here — the default
                // (header-only) resolver stays wired to oauth2ResourceServer() so that Spring
                // Security's automatic "requests carrying a bearer token are CSRF-exempt" rule
                // (added whenever csrf() and oauth2ResourceServer() are both configured) only
                // ever exempts genuine Authorization-header requests, never the ambient
                // rw_session cookie. See SessionCookieAuthorizationHeaderFilter's Javadoc.
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            // Synthesizes an Authorization header from the rw_session cookie for requests that
            // don't carry one — positioned after CsrfFilter (so CSRF validation still sees the
            // original, header-less request) and before BearerTokenAuthenticationFilter (so the
            // resource server still authenticates the request normally).
            .addFilterAfter(new SessionCookieAuthorizationHeaderFilter(sessionCookieService),
                            CsrfFilter.class)
            // Runs after the bearer token has been validated and an Authentication established,
            // so it can swap Entra's identifiers for Registerwerk's. See the filter's Javadoc
            // for why this is a filter rather than a change to ~100 call sites.
            .addFilterAfter(new EntraPrincipalNormalizationFilter(principalResolver),
                            BearerTokenAuthenticationFilter.class)
            // SessionManagementFilter invokes CsrfAuthenticationStrategy after a cookie-backed
            // bearer token is authenticated. That strategy deliberately expires the pre-login
            // token and installs a fresh deferred token. This filter therefore MUST run after
            // SessionManagementFilter: running it after BasicAuthenticationFilter is still too
            // early and leaves the deletion cookie as the final response header, so Angular has
            // no XSRF-TOKEN for its first mutating request (notably impersonation).
            .addFilterAfter(new SpaCsrfConfig.CsrfCookieFilter(), SessionManagementFilter.class);

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
