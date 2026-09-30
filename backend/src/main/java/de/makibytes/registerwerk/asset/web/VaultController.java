package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.idempotency.api.RequiresIdempotencyKey;
import de.makibytes.registerwerk.blockchain.api.Erc4626AdminPort;
import de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort;
import de.makibytes.registerwerk.blockchain.api.VaultRequestView;
import de.makibytes.registerwerk.blockchain.web.dto.ForceCancelVaultRequestBody;
import de.makibytes.registerwerk.blockchain.web.dto.FulfillVaultRequestBody;
import de.makibytes.registerwerk.blockchain.web.dto.NavStrikeRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Vault administration endpoints for ERC-4626 (sync) and ERC-7540 (async) tokenized vaults.
 * Moved from blockchain/web to asset/web to resolve the asset ↔ blockchain modulith cycle.
 */
@RestController
@RequestMapping("/api/v1/deployments/{depId}")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
@Validated
public class VaultController {

    private static final Logger log = LoggerFactory.getLogger(VaultController.class);

    private final Erc4626AdminPort erc4626AdminService;
    private final Erc7540AdminPort erc7540AdminService;
    private final VaultNavStrikeRepository navStrikeRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetVaultStateRepository vaultStateRepository;

    public VaultController(Erc4626AdminPort erc4626AdminService,
                           Erc7540AdminPort erc7540AdminService,
                           VaultNavStrikeRepository navStrikeRepository,
                           AssetDeploymentRepository deploymentRepository,
                           AssetVaultStateRepository vaultStateRepository) {
        this.erc4626AdminService = erc4626AdminService;
        this.erc7540AdminService = erc7540AdminService;
        this.navStrikeRepository = navStrikeRepository;
        this.deploymentRepository = deploymentRepository;
        this.vaultStateRepository = vaultStateRepository;
    }

    @RequiresIdempotencyKey
    @PostMapping("/nav-strike")
    public ResponseEntity<TxSubmissionResponse> strikeNav(
            @PathVariable UUID depId,
            @Valid @RequestBody NavStrikeRequest request,
            Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        UUID txId = erc4626AdminService.strikeNav(
                depId, request.navPerShare(), request.effectiveAt(),
                null, request.reportDocId(), actorId, SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @GetMapping("/nav-strikes")
    public ResponseEntity<List<VaultNavStrike>> listNavStrikes(@PathVariable UUID depId) {
        var dep = deploymentRepository.findById(depId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
        return ResponseEntity.ok(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(dep.getAssetId()));
    }

    /** Confirmed vault state. {@code latestNavPerShare}/{@code latestNavStrikeAt} is the strike a
     *  fulfilment will settle at (only strikes confirmed on-chain are applied here). */
    public record VaultStateResponse(
            UUID assetId, BigDecimal latestNavPerShare, Instant latestNavStrikeAt,
            BigInteger depositCap, Integer minSettlementDelay) {
        static VaultStateResponse of(UUID assetId, AssetVaultState s) {
            return s == null
                    ? new VaultStateResponse(assetId, null, null, null, null)
                    : new VaultStateResponse(assetId, s.getLatestNavPerShare(), s.getLatestNavStrikeAt(),
                            s.getDepositCap(), s.getMinSettlementDelay());
        }
    }

    @GetMapping("/vault-state")
    public ResponseEntity<VaultStateResponse> getVaultState(@PathVariable UUID depId) {
        var dep = deploymentRepository.findById(depId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
        return ResponseEntity.ok(VaultStateResponse.of(dep.getAssetId(),
                vaultStateRepository.findById(dep.getAssetId()).orElse(null)));
    }

    @GetMapping("/vault-requests")
    public ResponseEntity<List<VaultRequestView>> listVaultRequests(
            @PathVariable UUID depId,
            @RequestParam(defaultValue = "PENDING") VaultRequestStatus status) {
        return ResponseEntity.ok(erc7540AdminService.listRequestViews(depId, status));
    }

    @RequiresIdempotencyKey
    @PostMapping("/vault-requests/{requestId}/fulfill")
    public ResponseEntity<TxSubmissionResponse> fulfillVaultRequest(
            @PathVariable UUID depId,
            @PathVariable @Positive BigInteger requestId,
            @Valid @RequestBody(required = false) FulfillVaultRequestBody body,
            Authentication auth) {
        if (body != null && body.navAtFulfill() != null) {
            log.warn("Deprecated navAtFulfill={} sent for vault request={} on deployment={} — ignored; "
                    + "fulfilment settles at the NAV struck on-chain and records the executed NAV from the "
                    + "fulfilment event.", body.navAtFulfill(), requestId, depId);
        }
        UUID txId = erc7540AdminService.fulfillRequest(depId, requestId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/vault-requests/{requestId}/cancel")
    public ResponseEntity<TxSubmissionResponse> cancelVaultRequest(
            @PathVariable UUID depId,
            @PathVariable @Positive BigInteger requestId,
            Authentication auth) {
        UUID txId = erc7540AdminService.cancelRequest(depId, requestId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    /**
     * Registry force-cancel of a pending request on a legal basis — the only way to release the
     * escrow of a request held by a compliance freeze. REGISTRY_ADMIN (class level) + step-up +
     * a second approver, like every other forced operation.
     */
    @RequiresIdempotencyKey
    @PostMapping("/vault-requests/{requestId}/force-cancel")
    @RequiresStepUp(requireSecondApprover = true, reason = "VAULT_REQUEST_FORCE_CANCEL")
    public ResponseEntity<TxSubmissionResponse> forceCancelVaultRequest(
            @PathVariable UUID depId,
            @PathVariable @Positive BigInteger requestId,
            @Valid @RequestBody ForceCancelVaultRequestBody body,
            Authentication auth) {
        UUID txId = erc7540AdminService.forceCancelRequest(depId, requestId, body.to(), body.legalBasis(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }
}
