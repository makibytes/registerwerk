package de.makibytes.registerwerk.finality.web;

import de.makibytes.registerwerk.finality.internal.ChainEffectView;
import de.makibytes.registerwerk.finality.internal.FinalityJournalAdminService;
import de.makibytes.registerwerk.finality.web.dto.AcknowledgeRequest;
import de.makibytes.registerwerk.finality.web.dto.RetryResultResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The operator "unresolved compensation" / reorg-impact queue — every {@code chain_effect} row
 * that a reorg-driven compensation failed on or that was escalated as irreversible, and the two
 * actions an admin has over one: retry it now, or acknowledge it (the action that lifts {@code
 * FinalityGate}'s per-asset freeze — see {@code FinalityGateImpl}'s javadoc).
 *
 * <p>Reads share the same role set as {@code FinalityPolicyController} (REGISTRY_ADMIN, AUDIT,
 * COMPLIANCE_OFFICER — visibility into what is currently blocking gated operations); writes are
 * REGISTRY_ADMIN-only and step-up protected, same convention as the policy screen this sits next
 * to in the operator UI.
 */
@RestController
@RequestMapping("/api/v1/finality-journal")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER')")
public class FinalityJournalController {

    private final FinalityJournalAdminService adminService;

    public FinalityJournalController(FinalityJournalAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/unresolved")
    public ResponseEntity<List<ChainEffectView>> listUnresolved() {
        return ResponseEntity.ok(adminService.listUnresolved());
    }

    @PostMapping("/{chainEffectId}/retry")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "CHAIN_EFFECT_RETRY")
    public ResponseEntity<RetryResultResponse> retry(@PathVariable UUID chainEffectId) {
        return ResponseEntity.ok(RetryResultResponse.of(adminService.retry(chainEffectId)));
    }

    @PostMapping("/{chainEffectId}/acknowledge")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "CHAIN_EFFECT_ACKNOWLEDGE")
    public ResponseEntity<ChainEffectView> acknowledge(
            @PathVariable UUID chainEffectId, @Valid @RequestBody AcknowledgeRequest request, Authentication auth) {
        return ResponseEntity.ok(adminService.acknowledge(chainEffectId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/chains/{chainConfigId}/resolve-quarantine")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "CHAIN_QUARANTINE_RESOLVE")
    public ResponseEntity<Void> resolveQuarantine(
            @PathVariable UUID chainConfigId, @Valid @RequestBody AcknowledgeRequest request,
            Authentication auth) {
        adminService.resolveQuarantine(chainConfigId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    /** What {@link #resolveQuarantine} is about to clear on the Chaincache lifecycle inbox side,
     *  for operator visibility before doing so — see {@code ChaincacheInboxRecoveryPort}. */
    @GetMapping("/chains/{chainConfigId}/quarantined-events")
    public ResponseEntity<List<de.makibytes.registerwerk.finality.api.ChaincacheInboxRecoveryPort.QuarantinedInboxEvent>>
            quarantinedEvents(@PathVariable UUID chainConfigId) {
        return ResponseEntity.ok(adminService.listQuarantinedInboxEvents(chainConfigId));
    }
}
