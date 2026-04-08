package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.blockchain.MintControlService;
import de.makibytes.registerwerk.application.blockchain.MintControlSyncJob;
import de.makibytes.registerwerk.domain.asset.MintControlRule;
import de.makibytes.registerwerk.web.dto.MintControlRuleCreateRequest;
import de.makibytes.registerwerk.web.dto.MintControlRuleResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    public ResponseEntity<MintControlRuleResponse> createRule(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid MintControlRuleCreateRequest request) {
        MintControlRule rule = new MintControlRule();
        rule.setTargetAddress(request.targetAddress());
        rule.setRuleType(request.ruleType());
        rule.setMaxAmount(request.maxAmount());
        MintControlRule created = mintControlService.createRule(depId, rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    /**
     * Lists all active mint control rules for the given deployment.
     */
    @GetMapping
    public ResponseEntity<List<MintControlRuleResponse>> listRules(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        List<MintControlRule> rules = mintControlService.listRules(depId);
        return ResponseEntity.ok(rules.stream().map(this::toResponse).toList());
    }

    /**
     * Partially updates a mint control rule.
     * TODO: Add full update logic.
     */
    @PatchMapping("/{ruleId}")
    public ResponseEntity<MintControlRuleResponse> updateRule(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @PathVariable UUID ruleId,
            @RequestBody MintControlRuleCreateRequest request) {
        // TODO: implement update via a MintControlRuleRepository.findById + patch
        log.warn("[STUB] updateRule called for ruleId={}", ruleId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Deactivates (soft-deletes) a mint control rule.
     */
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deactivateRule(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @PathVariable UUID ruleId) {
        mintControlService.deactivateRule(ruleId);
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
        mintControlSyncJob.syncFromChain();
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
