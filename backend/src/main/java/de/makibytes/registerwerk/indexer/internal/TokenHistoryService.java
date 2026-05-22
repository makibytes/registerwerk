package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only service for querying the token transfer history and computing aggregate statistics
 * for a given asset deployment.
 */
@Service
@Transactional(readOnly = true)
public class TokenHistoryService {

    /**
     * Aggregate statistics for a single deployment.
     *
     * @param totalMints         Total number of mint events.
     * @param totalBurns         Total number of burn events.
     * @param totalTransfers     Total number of regular transfer events.
     * @param currentHolderCount Approximate distinct holder count based on recorded transfers.
     */
    public record TokenTransferSummary(
            long totalMints,
            long totalBurns,
            long totalTransfers,
            long currentHolderCount
    ) {}

    private final TokenTransferRepository tokenTransferRepository;

    public TokenHistoryService(TokenTransferRepository tokenTransferRepository) {
        this.tokenTransferRepository = tokenTransferRepository;
    }

    /**
     * Returns a paginated list of transfers linked to a specific deployment, newest first.
     */
    public Page<TokenTransfer> getTransfersByDeployment(UUID deploymentId, Pageable pageable) {
        return tokenTransferRepository.findByDeploymentIdOrderByOccurredAtDesc(deploymentId, pageable);
    }

    /**
     * Returns a paginated list of all transfers linked to any deployment of the given asset,
     * newest first.
     */
    public Page<TokenTransfer> getTransfersByAsset(UUID assetId, Pageable pageable) {
        return tokenTransferRepository.findByAssetIdOrderByOccurredAtDesc(assetId, pageable);
    }

    /**
     * Returns a paginated list of transfers for a given on-chain contract address, newest first.
     */
    public Page<TokenTransfer> getTransfersByContractAddress(String contractAddress, Pageable pageable) {
        return tokenTransferRepository
                .findByContractAddressOrderByOccurredAtDesc(contractAddress, pageable);
    }

    /**
     * Computes aggregate transfer statistics for a deployment.
     *
     * <p>Holder count is approximated by collecting all unique {@code toAddress} values across
     * all transfers for the deployment, minus addresses that have since burned their entire
     * position (i.e. appear as {@code fromAddress} in a BURN event). For production use, a
     * dedicated SQL query or materialised view would be more efficient.
     */
    public TokenTransferSummary getSummary(UUID deploymentId) {
        // Load all transfers for the deployment (unbounded — suitable for moderate-size deployments;
        // replace with @Query aggregate queries for very large datasets).
        List<TokenTransfer> all = tokenTransferRepository
                .findByDeploymentIdOrderByOccurredAtDesc(deploymentId, Pageable.unpaged())
                .getContent();

        long mints = all.stream()
                .filter(t -> t.getEventType() == TokenTransfer.EventType.MINT)
                .count();
        long burns = all.stream()
                .filter(t -> t.getEventType() == TokenTransfer.EventType.BURN)
                .count();
        long transfers = all.stream()
                .filter(t -> t.getEventType() == TokenTransfer.EventType.TRANSFER)
                .count();

        // Approximate holder set: all unique recipients minus known full-burners.
        Set<String> recipients = all.stream()
                .filter(t -> t.getToAddress() != null)
                .map(TokenTransfer::getToAddress)
                .collect(Collectors.toSet());

        Set<String> burners = all.stream()
                .filter(t -> t.getEventType() == TokenTransfer.EventType.BURN
                        && t.getFromAddress() != null)
                .map(TokenTransfer::getFromAddress)
                .collect(Collectors.toSet());

        // Remove addresses that have burned everything they ever received.
        // This is a heuristic — a proper balance-aware computation would require tracking amounts.
        Set<String> approximateHolders = recipients.stream()
                .filter(addr -> !burners.contains(addr))
                .collect(Collectors.toSet());

        return new TokenTransferSummary(mints, burns, transfers, approximateHolders.size());
    }
}
