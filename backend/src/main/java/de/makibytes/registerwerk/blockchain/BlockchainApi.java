package de.makibytes.registerwerk.blockchain;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/** Public API for querying blockchain transaction records. */
public interface BlockchainApi {

    Optional<BlockchainTransactionView> findTransaction(UUID id);

    Page<BlockchainTransactionView> findTransactionsByDeployment(UUID deploymentId, Pageable pageable);

    Page<BlockchainTransactionView> findTransactionsByAsset(UUID assetId, Pageable pageable);

    Page<BlockchainTransactionView> findTransactionsByActor(String actorName, Pageable pageable);

    Page<BlockchainTransactionView> findAllTransactions(Pageable pageable);

    Optional<BlockchainTransactionView> findByTxHash(String txHash);
}
