package de.makibytes.registerwerk.blockchain.web;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialBalanceReconciliationService;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialBalanceReconciliationService.ReconciliationReport;
import de.makibytes.registerwerk.shared.SecurityUtils;

/**
 * Operator/auditor endpoint comparing each holder's decrypted on-chain confidential balance
 * against the register's own plaintext §16 balance — see
 * {@link ConfidentialBalanceReconciliationService}'s class-level note. {@code AUDIT} is included
 * alongside {@code REGISTRY_ADMIN} deliberately: an independent auditor being able to decrypt and
 * verify every holder's balance (not just the operator) is one of the two "who must be able to
 * decrypt everything" roles this whole confidential-token feature exists to serve.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/confidential-reconciliation")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
public class ConfidentialReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialReconciliationController.class);

    private final ConfidentialBalanceReconciliationService reconciliationService;

    public ConfidentialReconciliationController(ConfidentialBalanceReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public ResponseEntity<ReconciliationReport> reconcile(@PathVariable UUID assetId, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");
        log.info("Confidential balance reconciliation requested for asset={} by actor={}", assetId, actorId);
        return ResponseEntity.ok(reconciliationService.reconcile(assetId, actorId, actorRole));
    }
}
