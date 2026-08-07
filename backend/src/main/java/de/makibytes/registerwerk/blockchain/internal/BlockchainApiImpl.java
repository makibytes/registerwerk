package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class BlockchainApiImpl implements BlockchainApi {

    private final BlockchainTransactionRepository repository;

    BlockchainApiImpl(BlockchainTransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<BlockchainTransactionView> findTransaction(UUID id) {
        return repository.findById(id).map(this::toView);
    }

    @Override
    public Page<BlockchainTransactionView> findTransactionsByDeployment(UUID deploymentId, Pageable pageable) {
        return repository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId, pageable).map(this::toView);
    }

    @Override
    public Page<BlockchainTransactionView> findTransactionsByAsset(UUID assetId, Pageable pageable) {
        return repository.findByAssetIdOrderByCreatedAtDesc(assetId, pageable).map(this::toView);
    }

    @Override
    public Page<BlockchainTransactionView> findTransactionsByActor(String actorName, Pageable pageable) {
        return repository.findByActorNameOrderByCreatedAtDesc(actorName, pageable).map(this::toView);
    }

    @Override
    public Page<BlockchainTransactionView> findAllTransactions(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable).map(this::toView);
    }

    @Override
    public Page<BlockchainTransactionView> findTransactionsByStatus(String status, Pageable pageable) {
        BlockchainTransaction.Status parsed = BlockchainTransaction.Status.valueOf(status);
        return repository.findByStatusOrderByCreatedAtDesc(parsed, pageable).map(this::toView);
    }

    @Override
    public Optional<BlockchainTransactionView> findByTxHash(String txHash) {
        return repository.findByTxHash(txHash).map(this::toView);
    }

    private BlockchainTransactionView toView(BlockchainTransaction tx) {
        return new BlockchainTransactionView(
                tx.getId(), tx.getTxHash(), tx.getStatus().name(),
                tx.getMethodName(), tx.getChain(), tx.getNetwork(),
                tx.getContractAddress(), tx.getDeploymentId(), tx.getAssetId(),
                tx.getActorName(), tx.getActorRole(), tx.getParams(),
                tx.getGasUsed(), tx.getBlockNumber(), tx.getErrorMessage(),
                tx.getCreatedAt(), tx.getCompletedAt(),
                tx.getOpsNote(), tx.getOpsReviewedAt(), tx.getOpsReviewedBy());
    }
}
