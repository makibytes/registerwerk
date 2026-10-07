package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.idempotency.api.RequiresIdempotencyKey;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetSlot;
import de.makibytes.registerwerk.deployment.api.AssetSlotRepository;
import de.makibytes.registerwerk.blockchain.api.Erc3525AdminPort;
import de.makibytes.registerwerk.blockchain.web.dto.CreateSlotRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedValueTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.FreezeTokenRequest;
import de.makibytes.registerwerk.blockchain.web.dto.MintIntoSlotRequest;
import de.makibytes.registerwerk.blockchain.web.dto.SftHolderRequest;
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
 * {@code createSlot} (it carries the slot's supply cap), {@code mintIntoSlot} and
 * {@code forcedValueTransfer} additionally require step-up + a second approver (review 9X-1):
 * minting bond value into a slot is the SFT counterpart of {@code IssuerTokenController.mint},
 * which already needs 4-eyes, and must not be reachable by one session. Pause/unpause stay
 * single-actor (same class as the ERC-20 {@code /pause}).
 * {@code forcedValueTransfer} was the first one to require step-up dual-control —
 * the direct EVM/Canton equivalent of an eWpG §24 forced correction requires it everywhere else
 * in this codebase, but this one lacked it. Freeze/unfreeze deliberately do NOT gain step-up here:
 * neither the EVM nor Canton equivalents of freeze/unfreeze require it either (freeze is
 * repo-wide treated as reversible/lighter-weight), so adding it only to ERC-3525 would be a new
 * inconsistency, not a fix.
 */
@RestController
@RequestMapping("/api/v1/deployments/{depId}")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
@RequiresIdempotencyKey
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
    @RequiresStepUp(requireSecondApprover = true, reason = "ERC3525_SLOT_CREATE")
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
    @RequiresStepUp(requireSecondApprover = true, reason = "ERC3525_SLOT_MINT")
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

    // ── Holder (address) controls ────────────────────────────────────────────
    // Same step-up policy as TokenAdminController's /whitelist and /freeze (none — reversible).

    @PostMapping("/holders/whitelist")
    public ResponseEntity<TxSubmissionResponse> whitelistHolder(
            @PathVariable UUID depId, @Valid @RequestBody SftHolderRequest request, Authentication auth) {
        UUID txId = erc3525AdminService.whitelistAddress(depId, request.address(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/holders/unwhitelist")
    public ResponseEntity<TxSubmissionResponse> unwhitelistHolder(
            @PathVariable UUID depId, @Valid @RequestBody SftHolderRequest request, Authentication auth) {
        UUID txId = erc3525AdminService.unwhitelistAddress(depId, request.address(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/holders/freeze")
    public ResponseEntity<TxSubmissionResponse> freezeHolder(
            @PathVariable UUID depId, @Valid @RequestBody SftHolderRequest request, Authentication auth) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reason is required to freeze an address");
        }
        UUID txId = erc3525AdminService.freezeAddress(depId, request.address(), request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/holders/unfreeze")
    public ResponseEntity<TxSubmissionResponse> unfreezeHolder(
            @PathVariable UUID depId, @Valid @RequestBody SftHolderRequest request, Authentication auth) {
        UUID txId = erc3525AdminService.unfreezeAddress(depId, request.address(),
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
