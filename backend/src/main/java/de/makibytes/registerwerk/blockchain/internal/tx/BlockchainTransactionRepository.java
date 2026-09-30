package de.makibytes.registerwerk.blockchain.internal.tx;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockchainTransactionRepository extends JpaRepository<BlockchainTransaction, UUID> {

    List<BlockchainTransaction> findByStatus(BlockchainTransaction.Status status);

    /** TIMEOUT rows still inside the late-mined window (P4B-5): they keep being reconciled. */
    List<BlockchainTransaction> findByStatusAndCompletedAtAfter(BlockchainTransaction.Status status, Instant cutoff);

    Optional<BlockchainTransaction> findByTxHash(String txHash);

    Page<BlockchainTransaction> findByDeploymentIdOrderByCreatedAtDesc(UUID deploymentId, Pageable pageable);

    Page<BlockchainTransaction> findByAssetIdOrderByCreatedAtDesc(UUID assetId, Pageable pageable);

    Page<BlockchainTransaction> findByActorNameOrderByCreatedAtDesc(String actorName, Pageable pageable);

    Page<BlockchainTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Confirmed confidential forced operations whose on-chain outcome is not yet verified. */
    List<BlockchainTransaction> findByMethodNameInAndStatusAndExecutionOutcomeIsNull(
            Collection<String> methodNames, BlockchainTransaction.Status status);

    Page<BlockchainTransaction> findByStatusOrderByCreatedAtDesc(BlockchainTransaction.Status status, Pageable pageable);
}
