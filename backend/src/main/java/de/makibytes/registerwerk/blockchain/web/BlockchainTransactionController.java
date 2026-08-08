package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.web.dto.TxRecordResponse;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-only access to blockchain transaction records.
 *
 * <p>Base path: {@code /api/v1/transactions}
 */
@RestController
@RequestMapping("/api/v1/transactions")
@PreAuthorize("isAuthenticated()")
@Validated
public class BlockchainTransactionController {

    private final BlockchainApi blockchainApi;
    private final BlockchainTransactionService transactionService;

    public BlockchainTransactionController(BlockchainApi blockchainApi, BlockchainTransactionService transactionService) {
        this.blockchainApi = blockchainApi;
        this.transactionService = transactionService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canReadTransaction(#id, authentication)")
    public ResponseEntity<TxRecordResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(TxRecordResponse.from(
                blockchainApi.findTransaction(id)
                        .orElseThrow(() -> new EntityNotFoundException("BlockchainTransaction", id))));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') "
            + "or (#assetId != null and @assetAccessChecker.canRead(#assetId, authentication)) "
            + "or (#deploymentId != null and @assetAccessChecker.canReadDeployment(#deploymentId, authentication))")
    public ResponseEntity<Page<TxRecordResponse>> list(
            @RequestParam(required = false) UUID deploymentId,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {

        Pageable pageable = PageRequest.of(page, size);

        Page<TxRecordResponse> result;
        if (deploymentId != null) {
            result = blockchainApi.findTransactionsByDeployment(deploymentId, pageable).map(TxRecordResponse::from);
        } else if (assetId != null) {
            result = blockchainApi.findTransactionsByAsset(assetId, pageable).map(TxRecordResponse::from);
        } else if (status != null) {
            // Cross-asset "what broke overnight" view — falls under this endpoint's
            // REGISTRY_ADMIN/AUDIT-only branch above since neither assetId nor deploymentId is
            // given, same as the unfiltered global list already was.
            result = blockchainApi.findTransactionsByStatus(status, pageable).map(TxRecordResponse::from);
        } else {
            result = blockchainApi.findAllTransactions(pageable).map(TxRecordResponse::from);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Annotates a FAILED/TIMEOUT transaction as handled. Not a resubmit — see
     * {@link BlockchainTransactionService#review} for why an automated gas-bump retry isn't
     * implemented.
     */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
    public ResponseEntity<TxRecordResponse> review(
            @PathVariable UUID id,
            @RequestBody @Valid ReviewRequest request,
            Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        var tx = transactionService.review(id, actorId, actorRole, request.note());
        return ResponseEntity.ok(TxRecordResponse.from(
                blockchainApi.findTransaction(tx.getId())
                        .orElseThrow(() -> new EntityNotFoundException("BlockchainTransaction", id))));
    }

    public record ReviewRequest(@NotBlank String note) {}
}
