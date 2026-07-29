package de.makibytes.registerwerk.registertransfer.web;

import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequest;
import de.makibytes.registerwerk.registertransfer.internal.PortfolioMigrationService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Portfolio-migration endpoints — operator-only. Lifecycle: initiate → set destination →
 * export → record on-chain transfer → complete, with cancel available before completion.
 * The on-chain transfer itself (moving tokens to the destination wallet) is a separate,
 * standard-specific operator action using the existing token-admin tooling; this module
 * records the handover, it does not execute the transfer.
 */
@RestController
@RequestMapping("/api/v1/portfolio-migrations")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class PortfolioMigrationController {

    private final PortfolioMigrationService migrationService;

    PortfolioMigrationController(PortfolioMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping
    public ResponseEntity<PortfolioMigrationRequest> initiate(
            @RequestBody @Valid PortfolioMigrationDtos.MigrationInitiateRequest request, Authentication auth) {
        PortfolioMigrationRequest created = migrationService.initiate(
                request.holderId(), request.reason(), SecurityUtils.extractUserId(auth));
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/{migrationId}/destination")
    public PortfolioMigrationRequest setDestination(
            @PathVariable UUID migrationId,
            @RequestBody @Valid PortfolioMigrationDtos.SetDestinationRequest request, Authentication auth) {
        return migrationService.setDestination(migrationId, request.destinationRegistrarName(),
                request.destinationRegistrarIdentifier(), request.destinationWalletAddress(),
                SecurityUtils.extractUserId(auth));
    }

    @PostMapping("/{migrationId}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID migrationId, Authentication auth) {
        byte[] json = migrationService.export(migrationId, SecurityUtils.extractUserId(auth));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"portfolio-migration.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/{migrationId}/onchain-transfer")
    @RequiresStepUp(requireSecondApprover = true, reason = "PORTFOLIO_MIGRATION_ONCHAIN_TRANSFER")
    public PortfolioMigrationRequest recordOnchainTransfer(
            @PathVariable UUID migrationId,
            @RequestBody @Valid PortfolioMigrationDtos.OnchainTransferRequest request, Authentication auth) {
        return migrationService.recordOnchainTransfer(migrationId, request.txHash(), SecurityUtils.extractUserId(auth));
    }

    @PostMapping("/{migrationId}/complete")
    public PortfolioMigrationRequest complete(@PathVariable UUID migrationId, Authentication auth) {
        return migrationService.complete(migrationId, SecurityUtils.extractUserId(auth));
    }

    @PostMapping("/{migrationId}/cancel")
    public PortfolioMigrationRequest cancel(
            @PathVariable UUID migrationId,
            @RequestBody @Valid PortfolioMigrationDtos.MigrationCancelRequest request, Authentication auth) {
        return migrationService.cancel(migrationId, request.reason(), SecurityUtils.extractUserId(auth));
    }

    @GetMapping("/investors/{investorEntityId}")
    public List<PortfolioMigrationRequest> listForInvestor(@PathVariable UUID investorEntityId) {
        return migrationService.listForInvestor(investorEntityId);
    }
}
