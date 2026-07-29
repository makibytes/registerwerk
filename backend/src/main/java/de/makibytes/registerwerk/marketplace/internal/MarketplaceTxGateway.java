package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Signs and submits DappRegistry calls with the operator wallet, recording the pending tx. */
@Component
class MarketplaceTxGateway {

    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final BlockchainTransactionService txService;

    MarketplaceTxGateway(
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ContractAddressConfig contractAddressConfig,
            BlockchainTransactionService txService) {
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.txService = txService;
    }

    ChainConfig requireChain(UUID chainConfigId) {
        return chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
    }

    String submitToDappRegistry(UUID chainConfigId, Function fn, Map<String, Object> params) {
        ChainConfig chain = requireChain(chainConfigId);
        String contractAddress = contractAddressConfig.requireDappRegistry(chain.getIdentifier());
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        Credentials creds = evmContractService.credentials(chain.getId());

        String txHash = evmContractService.submit(web3j, creds, contractAddress, fn);
        txService.record(txHash, fn.getName(), null, null,
                parseChain(chain.getIdentifier()), chain.getNetworkType().name(),
                contractAddress, params);
        return txHash;
    }

    Optional<org.web3j.protocol.core.methods.response.TransactionReceipt> receipt(UUID chainConfigId, String txHash) {
        ChainConfig chain = chainConfigRepository.findById(chainConfigId).orElse(null);
        if (chain == null) return Optional.empty();
        try {
            Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
            return web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String parseChain(String identifier) {
        int splitIndex = identifier.lastIndexOf('_');
        return splitIndex > 0 ? identifier.substring(0, splitIndex) : identifier;
    }
}
