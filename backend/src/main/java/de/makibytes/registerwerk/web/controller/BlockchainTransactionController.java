package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.BlockchainTransactionRepository;
import de.makibytes.registerwerk.web.dto.blockchain.TxRecordResponse;
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
 *
 * <p>Frontend uses this to poll pending transactions and display history.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@PreAuthorize("isAuthenticated()")
public class BlockchainTransactionController {

    private final BlockchainTransactionRepository repository;

    public BlockchainTransactionController(BlockchainTransactionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canReadTransaction(#id, authentication)")
    public ResponseEntity<TxRecordResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(TxRecordResponse.from(
                repository.findById(id)
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
            result = repository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId, pageable)
                    .map(TxRecordResponse::from);
        } else if (assetId != null) {
            result = repository.findByAssetIdOrderByCreatedAtDesc(assetId, pageable)
                    .map(TxRecordResponse::from);
        } else {
            result = repository.findAllByOrderByCreatedAtDesc(pageable)
                    .map(TxRecordResponse::from);
        }
        return ResponseEntity.ok(result);
    }
}
