package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.LifecycleLogProjectionPort;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Token-transfer projection adapter for the Chaincache lifecycle inbox. */
@Service
class ChaincacheLogProjectionService implements LifecycleLogProjectionPort {

    private final TokenTransferRepository repository;

    ChaincacheLogProjectionService(TokenTransferRepository repository) {
        this.repository = repository;
    }

    @Override
    public UUID create(TransferProjection value) {
        TokenTransfer transfer = new TokenTransfer();
        transfer.setAssetId(value.assetId());
        transfer.setDeploymentId(value.deploymentId());
        transfer.setChainConfigId(value.chainConfigId());
        transfer.setContractAddress(value.contractAddress());
        transfer.setFromAddress(value.fromAddress());
        transfer.setToAddress(value.toAddress());
        transfer.setTokenId(value.tokenId());
        transfer.setAmount(value.amount());
        transfer.setEventType(TokenTransfer.EventType.valueOf(value.eventType().name()));
        transfer.setTxHash(value.transactionHash());
        transfer.setBlockNumber(value.blockNumber());
        transfer.setLogIndex(value.logIndex());
        transfer.setOccurredAt(value.occurredAt());
        transfer.setExplorerTxUrl(value.explorerTransactionUrl());
        transfer.setRawData(value.rawData());
        transfer.setFinalityStatus(value.finality());
        transfer.setBlockHash(value.blockHash());
        return repository.save(transfer).getId();
    }

    @Override
    public void promote(UUID transferId, FinalityLevel finality) {
        repository.updateFinalityStatus(transferId, finality);
    }

    @Override
    public void orphan(UUID transferId) {
        promote(transferId, FinalityLevel.ORPHANED);
    }
}

