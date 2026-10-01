package de.makibytes.registerwerk.blockchain.internal.tx;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

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

    /** Oldest-first slice of one status for the bounded pollers (7A-06). */
    List<BlockchainTransaction> findByStatusOrderByCreatedAtAsc(BlockchainTransaction.Status status, Pageable pageable);

    List<BlockchainTransaction> findByStatusAndCompletedAtAfterOrderByCompletedAtAsc(
            BlockchainTransaction.Status status, Instant cutoff, Pageable pageable);

    /** Re-read under a write lock inside the writer transaction, so a stale poller cannot overwrite a terminal state. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BlockchainTransaction t where t.id = :id")
    Optional<BlockchainTransaction> findByIdForUpdate(@Param("id") UUID id);

    Page<BlockchainTransaction> findByDeploymentIdOrderByCreatedAtDesc(UUID deploymentId, Pageable pageable);

    Page<BlockchainTransaction> findByAssetIdOrderByCreatedAtDesc(UUID assetId, Pageable pageable);

    Page<BlockchainTransaction> findByActorNameOrderByCreatedAtDesc(String actorName, Pageable pageable);

    Page<BlockchainTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Confirmed confidential forced operations whose on-chain outcome is not yet verified. */
    List<BlockchainTransaction> findByMethodNameInAndStatusAndExecutionOutcomeIsNull(
            Collection<String> methodNames, BlockchainTransaction.Status status);

    Page<BlockchainTransaction> findByStatusOrderByCreatedAtDesc(BlockchainTransaction.Status status, Pageable pageable);
}
