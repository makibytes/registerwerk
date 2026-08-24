package de.makibytes.registerwerk.admin.web;

import java.util.UUID;

import de.makibytes.registerwerk.admin.internal.EntraSupportService;
import de.makibytes.registerwerk.admin.web.dto.EntraMethodsResponse;
import de.makibytes.registerwerk.admin.web.dto.EntraResetOutcomeResponse;
import de.makibytes.registerwerk.admin.web.dto.TemporaryAccessPassRequest;
import de.makibytes.registerwerk.admin.web.dto.TemporaryAccessPassResponse;
import de.makibytes.registerwerk.entra.api.EntraAuthMethodType;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-factor support actions an operator performs on a customer's behalf — overwhelmingly the
 * lost-phone case.
 *
 * <p>Lives in {@code admin} rather than {@code entra} because these endpoints carry
 * {@code @RequiresStepUp}, and {@code entra} must not depend on {@code stepup}. Kept under
 * {@code /api/v1/admin/**} so Kong's {@code ip-restriction} plugin still applies (the operator
 * portal bypasses Kong, so this is defence in depth rather than the only control).
 *
 * <p>Step-up on every mutation; 4-eyes on the two that can end with someone else signing in as
 * the customer — a full method reset, and issuing a Temporary Access Pass. Deleting a single
 * stale factor and revoking sessions cost availability, not confidentiality, so they take
 * step-up alone.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/entra")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class OperatorUserEntraController {

    private final EntraSupportService supportService;

    OperatorUserEntraController(EntraSupportService supportService) {
        this.supportService = supportService;
    }

    @GetMapping("/methods")
    public ResponseEntity<EntraMethodsResponse> methods(@PathVariable UUID userId) {
        return ResponseEntity.ok(supportService.listMethods(userId));
    }

    @DeleteMapping("/methods/{type}/{methodId}")
    @RequiresStepUp(reason = "ENTRA_AUTH_METHOD_DELETE")
    public ResponseEntity<Void> deleteMethod(
            Authentication authentication,
            @PathVariable UUID userId,
            @PathVariable EntraAuthMethodType type,
            @PathVariable String methodId) {
        supportService.deleteMethod(authentication, userId, type, methodId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes every deletable method, forcing re-registration at next sign-in. Graph has no
     * single "reset MFA" call, so this is the documented substitute.
     */
    @PostMapping("/methods/reset")
    @RequiresStepUp(requireSecondApprover = true, reason = "ENTRA_MFA_RESET")
    public ResponseEntity<EntraResetOutcomeResponse> resetMethods(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return ResponseEntity.ok(supportService.resetAllMethods(authentication, userId, approverId));
    }

    /**
     * Invalidates existing refresh tokens and browser sessions. Must be called explicitly —
     * neither a password reset nor deleting authentication methods does this.
     */
    @PostMapping("/revoke-sessions")
    @RequiresStepUp(reason = "ENTRA_REVOKE_SIGNIN_SESSIONS")
    public ResponseEntity<Void> revokeSessions(
            Authentication authentication,
            @PathVariable UUID userId) {
        supportService.revokeSessions(authentication, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Issues a Temporary Access Pass so the customer can sign in once and register a new method.
     *
     * <p>Requires 4-eyes: the pass fully authenticates as the target user, which makes it an
     * account-takeover primitive in the same class as a wallet key export.
     *
     * <p>The response carries the only copy of the pass — Graph will not return it again and
     * Registerwerk does not store it — so it is marked {@code no-store} and must be delivered
     * out-of-band immediately.
     */
    @PostMapping("/temporary-access-pass")
    @RequiresStepUp(requireSecondApprover = true, reason = "ENTRA_TEMPORARY_ACCESS_PASS")
    public ResponseEntity<TemporaryAccessPassResponse> issueTemporaryAccessPass(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody TemporaryAccessPassRequest request,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        TemporaryAccessPassResponse response =
                supportService.issueTemporaryAccessPass(authentication, userId, request, approverId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }
}
