package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the dual-control (4-eyes) second-approver checks, in particular the DB-backed
 * liveness re-check added alongside the JWT claim checks: a step-up token minted while an
 * admin was still enabled must NOT be honoured once that admin has since been disabled or
 * demoted, even though the JWT itself is still within its 10-minute validity window.
 */
@ExtendWith(MockitoExtension.class)
class StepUpTokenValidatorTest {

    @Mock private JwtDecoder jwtDecoder;
    @Mock private AppUserRepository appUserRepository;

    private StepUpTokenValidator validator;

    private final UUID initiatorId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        validator = new StepUpTokenValidator(jwtDecoder, appUserRepository);
    }

    private Jwt approverJwt() {
        return approverJwt("FORCE_BURN");
    }

    private Jwt approverJwt(String scope) {
        var builder = Jwt.withTokenValue("raw-token")
                .header("alg", "HS256")
                .subject(approverId.toString())
                .claim("roles", List.of("REGISTRY_ADMIN"))
                .claim("acr", "stepup")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600));
        if (scope != null) {
            builder.claim("stepup_scope", scope);
        }
        return builder.build();
    }

    private AppUser enabledAdmin() {
        AppUser user = new AppUser();
        user.setId(approverId);
        user.setEnabled(true);
        user.setRoles(Set.of(AppUserRole.REGISTRY_ADMIN));
        return user;
    }

    @Test
    @DisplayName("accepts a currently-enabled REGISTRY_ADMIN approver")
    void accepts_currentlyEligibleApprover() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt());
        when(appUserRepository.findById(approverId)).thenReturn(Optional.of(enabledAdmin()));

        assertThatCode(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects an approver who has since been disabled — JWT claim alone is not trusted")
    void rejects_approverDisabledSinceTokenMint() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt());
        AppUser disabled = enabledAdmin();
        disabled.setEnabled(false);
        when(appUserRepository.findById(approverId)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("no longer an enabled REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("rejects an approver who has since been demoted from REGISTRY_ADMIN")
    void rejects_approverDemotedSinceTokenMint() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt());
        AppUser demoted = enabledAdmin();
        demoted.setRoles(Set.of(AppUserRole.COMPLIANCE_OFFICER));
        when(appUserRepository.findById(approverId)).thenReturn(Optional.of(demoted));

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("no longer an enabled REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("rejects an approver who has since been deleted entirely")
    void rejects_approverDeletedSinceTokenMint() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt());
        when(appUserRepository.findById(approverId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("no longer an enabled REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("self-approval is rejected before any DB lookup")
    void rejects_selfApproval() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt());

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", approverId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("different user");
    }

    // ── Scope binding (finding #10, Phase 8) ───────────────────────────────────

    @Test
    @DisplayName("rejects an approver token scoped to a different action than the one being approved")
    void rejects_scopeMismatch() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt("WALLET_DELETE"));

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not scoped to this action");
    }

    @Test
    @DisplayName("rejects an approver token with no stepup_scope claim at all")
    void rejects_missingScope() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt(null));

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not scoped to this action");
    }

    @Test
    @DisplayName("a scope-mismatched token is rejected before the DB re-check runs (cheap check first)")
    void scopeMismatch_rejectedBeforeDbLookup() {
        when(jwtDecoder.decode("raw-token")).thenReturn(approverJwt("WALLET_DELETE"));

        assertThatThrownBy(() -> validator.validateDualControlToken("raw-token", initiatorId.toString(), "FORCE_BURN"))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verifyNoInteractions(appUserRepository);
    }
}
