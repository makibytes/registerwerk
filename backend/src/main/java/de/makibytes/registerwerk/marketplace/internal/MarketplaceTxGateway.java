package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.web3j.abi.datatypes.Function;

import java.util.Map;
import java.util.UUID;

/** Signs and submits DappRegistry calls with the operator wallet, recording the pending tx. */
@Component
class MarketplaceTxGateway {

    private final ChainConfigRepository chainConfigRepository;
    private final ContractAddressConfig contractAddressConfig;
    private final BlockchainTransactionService txService;
    private final DurableEvmTransactionGateway durableTransactions;

    MarketplaceTxGateway(
            ChainConfigRepository chainConfigRepository,
            ContractAddressConfig contractAddressConfig,
            BlockchainTransactionService txService,
            DurableEvmTransactionGateway durableTransactions) {
        this.chainConfigRepository = chainConfigRepository;
        this.contractAddressConfig = contractAddressConfig;
        this.txService = txService;
        this.durableTransactions = durableTransactions;
    }

    ChainConfig requireChain(UUID chainConfigId) {
        return chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
    }

    String submitToDappRegistry(UUID chainConfigId, Function fn, Map<String, Object> params) {
        ChainConfig chain = requireChain(chainConfigId);
        String contractAddress = contractAddressConfig.requireDappRegistry(chain.getIdentifier());
        return durableTransactions.submit(chain.getId(), contractAddress, fn, params);
    }

    /** @see BlockchainTransactionService#isConfirmedSuccess */
    boolean isConfirmedSuccess(String txHash) {
        return txService.isConfirmedSuccess(txHash);
    }

    /** @see BlockchainTransactionService#isConfirmedFailure */
    boolean isConfirmedFailure(String txHash) {
        return txService.isConfirmedFailure(txHash);
    }

    /** @see BlockchainTransactionService#confirmedLocation */
    java.util.Optional<BlockchainTransactionService.ConfirmedTxLocation> confirmedLocation(String txHash) {
        return txService.confirmedLocation(txHash);
    }

}
