package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1: a dual-control approver token is a credential for the {@code X-Dual-Control-Token} header only.
 * Presented as the caller's own Bearer it would run the request as the approver.
 */
@DisplayName("StepUpEnforcer - approver tokens are never the caller's own proof (C1)")
class StepUpEnforcerApproverTokenTest {

    private final RegisterwerkAuthProperties auth = new RegisterwerkAuthProperties();
    private final StepUpEntraProperties entra = new StepUpEntraProperties();
    private StepUpEnforcer enforcer;

    @BeforeEach
    void setUp() {
        entra.setAuthContextId("c1");
        enforcer = new StepUpEnforcer(new StepUpPolicy(auth, entra));
    }

    private static Jwt.Builder base() {
        return Jwt.withTokenValue("t").header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("acr", "stepup");
    }

    private static Jwt approverToken() {
        return base().claim("stepup_scope", "FORCE_BURN_EWG26").claim("stepup_target", "digest")
                .claim("jti", "j-1").claim("use", "dual_control")
                .audience(List.of("registerwerk-dual-control")).build();
    }

    @Test
    @DisplayName("local mode: an approver token is refused as the caller's step-up proof")
    void localModeRefusesApproverToken() {
        auth.setEntraEnabled(false);
        assertThatThrownBy(() -> enforcer.enforce(approverToken(), "FORCE_BURN_EWG26", 10))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("approver");
    }

    @Test
    @DisplayName("a legacy approver token (scope claim, no marker yet) is refused too")
    void scopeClaimAloneMarksAnApproverToken() {
        auth.setEntraEnabled(false);
        Jwt legacy = base().claim("stepup_scope", "FORCE_BURN_EWG26").claim("jti", "j-2").build();
        assertThatThrownBy(() -> enforcer.enforce(legacy, "FORCE_BURN_EWG26", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Entra mode: an approver token is refused outright, not answered with a claims challenge")
    void entraModeRefusesApproverToken() {
        auth.setEntraEnabled(true);
        Jwt withAcrs = base().claim("stepup_scope", "FORCE_BURN_EWG26").claim("use", "dual_control")
                .claim("acrs", List.of("c1")).claim("auth_time", Instant.now().getEpochSecond()).build();
        assertThatThrownBy(() -> enforcer.enforce(withAcrs, "FORCE_BURN_EWG26", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an ordinary step-up token (no scope, no marker) is still the caller's proof")
    void ordinaryStepUpTokenStillWorks() {
        auth.setEntraEnabled(false);
        assertThatCode(() -> enforcer.enforce(base().claim("roles", List.of("REGISTRY_ADMIN")).build(),
                "FORCE_BURN_EWG26", 10)).doesNotThrowAnyException();
    }
}
