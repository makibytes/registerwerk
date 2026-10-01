package de.makibytes.registerwerk.stepup.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.stepup.internal.StepUpTokenIssuer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator reset of a user's TOTP enrolment (lost or replaced device), K3 6-09. Step-up plus a second
 * approver; clears the secret, ends the user's live sessions and audits {@code TOTP_RESET} with both
 * identities. The user enrols again at next login (password re-entry required).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class TotpResetController {

    private final StepUpTokenIssuer issuer;

    TotpResetController(StepUpTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping("/{id}/totp-reset")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "TOTP_RESET")
    public ResponseEntity<Void> reset(
            @PathVariable UUID id,
            Authentication auth,
            @RequestAttribute(value = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        issuer.reset(id, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
        return ResponseEntity.noContent().build();
    }
}
