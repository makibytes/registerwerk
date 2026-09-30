package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.blockchain.api.RequestEvidence;
import de.makibytes.registerwerk.blockchain.internal.OutboxRecoveryService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operator view and actions for stuck durable-outbox payloads (P4B-4, parked T4-03 interim).
 * Cancel and re-price replace the payload at the same nonce (cancel = 0-value self-send, the
 * operation will not execute) and are the only way a regulatory operation is ever replaced: each
 * needs a fresh step-up token and a second approver, whose id is stored on the audit entry.
 * The stuck-queue UI is a separate frontend task; the response is the stable contract for it.
 */
@RestController
@RequestMapping("/api/v1/admin/chains/{chainConfigId}/outbox")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
@Validated
public class OutboxAdminController {

    private final OutboxRecoveryService recovery;

    public OutboxAdminController(OutboxRecoveryService recovery) {
        this.recovery = recovery;
    }

    /** PREPARED payloads older than the stuck threshold, and BROADCAST payloads still unmined. */
    @GetMapping("/stuck")
    public ResponseEntity<List<OutboxRecoveryService.OutboxEntry>> stuck(@PathVariable UUID chainConfigId) {
        return ResponseEntity.ok(recovery.listStuck(chainConfigId));
    }

    @PostMapping("/{id}/cancel")
    @RequiresStepUp(requireSecondApprover = true, reason = "EVM_OUTBOX_CANCEL")
    public ResponseEntity<OutboxRecoveryService.OutboxEntry> cancel(@PathVariable UUID chainConfigId,
            @PathVariable UUID id, @RequestBody @Valid OutboxActionRequest request, Authentication auth) {
        return ResponseEntity.ok(recovery.cancel(chainConfigId, id, actor(auth), request.reason().trim()));
    }

    @PostMapping("/{id}/reprice")
    @RequiresStepUp(requireSecondApprover = true, reason = "EVM_OUTBOX_REPRICE")
    public ResponseEntity<OutboxRecoveryService.OutboxEntry> reprice(@PathVariable UUID chainConfigId,
            @PathVariable UUID id, @RequestBody @Valid OutboxActionRequest request, Authentication auth) {
        return ResponseEntity.ok(recovery.reprice(chainConfigId, id, actor(auth), request.reason().trim()));
    }

    private static OutboxRecoveryService.Actor actor(Authentication auth) {
        String role = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        return new OutboxRecoveryService.Actor(SecurityUtils.extractUserId(auth), auth.getName(), role,
                RequestEvidence.approverId(), RequestEvidence.requestId());
    }

    /** The operator's reason is mandatory and lands in the audit entry and on the replacement. */
    public record OutboxActionRequest(@NotBlank @Size(min = 10, max = 500) String reason) {}
}
