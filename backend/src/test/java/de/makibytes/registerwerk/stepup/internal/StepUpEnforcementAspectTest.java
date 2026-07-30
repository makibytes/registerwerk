package de.makibytes.registerwerk.stepup.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.stepup.api.ClaimsChallengeException;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Both step-up tracks, side by side. The property that matters most is that they stay separate:
 * a local TOTP token must not satisfy an Entra deployment and vice versa, and the Entra track
 * must produce a <em>401 challenge</em> rather than a 403 so the SPA re-authenticates instead of
 * logging the user out.
 */
@DisplayName("StepUpEnforcementAspect — both modes")
class StepUpEnforcementAspectTest {

    private static final String ACTION = "FORCE_BURN_EWG26";

    private RegisterwerkAuthProperties authProperties;
    private StepUpEntraProperties entraProperties;
    private StepUpTokenValidator validator;
    private StepUpEnforcementAspect aspect;

    @BeforeEach
    void setUp() {
        authProperties = new RegisterwerkAuthProperties();
        entraProperties = new StepUpEntraProperties();
        entraProperties.setAuthContextId("c1");
        validator = mock(StepUpTokenValidator.class);
        aspect = new StepUpEnforcementAspect(validator, new StepUpPolicy(authProperties, entraProperties));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── LOCAL_TOTP ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("local mode: a fresh acr=stepup token is accepted")
    void local_freshStepUpToken_proceeds() throws Throwable {
        authProperties.setEntraEnabled(false);
        authenticate(jwt(Map.of("acr", "stepup"), Instant.now()));

        assertThat(aspect.enforce(joinPoint())).isEqualTo("ok");
    }

    @Test
    @DisplayName("local mode: an ordinary session token is refused with 403 semantics")
    void local_sessionToken_denied() {
        authProperties.setEntraEnabled(false);
        authenticate(jwt(Map.of(), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("acr=stepup");
    }

    @Test
    @DisplayName("local mode: a stale step-up token is refused")
    void local_staleToken_denied() {
        authProperties.setEntraEnabled(false);
        authenticate(jwt(Map.of("acr", "stepup"), Instant.now().minusSeconds(1200)));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("local mode: an acrs claim does not substitute for a step-up token")
    void local_acrsClaim_isNotAccepted() {
        authProperties.setEntraEnabled(false);
        authenticate(jwt(Map.of("acrs", List.of("c1")), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint())).isInstanceOf(AccessDeniedException.class);
    }

    // ── ENTRA_AUTH_CONTEXT ────────────────────────────────────────────────────

    @Test
    @DisplayName("Entra mode: a matching acrs claim with recent auth_time is accepted")
    void entra_matchingAcrs_proceeds() throws Throwable {
        authProperties.setEntraEnabled(true);
        authenticate(jwt(Map.of(
                "acrs", List.of("c1"),
                "auth_time", Instant.now().minusSeconds(60).getEpochSecond()), Instant.now()));

        assertThat(aspect.enforce(joinPoint())).isEqualTo("ok");
    }

    @Test
    @DisplayName("Entra mode: a missing acrs claim produces a 401 claims challenge, not a 403")
    void entra_missingAcrs_challenges() {
        authProperties.setEntraEnabled(true);
        authenticate(jwt(Map.of(), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(ClaimsChallengeException.class)
                // Not an AccessDeniedException — that would be collapsed into a flat 403 by
                // GlobalExceptionHandler and the client would never see the challenge.
                .isNotInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Entra mode: a different authentication context does not satisfy the requirement")
    void entra_wrongAcrs_challenges() {
        authProperties.setEntraEnabled(true);
        authenticate(jwt(Map.of("acrs", List.of("c7")), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(ClaimsChallengeException.class);
    }

    @Test
    @DisplayName("Entra mode: a stale auth_time re-challenges even though acrs matches")
    void entra_staleAuthTime_challenges() {
        authProperties.setEntraEnabled(true);
        authenticate(jwt(Map.of(
                "acrs", List.of("c1"),
                "auth_time", Instant.now().minusSeconds(3600).getEpochSecond()), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(ClaimsChallengeException.class);
    }

    @Test
    @DisplayName("Entra mode: without auth_time, freshness falls back to iat")
    void entra_noAuthTime_fallsBackToIat() throws Throwable {
        authProperties.setEntraEnabled(true);
        authenticate(jwt(Map.of("acrs", List.of("c1")), Instant.now()));

        assertThat(aspect.enforce(joinPoint())).isEqualTo("ok");
    }

    @Test
    @DisplayName("Entra mode: a per-action override takes precedence over the default context")
    void entra_reasonOverride_isUsed() {
        authProperties.setEntraEnabled(true);
        entraProperties.setReasonOverrides(Map.of(ACTION, "c2"));
        authenticate(jwt(Map.of("acrs", List.of("c1")), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(ClaimsChallengeException.class)
                .extracting(e -> ((ClaimsChallengeException) e).getAuthContextId())
                .isEqualTo("c2");
    }

    @Test
    @DisplayName("Entra mode: a local step-up token does not satisfy the auth-context requirement")
    void entra_localStepUpToken_stillChallenges() {
        authProperties.setEntraEnabled(true);
        authenticate(jwt(Map.of("acr", "stepup"), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(ClaimsChallengeException.class);
    }

    @Test
    @DisplayName("Entra mode: no configured authentication context fails closed")
    void entra_unconfiguredContext_failsClosed() {
        authProperties.setEntraEnabled(true);
        entraProperties.setAuthContextId("");
        authenticate(jwt(Map.of("acrs", List.of("c1")), Instant.now()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth-context-id");
    }

    @Test
    @DisplayName("the claims challenge encodes the exact request Entra expects")
    void challenge_encodesExpectedClaimsRequest() {
        ClaimsChallengeException ex = new ClaimsChallengeException("c1", "https://example/authorize", ACTION);

        String decoded = new String(java.util.Base64.getDecoder().decode(ex.claimsBase64()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(decoded).isEqualTo("{\"access_token\":{\"acrs\":{\"essential\":true,\"value\":\"c1\"}}}");
    }

    // ── shared ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a non-JWT principal is refused in either mode")
    void nonJwtPrincipal_denied() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "someone", "n/a", List.of()));

        assertThatThrownBy(() -> aspect.enforce(joinPoint())).isInstanceOf(AccessDeniedException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static Jwt jwt(Map<String, Object> extraClaims, Instant issuedAt) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("sub", UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(3600));
        extraClaims.forEach(builder::claim);
        return builder.build();
    }

    /** A join point standing in for a controller method annotated {@code @RequiresStepUp}. */
    private static ProceedingJoinPoint joinPoint() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(ProtectedTarget.class.getMethod("protectedAction"));
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.proceed()).thenReturn("ok");
        when(pjp.getTarget()).thenReturn(new ProtectedTarget());
        return pjp;
    }

    public static class ProtectedTarget {
        @RequiresStepUp(reason = ACTION, maxAgeMinutes = 10)
        public String protectedAction() {
            return "ok";
        }
    }
}
