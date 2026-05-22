package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link TokenTransfer} entities.
 */
public interface TokenTransferRepository extends JpaRepository<TokenTransfer, UUID> {

    /** Returns all transfers for a given contract address, newest first. */
    Page<TokenTransfer> findByContractAddressOrderByOccurredAtDesc(String contractAddress, Pageable pageable);

    /** Returns all transfers linked to a specific asset, newest first. */
    Page<TokenTransfer> findByAssetIdOrderByOccurredAtDesc(UUID assetId, Pageable pageable);

    /** Returns all transfers linked to a specific deployment, newest first. */
    Page<TokenTransfer> findByDeploymentIdOrderByOccurredAtDesc(UUID deploymentId, Pageable pageable);

    /**
     * Returns transfers on a given chain that occurred after (strictly greater than) the
     * given block number. Used by the indexer to resume from a checkpoint.
     */
    List<TokenTransfer> findByChainConfigIdAndBlockNumberGreaterThan(UUID chainConfigId, Long fromBlock);

    /**
     * Deduplication check: returns true if a transfer with the same chain, transaction hash,
     * and log index already exists in the database.
     *
     * @param logIndex null is valid for Solana transfers (no EVM log index)
     */
    boolean existsByChainConfigIdAndTxHashAndLogIndex(UUID chainConfigId, String txHash, Integer logIndex);

    /**
     * Returns the most recent transfer for a given chain, used to determine the high-water mark
     * for the next sync window.
     */
    Optional<TokenTransfer> findTopByChainConfigIdOrderByBlockNumberDesc(UUID chainConfigId);
}
