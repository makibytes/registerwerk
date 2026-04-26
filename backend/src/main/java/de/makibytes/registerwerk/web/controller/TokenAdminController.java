package de.makibytes.registerwerk.web.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.makibytes.registerwerk.application.blockchain.TokenAdminService;
import de.makibytes.registerwerk.web.dto.admin.ForceBurnRequest;
import de.makibytes.registerwerk.web.dto.admin.ForceBurnSingleRequest;
import de.makibytes.registerwerk.web.dto.admin.ForcedApproveRequest;
import de.makibytes.registerwerk.web.dto.admin.ForcedTransferRequest;
import de.makibytes.registerwerk.web.dto.admin.ForcedTransferSingleRequest;
import de.makibytes.registerwerk.web.dto.admin.FreezeRequest;
import de.makibytes.registerwerk.web.dto.admin.SetSupplyCapRequest;
import de.makibytes.registerwerk.web.dto.admin.UnfreezeRequest;
import de.makibytes.registerwerk.web.dto.admin.UnwhitelistRequest;
import de.makibytes.registerwerk.web.dto.blockchain.TxSubmissionResponse;
import jakarta.validation.Valid;

/**
 * Registry-operator administrative controls for ERC-20, ERC-721, and ERC-1155 token contracts.
 *
 * <p>Base path: {@code /api/v1/assets/{assetId}/deployments/{depId}/admin}
 *
 * <p>All endpoints require {@code REGISTRY_ADMIN}. Every submission returns a {@link TxSubmissionResponse}
 * with a {@code txId} that can be polled at {@code GET /api/v1/transactions/{txId}}.
 *
 * <pre>
 *   POST .../pause                    — suspend all transfers
 *   POST .../unpause                  — resume transfers
 *   POST .../freeze                   — freeze specific address
 *   POST .../unfreeze                 — lift address freeze
 *   POST .../whitelist                — add address to on-chain whitelist
 *   POST .../unwhitelist              — remove address from on-chain whitelist
 *   POST .../forced-transfer          — BaFin/court-ordered transfer
 *   POST .../forced-transfer-single   — ERC-1155: forced transfer of specific token id
 *   POST .../forced-approve           — BaFin/court-ordered approval override
 *   POST .../force-burn               — compulsory cancellation
 *   POST .../force-burn-single        — ERC-1155: forced burn of specific token id
 *   POST .../set-supply-cap           — regulatory issuance ceiling
 * </pre>
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

    @PostMapping("/pause")
    public ResponseEntity<TxSubmissionResponse> pause(
            @PathVariable UUID assetId, @PathVariable UUID depId, Authentication auth) {
        log.info("ADMIN pause deployment={} by actor={}", depId, actorName(auth));
        return accepted(adminService.pause(depId));
    }

    @PostMapping("/unpause")
    public ResponseEntity<TxSubmissionResponse> unpause(
            @PathVariable UUID assetId, @PathVariable UUID depId, Authentication auth) {
        log.info("ADMIN unpause deployment={} by actor={}", depId, actorName(auth));
        return accepted(adminService.unpause(depId));
    }

    @PostMapping("/freeze")
    public ResponseEntity<TxSubmissionResponse> freeze(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid FreezeRequest request, Authentication auth) {
        log.info("ADMIN freeze address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.freezeAddress(depId, request.address(), request.reason(),
                request.legalBasis() != null ? request.legalBasis() : ""));
    }

    @PostMapping("/unfreeze")
    public ResponseEntity<TxSubmissionResponse> unfreeze(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid UnfreezeRequest request, Authentication auth) {
        log.info("ADMIN unfreeze address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.unfreezeAddress(depId, request.address()));
    }

    @PostMapping("/whitelist")
    public ResponseEntity<TxSubmissionResponse> whitelist(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid UnwhitelistRequest request, Authentication auth) {
        log.info("ADMIN whitelist address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.whitelist(depId, request.address()));
    }

    @PostMapping("/unwhitelist")
    public ResponseEntity<TxSubmissionResponse> unwhitelist(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid UnwhitelistRequest request, Authentication auth) {
        log.info("ADMIN unwhitelist address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.unwhitelist(depId, request.address()));
    }

    @PostMapping("/forced-transfer")
    public ResponseEntity<TxSubmissionResponse> forcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferRequest request, Authentication auth) {
        log.info("ADMIN forcedTransfer from={} to={} on deployment={} by actor={}", request.from(), request.to(), depId, actorName(auth));
        return accepted(adminService.forcedTransfer(depId, request.from(), request.to(), request.value(), request.legalBasis()));
    }

    @PostMapping("/forced-transfer-single")
    public ResponseEntity<TxSubmissionResponse> forcedTransferSingle(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferSingleRequest request, Authentication auth) {
        log.info("ADMIN forcedTransferSingle id={} amount={} on deployment={} by actor={}", request.id(), request.amount(), depId, actorName(auth));
        return accepted(adminService.forcedTransferSingle(depId, request.from(), request.to(), request.id(), request.amount(), request.legalBasis()));
    }

    @PostMapping("/forced-approve")
    public ResponseEntity<TxSubmissionResponse> forcedApprove(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedApproveRequest request, Authentication auth) {
        log.info("ADMIN forcedApprove owner={} spender={} on deployment={} by actor={}", request.owner(), request.spender(), depId, actorName(auth));
        return accepted(adminService.forcedApprove(depId, request.owner(), request.spender(), request.value(), request.legalBasis()));
    }

    @PostMapping("/force-burn")
    public ResponseEntity<TxSubmissionResponse> forceBurn(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForceBurnRequest request, Authentication auth) {
        log.info("ADMIN forceBurn from={} value={} on deployment={} by actor={}", request.from(), request.value(), depId, actorName(auth));
        return accepted(adminService.forceBurn(depId, request.from(), request.value(), request.legalBasis()));
    }

    @PostMapping("/force-burn-single")
    public ResponseEntity<TxSubmissionResponse> forceBurnSingle(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForceBurnSingleRequest request, Authentication auth) {
        log.info("ADMIN forceBurnSingle id={} amount={} on deployment={} by actor={}", request.id(), request.amount(), depId, actorName(auth));
        return accepted(adminService.forceBurnSingle(depId, request.from(), request.id(), request.amount(), request.legalBasis()));
    }

    @PostMapping("/set-supply-cap")
    public ResponseEntity<TxSubmissionResponse> setSupplyCap(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid SetSupplyCapRequest request, Authentication auth) {
        log.info("ADMIN setSupplyCap={} on deployment={} by actor={}", request.newCap(), depId, actorName(auth));
        return accepted(adminService.setSupplyCap(depId, request.newCap()));
    }

    private static ResponseEntity<TxSubmissionResponse> accepted(UUID txId) {
        return ResponseEntity.accepted().body(new TxSubmissionResponse(txId));
    }

    private static String actorName(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }
}
