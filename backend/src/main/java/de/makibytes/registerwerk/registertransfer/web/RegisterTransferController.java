package de.makibytes.registerwerk.registertransfer.web;

import de.makibytes.registerwerk.registertransfer.api.RegisterTransfer;
import de.makibytes.registerwerk.registertransfer.internal.RegisterTransferService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * §§21/22 eWpG register transfer endpoints — operator-only. The lifecycle is
 * initiate → export (§20 eWpRV data package) → record on-chain handover →
 * complete, with cancel available before completion.
 *
 * <p>The acting operator is always derived from the JWT ({@code Authentication}), never
 * from the request body — a client-supplied {@code initiatedBy} field would let a caller
 * attribute the action to any UUID.
 */
@RestController
@RequestMapping("/api/v1/register-transfers")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class RegisterTransferController {

    private final RegisterTransferService transferService;

    RegisterTransferController(RegisterTransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<RegisterTransfer> initiate(
            @RequestBody @Valid RegisterTransferDtos.TransferInitiateRequest request, Authentication auth) {
        RegisterTransfer created = transferService.initiate(
                request.assetId(), request.successorName(), request.successorIdentifier(),
                request.reason(), SecurityUtils.extractUserId(auth));
        return ResponseEntity.status(201).body(created);
    }

    @PostMapping("/{transferId}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID transferId, Authentication auth) {
        byte[] json = transferService.export(transferId, SecurityUtils.extractUserId(auth));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"register-transfer.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/{transferId}/onchain-handover")
    @RequiresStepUp(requireSecondApprover = true, reason = "REGISTER_TRANSFER_ONCHAIN_HANDOVER")
    public RegisterTransfer recordOnchainHandover(
            @PathVariable UUID transferId,
            @RequestBody @Valid RegisterTransferDtos.OnchainHandoverRequest request, Authentication auth) {
        return transferService.recordOnchainHandover(transferId, request.txHash(), SecurityUtils.extractUserId(auth));
    }

    @PostMapping("/{transferId}/complete")
    @RequiresStepUp(requireSecondApprover = true, reason = "REGISTER_TRANSFER_COMPLETE")
    public RegisterTransfer complete(@PathVariable UUID transferId, Authentication auth) {
        return transferService.complete(transferId, SecurityUtils.extractUserId(auth));
    }

    @PostMapping("/{transferId}/cancel")
    public RegisterTransfer cancel(
            @PathVariable UUID transferId,
            @RequestBody @Valid RegisterTransferDtos.TransferCancelRequest request, Authentication auth) {
        return transferService.cancel(transferId, request.reason(), SecurityUtils.extractUserId(auth));
    }

    @GetMapping("/assets/{assetId}")
    public List<RegisterTransfer> listForAsset(@PathVariable UUID assetId) {
        return transferService.listForAsset(assetId);
    }
}
