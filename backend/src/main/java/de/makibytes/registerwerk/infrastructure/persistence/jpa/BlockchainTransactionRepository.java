package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.blockchain.BlockchainTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockchainTransactionRepository extends JpaRepository<BlockchainTransaction, UUID> {

    List<BlockchainTransaction> findByStatus(BlockchainTransaction.Status status);

    Optional<BlockchainTransaction> findByTxHash(String txHash);

    Page<BlockchainTransaction> findByDeploymentIdOrderByCreatedAtDesc(UUID deploymentId, Pageable pageable);

    Page<BlockchainTransaction> findByAssetIdOrderByCreatedAtDesc(UUID assetId, Pageable pageable);

    Page<BlockchainTransaction> findByActorNameOrderByCreatedAtDesc(String actorName, Pageable pageable);

    Page<BlockchainTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
