package de.makibytes.registerwerk.registertransfer.web;

import de.makibytes.registerwerk.registertransfer.api.RegisterTransfer;
import de.makibytes.registerwerk.registertransfer.internal.RegisterTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
            @RequestBody @Valid RegisterTransferDtos.TransferInitiateRequest request) {
        RegisterTransfer created = transferService.initiate(
                request.assetId(), request.successorName(), request.successorIdentifier(),
                request.reason(), request.initiatedBy());
        return ResponseEntity.status(201).body(created);
    }

    @PostMapping("/{transferId}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID transferId) {
        byte[] json = transferService.export(transferId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"register-transfer.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/{transferId}/onchain-handover")
    public RegisterTransfer recordOnchainHandover(
            @PathVariable UUID transferId,
            @RequestBody @Valid RegisterTransferDtos.OnchainHandoverRequest request) {
        return transferService.recordOnchainHandover(transferId, request.txHash());
    }

    @PostMapping("/{transferId}/complete")
    public RegisterTransfer complete(@PathVariable UUID transferId) {
        return transferService.complete(transferId);
    }

    @PostMapping("/{transferId}/cancel")
    public RegisterTransfer cancel(
            @PathVariable UUID transferId,
            @RequestBody @Valid RegisterTransferDtos.TransferCancelRequest request) {
        return transferService.cancel(transferId, request.reason());
    }

    @GetMapping("/assets/{assetId}")
    public List<RegisterTransfer> listForAsset(@PathVariable UUID assetId) {
        return transferService.listForAsset(assetId);
    }
}
