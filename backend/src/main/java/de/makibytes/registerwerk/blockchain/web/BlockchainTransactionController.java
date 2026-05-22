package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.web.dto.TxRecordResponse;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
public class BlockchainTransactionController {

    private final BlockchainApi blockchainApi;

    public BlockchainTransactionController(BlockchainApi blockchainApi) {
        this.blockchainApi = blockchainApi;
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);

        Page<TxRecordResponse> result;
        if (deploymentId != null) {
            result = blockchainApi.findTransactionsByDeployment(deploymentId, pageable).map(TxRecordResponse::from);
        } else if (assetId != null) {
            result = blockchainApi.findTransactionsByAsset(assetId, pageable).map(TxRecordResponse::from);
        } else {
            result = blockchainApi.findAllTransactions(pageable).map(TxRecordResponse::from);
        }
        return ResponseEntity.ok(result);
    }
}
