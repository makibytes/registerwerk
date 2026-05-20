package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.blockchain.internal.Erc4626AdminService;
import de.makibytes.registerwerk.blockchain.internal.Erc7540AdminService;
import de.makibytes.registerwerk.blockchain.web.dto.FulfillVaultRequestBody;
import de.makibytes.registerwerk.blockchain.web.dto.NavStrikeRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.asset.api.VaultNavStrike;
import de.makibytes.registerwerk.asset.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.asset.api.VaultRequest;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * Vault administration endpoints for ERC-4626 (sync) and ERC-7540 (async) tokenized vaults.
 *
 * <pre>
 *   POST /api/v1/deployments/{depId}/nav-strike                     — strike a new NAV
 *   GET  /api/v1/deployments/{depId}/nav-strikes                    — NAV history
 *   GET  /api/v1/deployments/{depId}/vault-requests?status=PENDING  — async request queue
 *   POST /api/v1/deployments/{depId}/vault-requests/{reqId}/fulfill — fulfill request
 *   POST /api/v1/deployments/{depId}/vault-requests/{reqId}/cancel  — cancel request
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/deployments/{depId}")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class VaultController {

    private final Erc4626AdminService erc4626AdminService;
    private final Erc7540AdminService erc7540AdminService;
    private final VaultNavStrikeRepository navStrikeRepository;
    private final AssetDeploymentRepository deploymentRepository;

    public VaultController(Erc4626AdminService erc4626AdminService,
                           Erc7540AdminService erc7540AdminService,
                           VaultNavStrikeRepository navStrikeRepository,
                           AssetDeploymentRepository deploymentRepository) {
        this.erc4626AdminService = erc4626AdminService;
        this.erc7540AdminService = erc7540AdminService;
        this.navStrikeRepository = navStrikeRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @PostMapping("/nav-strike")
    public ResponseEntity<TxSubmissionResponse> strikeNav(
            @PathVariable UUID depId,
            @Valid @RequestBody NavStrikeRequest request,
            Authentication auth) {
        UUID actorId = actorIdFrom(auth);
        UUID txId = erc4626AdminService.strikeNav(
                depId, request.navPerShare(), request.effectiveAt(),
                null, request.reportDocId(), actorId);
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @GetMapping("/nav-strikes")
    public ResponseEntity<List<VaultNavStrike>> listNavStrikes(@PathVariable UUID depId) {
        AssetDeployment dep = deploymentRepository.findById(depId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
        return ResponseEntity.ok(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(dep.getAssetId()));
    }

    @GetMapping("/vault-requests")
    public ResponseEntity<List<VaultRequest>> listVaultRequests(
            @PathVariable UUID depId,
            @RequestParam(required = false, defaultValue = "PENDING") String status) {
        AssetDeployment dep = deploymentRepository.findById(depId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
        return ResponseEntity.ok(erc7540AdminService.listPendingRequests(dep.getAssetId()));
    }

    @PostMapping("/vault-requests/{requestId}/fulfill")
    public ResponseEntity<TxSubmissionResponse> fulfillVaultRequest(
            @PathVariable UUID depId,
            @PathVariable BigInteger requestId,
            @Valid @RequestBody FulfillVaultRequestBody body,
            Authentication auth) {
        UUID txId = erc7540AdminService.fulfillDepositRequest(depId, requestId, body.navAtFulfill(), actorIdFrom(auth));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/vault-requests/{requestId}/cancel")
    public ResponseEntity<TxSubmissionResponse> cancelVaultRequest(
            @PathVariable UUID depId,
            @PathVariable BigInteger requestId,
            Authentication auth) {
        UUID txId = erc7540AdminService.cancelDepositRequest(depId, requestId, actorIdFrom(auth));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    private UUID actorIdFrom(Authentication auth) {
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(auth.getName().getBytes());
        }
    }
}
