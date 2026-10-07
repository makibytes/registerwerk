package de.makibytes.registerwerk.wallet.web;

import de.makibytes.registerwerk.shared.EnvelopeSecretInventory.RewrapOutcome;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.wallet.internal.KekRewrapService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Platform key-encryption-key lifecycle across every envelope-encrypted secret (wallet keys, TOTP, webhook,
 * Travel Rule peer keys, in-flight secure links): where each ciphertext stands, a re-wrap onto the active KEK
 * version, and the guarded retirement of an old version. Responses carry counts only, never secret material.
 */
@RestController
@RequestMapping("/api/v1/admin/kek")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class KekController {

    private final KekRewrapService service;

    public KekController(KekRewrapService service) {
        this.service = service;
    }

    /** Ciphertext counts per secret type and KEK version label. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeVersion", service.activeVersion().orElse(null));
        body.put("secrets", service.scan());
        return ResponseEntity.ok(body);
    }

    /** Re-wraps every secret that is not on the active KEK version. Idempotent; a failed row is counted, not fatal. */
    @PostMapping("/rewrap")
    @RequiresStepUp(requireSecondApprover = true, reason = "KEK_REWRAP")
    public ResponseEntity<Map<String, Object>> rewrap(
            @RequestAttribute(value = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        KekRewrapService.RewrapReport report = service.rewrapAll(
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
        Map<String, Object> byType = new LinkedHashMap<>();
        for (Map.Entry<String, RewrapOutcome> e : report.byType().entrySet()) {
            byType.put(e.getKey(), Map.of("rewrapped", e.getValue().rewrapped(), "failed", e.getValue().failed()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rewrapped", report.rewrapped());
        body.put("failed", report.failed());
        body.put("byType", byType);
        return ResponseEntity.ok(body);
    }

    /**
     * Retires an old KEK version. Answers 409 while any ciphertext still references it. Where the provider
     * allows it the version stops being used in-process; for cloud KMS the operator disables it there afterwards.
     */
    @PostMapping("/versions/{version}/retire")
    @RequiresStepUp(requireSecondApprover = true, reason = "KEK_VERSION_RETIRE")
    public ResponseEntity<KekRewrapService.RetireResult> retire(
            @PathVariable String version,
            @RequestAttribute(value = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        return ResponseEntity.ok(service.retireVersion(version,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId));
    }
}
