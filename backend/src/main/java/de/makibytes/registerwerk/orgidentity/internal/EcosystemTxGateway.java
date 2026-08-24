package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signs and submits ecosystem contract calls with the operator wallet and records
 * the pending transaction — one shared path for OrgRegistry, PermissionRegistry
 * and the EcosystemTrustedIssuersRegistry.
 */
@Component
class EcosystemTxGateway {

    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final DurableEvmTransactionGateway durableTransactions;

    EcosystemTxGateway(
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ContractAddressConfig contractAddressConfig,
            DurableEvmTransactionGateway durableTransactions) {
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.durableTransactions = durableTransactions;
    }

    ChainConfig requireChain(UUID chainConfigId) {
        ChainConfig chain = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        return chain;
    }

    String submitToOrgRegistry(UUID chainConfigId, Function fn, Map<String, Object> params) {
        ChainConfig chain = requireChain(chainConfigId);
        return submit(chain, contractAddressConfig.requireOrgRegistry(chain.getIdentifier()), fn, params);
    }

    String submitToPermissionRegistry(UUID chainConfigId, Function fn, Map<String, Object> params) {
        ChainConfig chain = requireChain(chainConfigId);
        return submit(chain, contractAddressConfig.requirePermissionRegistry(chain.getIdentifier()), fn, params);
    }

    String submitToEcosystemTir(UUID chainConfigId, Function fn, Map<String, Object> params) {
        ChainConfig chain = requireChain(chainConfigId);
        return submit(chain, contractAddressConfig.requireEcosystemTir(chain.getIdentifier()), fn, params);
    }

    /** Read-only eth_call against the OrgRegistry (used by the reconciliation job). */
    List<Type> callOrgRegistry(ChainConfig chain, Function fn) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        return evmContractService.call(web3j, contractAddressConfig.requireOrgRegistry(chain.getIdentifier()), fn);
    }

    /** Read-only eth_call against the PermissionRegistry (used by the reconciliation job). */
    List<Type> callPermissionRegistry(ChainConfig chain, Function fn) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        return evmContractService.call(web3j, contractAddressConfig.requirePermissionRegistry(chain.getIdentifier()), fn);
    }

    private String submit(ChainConfig chain, String contractAddress, Function fn, Map<String, Object> params) {
        return durableTransactions.submit(chain.getId(), contractAddress, fn, params);
    }
}
