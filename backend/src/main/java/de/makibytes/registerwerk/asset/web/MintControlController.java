package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.MintControlService;
import de.makibytes.registerwerk.asset.internal.MintControlSyncJob;
import de.makibytes.registerwerk.deployment.api.MintControlRule;
import de.makibytes.registerwerk.asset.web.dto.MintControlRuleCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.MintControlRuleResponse;
import de.makibytes.registerwerk.asset.web.dto.MintControlRuleUpdateRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing mint control rules for a specific deployment.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/mint-rules")
public class MintControlController {

    private static final Logger log = LoggerFactory.getLogger(MintControlController.class);

    private final MintControlService mintControlService;
    private final MintControlSyncJob mintControlSyncJob;

    public MintControlController(
            MintControlService mintControlService,
            MintControlSyncJob mintControlSyncJob) {
        this.mintControlService = mintControlService;
        this.mintControlSyncJob = mintControlSyncJob;
    }

    /**
     * Creates a new mint control rule for the given deployment.
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<MintControlRuleResponse> createRule(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid MintControlRuleCreateRequest request,
            Authentication auth) {
        MintControlRule rule = new MintControlRule();
        rule.setTargetAddress(request.targetAddress());
        rule.setRuleType(request.ruleType());
        rule.setMaxAmount(request.maxAmount());
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        MintControlRule created = mintControlService.createRule(assetId, depId, rule, actorId, actorRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    /**
     * Lists all active mint control rules for the given deployment.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<List<MintControlRuleResponse>> listRules(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        List<MintControlRule> rules = mintControlService.listRules(assetId, depId);
        return ResponseEntity.ok(rules.stream().map(this::toResponse).toList());
    }

    /**
     * Partially updates a mint control rule's target address, type, or max amount.
     * Null request fields are ignored (PATCH semantics).
     */
    @PatchMapping("/{ruleId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<MintControlRuleResponse> updateRule(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @PathVariable UUID ruleId,
            @RequestBody @Valid MintControlRuleUpdateRequest request,
            Authentication auth) {
        MintControlRule patch = new MintControlRule();
        patch.setTargetAddress(request.targetAddress());
        patch.setRuleType(request.ruleType());
        patch.setMaxAmount(request.maxAmount());
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        mintControlService.requireDeployment(assetId, depId);
        MintControlRule updated = mintControlService.updateRule(depId, ruleId, patch, actorId, actorRole);
        return ResponseEntity.ok(toResponse(updated));
    }

    /**
     * Deactivates (soft-deletes) a mint control rule.
     */
    @DeleteMapping("/{ruleId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<Void> deactivateRule(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @PathVariable UUID ruleId,
            Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        mintControlService.requireDeployment(assetId, depId);
        mintControlService.deactivateRule(depId, ruleId, actorId, actorRole);
        return ResponseEntity.noContent().build();
    }

    /**
     * Triggers an immediate on-chain sync of mint control rules.
     */
    @PostMapping("/sync")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> syncRules(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        mintControlService.requireDeployment(assetId, depId);
        mintControlSyncJob.syncDeployment(depId);
        return ResponseEntity.accepted().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MintControlRuleResponse toResponse(MintControlRule rule) {
        return new MintControlRuleResponse(
            rule.getId(),
            rule.getTargetAddress(),
            rule.getRuleType(),
            rule.getMaxAmount(),
            rule.getActive(),
            rule.getCreatedAt()
        );
    }
}
