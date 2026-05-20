package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetSlot;
import de.makibytes.registerwerk.asset.api.AssetSlotRepository;
import de.makibytes.registerwerk.blockchain.internal.Erc3525AdminService;
import de.makibytes.registerwerk.blockchain.web.dto.CreateSlotRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedValueTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.FreezeTokenRequest;
import de.makibytes.registerwerk.blockchain.web.dto.MintIntoSlotRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * ERC-3525 slot and token administration endpoints.
 *
 * <pre>
 *   POST /api/v1/deployments/{depId}/slots                              — create a slot (bond series)
 *   GET  /api/v1/deployments/{depId}/slots                              — list slots
 *   POST /api/v1/deployments/{depId}/slots/{slotId}/pause               — pause all value transfers in slot
 *   POST /api/v1/deployments/{depId}/slots/{slotId}/unpause             — resume transfers
 *   POST /api/v1/deployments/{depId}/slots/{slotId}/mint                — mint value into slot for an address
 *   POST /api/v1/deployments/{depId}/tokens/{tokenId}/freeze            — freeze a specific holding (AWG §17)
 *   POST /api/v1/deployments/{depId}/tokens/{tokenId}/unfreeze          — lift freeze
 *   POST /api/v1/deployments/{depId}/tokens/{tokenId}/forced-value-transfer — eWpG §24 value transfer
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/deployments/{depId}")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class Erc3525SlotController {

    private final Erc3525AdminService erc3525AdminService;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetSlotRepository slotRepository;

    public Erc3525SlotController(Erc3525AdminService erc3525AdminService,
                                 AssetDeploymentRepository deploymentRepository,
                                 AssetSlotRepository slotRepository) {
        this.erc3525AdminService = erc3525AdminService;
        this.deploymentRepository = deploymentRepository;
        this.slotRepository = slotRepository;
    }

    // ── Slot operations ───────────────────────────────────────────────────────

    @PostMapping("/slots")
    public ResponseEntity<TxSubmissionResponse> createSlot(
            @PathVariable UUID depId,
            @Valid @RequestBody CreateSlotRequest request) {
        UUID txId = erc3525AdminService.createSlot(
                depId, request.slotId(), request.name(), request.metadata(), request.supplyCap());
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @GetMapping("/slots")
    public ResponseEntity<List<AssetSlot>> listSlots(@PathVariable UUID depId) {
        AssetDeployment dep = requireDeployment(depId);
        return ResponseEntity.ok(slotRepository.findByAssetId(dep.getAssetId()));
    }

    @PostMapping("/slots/{slotId}/pause")
    public ResponseEntity<TxSubmissionResponse> pauseSlot(
            @PathVariable UUID depId,
            @PathVariable BigInteger slotId) {
        UUID txId = erc3525AdminService.pauseSlot(depId, slotId);
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/slots/{slotId}/unpause")
    public ResponseEntity<TxSubmissionResponse> unpauseSlot(
            @PathVariable UUID depId,
            @PathVariable BigInteger slotId) {
        UUID txId = erc3525AdminService.unpauseSlot(depId, slotId);
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/slots/{slotId}/mint")
    public ResponseEntity<TxSubmissionResponse> mintIntoSlot(
            @PathVariable UUID depId,
            @PathVariable BigInteger slotId,
            @Valid @RequestBody MintIntoSlotRequest request) {
        UUID txId = erc3525AdminService.mintIntoSlot(depId, slotId, request.toAddress(), request.value());
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    // ── Token operations ──────────────────────────────────────────────────────

    @PostMapping("/tokens/{tokenId}/freeze")
    public ResponseEntity<TxSubmissionResponse> freezeToken(
            @PathVariable UUID depId,
            @PathVariable BigInteger tokenId,
            @Valid @RequestBody FreezeTokenRequest request) {
        UUID txId = erc3525AdminService.freezeToken(depId, tokenId, request.reason());
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/tokens/{tokenId}/unfreeze")
    public ResponseEntity<TxSubmissionResponse> unfreezeToken(
            @PathVariable UUID depId,
            @PathVariable BigInteger tokenId) {
        UUID txId = erc3525AdminService.unfreezeToken(depId, tokenId);
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    @PostMapping("/tokens/{tokenId}/forced-value-transfer")
    public ResponseEntity<TxSubmissionResponse> forcedValueTransfer(
            @PathVariable UUID depId,
            @PathVariable BigInteger tokenId,
            @Valid @RequestBody ForcedValueTransferRequest request) {
        UUID txId = erc3525AdminService.forcedValueTransfer(
                depId, tokenId, request.toTokenId(), request.value(), request.legalBasis());
        return ResponseEntity.ok(new TxSubmissionResponse(txId));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID depId) {
        return deploymentRepository.findById(depId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
    }
}
