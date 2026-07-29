package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
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
    private final BlockchainTransactionService txService;

    EcosystemTxGateway(
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
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        Credentials creds = evmContractService.credentials(chain.getId());

        String txHash = evmContractService.submit(web3j, creds, contractAddress, fn);
        txService.record(txHash, fn.getName(), null, null,
                parseChain(chain.getIdentifier()), chain.getNetworkType().name(),
                contractAddress, params);
        return txHash;
    }

    private static String parseChain(String identifier) {
        int splitIndex = identifier.lastIndexOf('_');
        return splitIndex > 0 ? identifier.substring(0, splitIndex) : identifier;
    }
}
