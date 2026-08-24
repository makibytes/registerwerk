package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Validates the second-approver token for the 4-eyes (Vieraugenprinzip) check.
 * The token must:
 * - Be a valid JWT (decoded by the same JwtDecoder as the primary token)
 * - Carry acr=stepup and be fresh (≤10 min)
 * - Carry role REGISTRY_ADMIN or COMPLIANCE_OFFICER
 * - Have a different sub than the primary caller (cannot self-approve)
 * - Belong to a user who is CURRENTLY (per the DB, not just the JWT claim) enabled and
 *   still holds one of those roles — a step-up token minted shortly before that user was
 *   disabled would otherwise still pass purely on JWT claims for its full 10-minute window.
 *
 * <p>Approver eligibility deliberately includes {@code COMPLIANCE_OFFICER}, not just
 * {@code REGISTRY_ADMIN}: every {@code @RequiresStepUp(requireSecondApprover=true)} endpoint's
 * class/method-level {@code @PreAuthorize} restricts the initiator to {@code REGISTRY_ADMIN}
 * (initiation is unchanged by this class), so REGISTRY_ADMIN-only approval meant an admin could
 * both make and check a dual-control action — two different admins, but never a genuine
 * segregation of duties between the maker and an independent compliance function (BaFin MaRisk
 * AT 4.3.1 Funktionstrennung calls for exactly that). A COMPLIANCE_OFFICER can now be the
 * checker; they still cannot self-approve their own actions since COMPLIANCE_OFFICER cannot
 * initiate these endpoints in the first place.
 */
@Component
class StepUpTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(StepUpTokenValidator.class);

    private static final List<AppUserRole> ELIGIBLE_APPROVER_ROLES =
            List.of(AppUserRole.REGISTRY_ADMIN, AppUserRole.COMPLIANCE_OFFICER);

    private final JwtDecoder jwtDecoder;
    private final AppUserRepository appUserRepository;

    /**
     * @param jwtDecoder deliberately the HS256 decoder <em>by qualifier</em>, not the primary
     *        one. Dual-control tokens are always minted locally by {@link StepUpTokenIssuer},
     *        so they must always be verified locally — with the primary decoder they became
     *        unverifiable the moment an OIDC issuer was configured, silently disabling every
     *        4-eyes endpoint in Entra mode.
     */
    StepUpTokenValidator(
            @Qualifier("localHs256JwtDecoder") JwtDecoder jwtDecoder,
            AppUserRepository appUserRepository) {
        this.jwtDecoder = jwtDecoder;
        this.appUserRepository = appUserRepository;
    }

    /**
     * @return the validated approver's user id — previously this was validate-only (void), so
     *         every controller wanting to persist "who approved this" had to independently
     *         re-decode the same dual-control JWT itself. Most never did (e.g. {@code
     *         HolderBlockController.lift} left the field {@code null} with a comment claiming
     *         "extracted... by the aspect", which the aspect never actually did); the one place
     *         that did ({@code AssetTokenAdminGrantController}) duplicated the decode. Returning
     *         it here lets {@code StepUpEnforcementAspect} expose it once, centrally.
     */
    UUID validateDualControlToken(String rawToken, String primarySub, String action) {
        Jwt approverJwt;
        try {
            approverJwt = jwtDecoder.decode(rawToken);
        } catch (JwtException e) {
            throw new AccessDeniedException("Invalid dual-control token: " + e.getMessage());
        }

        // Must be different person
        if (primarySub.equals(approverJwt.getSubject())) {
            log.warn("Dual-control self-approval attempt: sub={} action={}", primarySub, action);
            throw new AccessDeniedException("Dual control: approver must be a different user from the initiator.");
        }

        // Must be REGISTRY_ADMIN or COMPLIANCE_OFFICER (JWT claim — cheap first check before
        // the DB round-trip)
        List<String> roles = approverJwt.getClaimAsStringList("roles");
        boolean hasEligibleRoleClaim = roles != null && ELIGIBLE_APPROVER_ROLES.stream()
                .map(Enum::name)
                .anyMatch(roles::contains);
        if (!hasEligibleRoleClaim) {
            throw new AccessDeniedException(
                    "Dual-control approver must have REGISTRY_ADMIN or COMPLIANCE_OFFICER role.");
        }

        // Must be fresh step-up token
        String acr = approverJwt.getClaimAsString("acr");
        if (!"stepup".equals(acr)) {
            throw new AccessDeniedException("Dual-control approver token must have acr=stepup.");
        }
        Instant iat = approverJwt.getIssuedAt();
        if (iat == null || iat.isBefore(Instant.now().minusSeconds(600))) {
            throw new AccessDeniedException("Dual-control approver step-up token expired (max 10 min).");
        }

        // Must be scoped to this exact action — otherwise one dual-
        // control approval, once minted, would be a generic bearer credential valid for ANY
        // requireSecondApprover action within its 10-minute window, not just the one the
        // approver actually reviewed and approved.
        String scope = approverJwt.getClaimAsString("stepup_scope");
        if (scope == null || !scope.equals(action)) {
            log.warn("Dual-control approver token scope mismatch: sub={} tokenScope={} requiredAction={}",
                    approverJwt.getSubject(), scope, action);
            throw new AccessDeniedException(
                    "Dual-control approver token is not scoped to this action. Mint a fresh step-up token with "
                    + "action='" + action + "'.");
        }

        // Re-check current DB state — the JWT claim reflects role/enabled status only as of
        // token mint time, not now.
        requireCurrentlyEligibleApprover(approverJwt.getSubject(), action);

        log.info("Dual-control approved: initiator={} approver={} action={}", primarySub, approverJwt.getSubject(), action);
        return UUID.fromString(approverJwt.getSubject());
    }

    private void requireCurrentlyEligibleApprover(String approverSub, String action) {
        UUID approverId;
        try {
            approverId = UUID.fromString(approverSub);
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Dual-control approver token has an invalid subject.");
        }
        AppUser approver = appUserRepository.findById(approverId).orElse(null);
        boolean hasEligibleRole = approver != null
                && ELIGIBLE_APPROVER_ROLES.stream().anyMatch(approver::hasRole);
        if (approver == null || !approver.isEnabled() || !hasEligibleRole) {
            log.warn("Dual-control approver no longer eligible: approverId={} action={} " +
                            "(found={}, enabled={}, hasEligibleRole={})",
                    approverId, action, approver != null,
                    approver != null && approver.isEnabled(),
                    hasEligibleRole);
            throw new AccessDeniedException(
                    "Dual-control approver is no longer an enabled REGISTRY_ADMIN or COMPLIANCE_OFFICER.");
        }
    }
}
