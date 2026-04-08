package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.blockchain.TokenAdminService;
import de.makibytes.registerwerk.web.dto.admin.ForceBurnRequest;
import de.makibytes.registerwerk.web.dto.admin.ForcedTransferRequest;
import de.makibytes.registerwerk.web.dto.admin.FreezeRequest;
import de.makibytes.registerwerk.web.dto.admin.SetSupplyCapRequest;
import de.makibytes.registerwerk.web.dto.admin.UnfreezeRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Registry-operator administrative controls for ERC-20, ERC-721, and ERC-1155 token contracts.
 *
 * <p>Base path: {@code /api/v1/assets/{assetId}/deployments/{depId}/admin}
 *
 * <p>All endpoints require the {@code REGISTRY_ADMIN} role and are not accessible to
 * issuers or investors. Every action is written to the immutable audit log.
 *
 * <p>Regulatory powers implemented (eWpG, MiCAR, AWG, GwG):
 * <pre>
 *   POST .../admin/pause              — suspend all transfers       (MiCAR Art. 36, 84; eWpG §24)
 *   POST .../admin/unpause            — resume transfers
 *   POST .../admin/freeze             — freeze specific address      (AWG §17, GwG §40)
 *   POST .../admin/unfreeze           — lift address freeze
 *   POST .../admin/forced-transfer    — BaFin/court-ordered transfer (eWpG §24 Berichtigung)
 *   POST .../admin/force-burn         — compulsory cancellation      (eWpG §26 Einziehung)
 *   POST .../admin/set-supply-cap     — regulatory issuance ceiling  (MiCAR Art. 46)
 * </pre>
 *
 * <p><strong>Note on ERC-3643:</strong> These endpoints do NOT apply to ERC-3643 tokens —
 * those use the T-REX agent model via {@code /api/v1/assets/{assetId}/erc3643/...}.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/admin")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class TokenAdminController {

    private static final Logger log = LoggerFactory.getLogger(TokenAdminController.class);

    private final TokenAdminService adminService;

    public TokenAdminController(TokenAdminService adminService) {
        this.adminService = adminService;
    }

    // ── Pause / Unpause ───────────────────────────────────────────────────────

    /**
     * Suspends all transfers on the token contract.
     *
     * <p>Use when BaFin issues an emergency suspension order (MiCAR Art. 84) or when
     * a systemic risk requires immediate action (eWpG §24).
     */
    @PostMapping("/pause")
    public ResponseEntity<Void> pause(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            Authentication auth) {
        log.info("ADMIN pause deployment={} by actor={}", depId, actorName(auth));
        adminService.pause(depId);
        return ResponseEntity.accepted().build();
    }

    /**
     * Resumes token transfers after a pause.
     */
    @PostMapping("/unpause")
    public ResponseEntity<Void> unpause(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            Authentication auth) {
        log.info("ADMIN unpause deployment={} by actor={}", depId, actorName(auth));
        adminService.unpause(depId);
        return ResponseEntity.accepted().build();
    }

    // ── Address freeze ────────────────────────────────────────────────────────

    /**
     * Freezes a specific wallet address — the address can no longer send or receive tokens.
     *
     * <p>Legal basis: AWG §17 (sanctions list); GwG §40 (AML freeze); MiCAR Art. 36.
     * The {@code reason} field is written on-chain; {@code legalBasis} is stored in the audit log.
     */
    @PostMapping("/freeze")
    public ResponseEntity<Void> freeze(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid FreezeRequest request,
            Authentication auth) {
        log.info("ADMIN freeze address={} on deployment={} by actor={} reason={}",
                request.address(), depId, actorName(auth), request.reason());
        adminService.freezeAddress(depId, request.address(), request.reason(),
                request.legalBasis() != null ? request.legalBasis() : "");
        return ResponseEntity.accepted().build();
    }

    /**
     * Lifts a previous freeze on a wallet address.
     */
    @PostMapping("/unfreeze")
    public ResponseEntity<Void> unfreeze(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid UnfreezeRequest request,
            Authentication auth) {
        log.info("ADMIN unfreeze address={} on deployment={} by actor={}",
                request.address(), depId, actorName(auth));
        adminService.unfreezeAddress(depId, request.address());
        return ResponseEntity.accepted().build();
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    /**
     * Performs a regulatory forced transfer, bypassing whitelist, freeze, and pause.
     *
     * <p>Legal basis: eWpG §24 Berichtigung — used to execute BaFin or court orders
     * that require the registry to correct the on-chain ownership record.
     *
     * <p>{@code legalBasis} is mandatory and is written both to the on-chain event and
     * the audit log.
     */
    @PostMapping("/forced-transfer")
    public ResponseEntity<Void> forcedTransfer(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferRequest request,
            Authentication auth) {
        log.info("ADMIN forcedTransfer from={} to={} value={} on deployment={} by actor={} legalBasis={}",
                request.from(), request.to(), request.value(), depId, actorName(auth), request.legalBasis());
        adminService.forcedTransfer(depId, request.from(), request.to(), request.value(), request.legalBasis());
        return ResponseEntity.accepted().build();
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    /**
     * Burns tokens from an address, bypassing all compliance controls (Einziehung).
     *
     * <p>Legal basis: eWpG §26 Einziehung — BaFin compulsory cancellation of electronic
     * securities. Used for confiscation orders, court-ordered seizure, or AML enforcement.
     *
     * <p>{@code legalBasis} is mandatory and is written both to the on-chain event and the audit log.
     */
    @PostMapping("/force-burn")
    public ResponseEntity<Void> forceBurn(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid ForceBurnRequest request,
            Authentication auth) {
        log.info("ADMIN forceBurn from={} value={} on deployment={} by actor={} legalBasis={}",
                request.from(), request.value(), depId, actorName(auth), request.legalBasis());
        adminService.forceBurn(depId, request.from(), request.value(), request.legalBasis());
        return ResponseEntity.accepted().build();
    }

    // ── Supply cap — MiCAR Art. 46 ────────────────────────────────────────────

    /**
     * Updates the maximum total supply of tokens.
     *
     * <p>Legal basis: MiCAR Art. 46 — competent authority may impose a ceiling on the
     * total amount of crypto-assets in circulation. Pass {@code newCap = 0} to remove any cap.
     */
    @PostMapping("/set-supply-cap")
    public ResponseEntity<Void> setSupplyCap(
            @PathVariable UUID assetId,
            @PathVariable UUID depId,
            @RequestBody @Valid SetSupplyCapRequest request,
            Authentication auth) {
        log.info("ADMIN setSupplyCap={} on deployment={} by actor={}",
                request.newCap(), depId, actorName(auth));
        adminService.setSupplyCap(depId, request.newCap());
        return ResponseEntity.accepted().build();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String actorName(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }
}
