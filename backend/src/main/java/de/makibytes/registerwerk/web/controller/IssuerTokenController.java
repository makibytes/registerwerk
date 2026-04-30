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
import de.makibytes.registerwerk.web.dto.admin.BurnRequest;
import de.makibytes.registerwerk.web.dto.admin.ForcedApproveRequest;
import de.makibytes.registerwerk.web.dto.admin.ForcedTransferRequest;
import de.makibytes.registerwerk.web.dto.admin.MintRequest;
import de.makibytes.registerwerk.web.dto.blockchain.TxSubmissionResponse;
import jakarta.validation.Valid;

/**
 * Issuer token controls for deployed assets.
 * Base path: {@code /api/v1/assets/{assetId}/deployments/{depId}/issuer}
 *
 * <p>All write operations return a {@link TxSubmissionResponse} with a {@code txId}
 * that can be polled at {@code GET /api/v1/transactions/{txId}}.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/issuer")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
public class IssuerTokenController {

    private static final Logger log = LoggerFactory.getLogger(IssuerTokenController.class);

    private final TokenAdminService adminService;

    public IssuerTokenController(TokenAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/mint")
    public ResponseEntity<TxSubmissionResponse> mint(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid MintRequest request, Authentication auth) {
        log.info("ISSUER mint to={} amount={} on deployment={} by actor={}",
                request.toAddress(), request.amount(), depId, actorName(auth));
        return accepted(adminService.mint(depId, request.toAddress(), request.amount()));
    }

    @PostMapping("/burn")
    public ResponseEntity<TxSubmissionResponse> burn(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid BurnRequest request, Authentication auth) {
        log.info("ISSUER burn from={} amount={} on deployment={} by actor={}",
                request.fromAddress(), request.amount(), depId, actorName(auth));
        return accepted(adminService.regularBurn(depId, request.fromAddress(), request.amount()));
    }

    @PostMapping("/forced-transfer")
    public ResponseEntity<TxSubmissionResponse> forcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferRequest request, Authentication auth) {
        log.info("ISSUER forcedTransfer from={} to={} on deployment={} by actor={}",
                request.from(), request.to(), depId, actorName(auth));
        return accepted(adminService.forcedTransfer(depId, request.from(), request.to(), request.value(), request.legalBasis()));
    }

    @PostMapping("/forced-approve")
    public ResponseEntity<TxSubmissionResponse> forcedApprove(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedApproveRequest request, Authentication auth) {
        log.info("ISSUER forcedApprove owner={} spender={} on deployment={} by actor={}",
                request.owner(), request.spender(), depId, actorName(auth));
        return accepted(adminService.forcedApprove(depId, request.owner(), request.spender(), request.value(), request.legalBasis()));
    }

    private static ResponseEntity<TxSubmissionResponse> accepted(UUID txId) {
        return ResponseEntity.accepted().body(new TxSubmissionResponse(txId));
    }

    private static String actorName(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }
}
