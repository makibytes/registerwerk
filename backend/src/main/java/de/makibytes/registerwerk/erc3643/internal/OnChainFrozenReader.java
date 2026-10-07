package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code isFrozen(address)} from a deployment's token, the ground truth the nightly Sperrvermerk reconcile
 * compares the register against (H5). Every EVM standard the sync freezes exposes the same getter: the T-REX token,
 * {@code EwpgCompliance} (ERC-20/721/1155/3525/4626/7540) and the confidential ERC-3643's public mapping.
 * An unreadable value is {@link Optional#empty()}, never "not frozen": an RPC outage must not raise drift.
 */
@Component
class OnChainFrozenReader {

    private static final Logger log = LoggerFactory.getLogger(OnChainFrozenReader.class);
    private static final Set<Chain> NON_EVM_CHAINS = EnumSet.of(Chain.SOLANA, Chain.STARKNET, Chain.STELLAR, Chain.CANTON);

    private final EvmContractService evm;
    private final Erc3643SuiteRepository suites;

    OnChainFrozenReader(EvmContractService evm, Erc3643SuiteRepository suites) {
        this.evm = evm;
        this.suites = suites;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    Optional<Boolean> isFrozen(AssetDeployment dep, String wallet) {
        if (dep.getChainConfigId() == null || (dep.getChain() != null && NON_EVM_CHAINS.contains(dep.getChain()))) {
            return Optional.empty();
        }
        try {
            String token = suites.findByAssetDeploymentId(dep.getId())
                    .map(s -> s.getTokenAddress()).orElse(dep.getContractAddress());
            if (token == null) {
                return Optional.empty();
            }
            List<Type> out = evm.call(evm.evmClient(dep.getChainConfigId()), token,
                    new Function("isFrozen", List.of(new Address(wallet)), List.of(new TypeReference<Bool>() { })));
            return out.isEmpty() ? Optional.empty() : Optional.of((Boolean) out.get(0).getValue());
        } catch (RuntimeException e) {
            log.warn("isFrozen({}) could not be read on deployment {}: {}", wallet, dep.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
