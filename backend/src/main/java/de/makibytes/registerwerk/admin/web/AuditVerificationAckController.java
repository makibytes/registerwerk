package de.makibytes.registerwerk.admin.web;

import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Dual-control acknowledgement of a broken audit-chain verification verdict (7B-04). Lives in the admin module
 * (which already depends on step-up) so the audit module stays free of a step-up dependency. The verdict, and
 * the alert built on it, stays "broken" until a later valid run exists AND this acknowledgement is recorded.
 */
@RestController
@RequestMapping("/api/v1/audit/verification")
public class AuditVerificationAckController {

    private final AuditApi auditApi;

    public AuditVerificationAckController(AuditApi auditApi) {
        this.auditApi = auditApi;
    }

    @PostMapping("/{id}/ack")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "AUDIT_CHAIN_VERIFICATION_ACK")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID id, @RequestParam(required = false) String note,
                                            Authentication auth) {
        auditApi.acknowledgeChainVerification(id, SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), note);
        return ResponseEntity.noContent().build();
    }
}
