package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetSlot;
import de.makibytes.registerwerk.deployment.api.AssetSlotRepository;
import de.makibytes.registerwerk.blockchain.api.Erc3525AdminPort;
import de.makibytes.registerwerk.blockchain.web.dto.CreateSlotRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedValueTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.FreezeTokenRequest;
import de.makibytes.registerwerk.blockchain.web.dto.MintIntoSlotRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * ERC-3525 slot and token administration endpoints.
 * Moved from blockchain/web to asset/web to resolve the asset ↔ blockchain modulith cycle.
 *
 * <p>Every state-mutating endpoint threads {@code actorId}/{@code actorRole} through to the
 * service, so these admin actions are audited.
 * {@code forcedValueTransfer} additionally requires step-up dual-control —
 * the direct EVM/Canton equivalent of an eWpG §24 forced correction requires it everywhere else
 * in this codebase, but this one lacked it. Freeze/unfreeze deliberately do NOT gain step-up here:
 * neither the EVM nor Canton equivalents of freeze/unfreeze require it either (freeze is
 * repo-wide treated as reversible/lighter-weight), so adding it only to ERC-3525 would be a new
 * inconsistency, not a fix.
 */
@RestController
@RequestMapping("/api/v1/deployments/{depId}")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class Erc3525SlotController {

    private final Erc3525AdminPort erc3525AdminService;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetSlotRepository slotRepository;

    public Erc3525SlotController(Erc3525AdminPort erc3525AdminService,
                                 AssetDeploymentRepository deploymentRepository,
                                 AssetSlotRepository slotRepository) {
        this.erc3525AdminService = erc3525AdminService;
        this.deploymentRepository = deploymentRepository;
        this.slotRepository = slotRepository;
    }

    @PostMapping("/slots")
    public ResponseEntity<TxSubmissionResponse> createSlot(
            @PathVariable UUID depId,
            @Valid @RequestBody CreateSlotRequest request,
            Authentication auth) {
        UUID txId = erc3525AdminService.createSlot(
                depId, request.slotId(), request.name(), request.metadata(), request.supplyCap(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @GetMapping("/slots")
    public ResponseEntity<List<AssetSlot>> listSlots(@PathVariable UUID depId) {
        var dep = deploymentRepository.findById(depId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
        return ResponseEntity.ok(slotRepository.findByAssetId(dep.getAssetId()));
    }

    @PostMapping("/slots/{slotId}/pause")
    public ResponseEntity<TxSubmissionResponse> pauseSlot(
            @PathVariable UUID depId, @PathVariable BigInteger slotId, Authentication auth) {
        UUID txId = erc3525AdminService.pauseSlot(depId, slotId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/slots/{slotId}/unpause")
    public ResponseEntity<TxSubmissionResponse> unpauseSlot(
            @PathVariable UUID depId, @PathVariable BigInteger slotId, Authentication auth) {
        UUID txId = erc3525AdminService.unpauseSlot(depId, slotId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/slots/{slotId}/mint")
    public ResponseEntity<TxSubmissionResponse> mintIntoSlot(
            @PathVariable UUID depId, @PathVariable BigInteger slotId,
            @Valid @RequestBody MintIntoSlotRequest request, Authentication auth) {
        UUID txId = erc3525AdminService.mintIntoSlot(depId, slotId, request.toAddress(), request.value(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/tokens/{tokenId}/freeze")
    public ResponseEntity<TxSubmissionResponse> freezeToken(
            @PathVariable UUID depId, @PathVariable BigInteger tokenId,
            @Valid @RequestBody FreezeTokenRequest request, Authentication auth) {
        UUID txId = erc3525AdminService.freezeToken(depId, tokenId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/tokens/{tokenId}/unfreeze")
    public ResponseEntity<TxSubmissionResponse> unfreezeToken(
            @PathVariable UUID depId, @PathVariable BigInteger tokenId, Authentication auth) {
        UUID txId = erc3525AdminService.unfreezeToken(depId, tokenId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/tokens/{tokenId}/forced-value-transfer")
    @RequiresStepUp(requireSecondApprover = true, reason = "ERC3525_FORCED_VALUE_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> forcedValueTransfer(
            @PathVariable UUID depId, @PathVariable BigInteger tokenId,
            @Valid @RequestBody ForcedValueTransferRequest request, Authentication auth) {
        UUID txId = erc3525AdminService.forcedValueTransfer(
                depId, tokenId, request.toTokenId(), request.value(), request.legalBasis(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }
}
