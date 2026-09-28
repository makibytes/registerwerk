package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;

import java.util.List;
import java.util.Set;

/**
 * T3-07: verifies on-chain that control of a deployment was really handed to the successor - the
 * token's {@code registry()} (EwpgCompliance-family) or {@code owner()} (T-REX suites) must equal
 * the successor address named at initiation. Only EVM chains are verified here; the other chains
 * need an explicit operator attestation (chain verification is deferred to Phase 4).
 */
@Component
public class OnchainHandoverVerifier {

    private static final Logger log = LoggerFactory.getLogger(OnchainHandoverVerifier.class);
    private static final Set<Chain> NON_EVM = Set.of(Chain.SOLANA, Chain.STARKNET, Chain.STELLAR, Chain.CANTON);

    /** @param function the getter that answered ({@code registry} / {@code owner}); observed is its value */
    public record Observation(String function, String observed) {}

    private final EvmContractService evm;

    public OnchainHandoverVerifier(EvmContractService evm) {
        this.evm = evm;
    }

    public static boolean isEvm(Chain chain) {
        return chain != null && !NON_EVM.contains(chain);
    }

    /**
     * Reads the controlling address of the deployment.
     * @throws IllegalStateException if neither getter can be read (fail closed)
     */
    public Observation observeController(AssetDeployment deployment) {
        if (deployment.getChainConfigId() == null || deployment.getContractAddress() == null) {
            throw new IllegalStateException("Deployment " + deployment.getId()
                    + " has no chain configuration / contract address - cannot verify the handover");
        }
        Web3j web3j = evm.evmClient(deployment.getChainConfigId());
        RuntimeException last = null;
        for (String getter : List.of("registry", "owner")) {
            try {
                List<Type> out = evm.call(web3j, deployment.getContractAddress(),
                        new Function(getter, List.of(), List.of(new TypeReference<Address>() {})));
                if (!out.isEmpty()) {
                    return new Observation(getter, ((Address) out.get(0)).getValue());
                }
            } catch (RuntimeException e) {
                last = e;
                log.debug("Handover check {}() failed on deployment {}: {}", getter, deployment.getId(), e.getMessage());
            }
        }
        throw new IllegalStateException("Could not read registry()/owner() of deployment " + deployment.getId()
                + " on chain - handover not verified" + (last != null ? " (" + last.getMessage() + ")" : ""));
    }
}
