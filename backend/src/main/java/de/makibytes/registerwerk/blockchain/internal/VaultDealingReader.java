package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.VaultDealingState;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Live ({@code eth_call}) reads of an ERC-7540 vault's forward-pricing state (T1-07): the dealing cut-off the
 * registry configured, the next dealing point, when the latest NAV was struck and the dealing point of a given
 * request. The chain is the single source of truth — nothing is mirrored into the database, so there is no copy
 * to go stale or to compensate on a reorg. Every read fails soft ({@link Optional#empty()} = unknown); callers
 * that gate on the result (the production subscription gate) treat unknown as "refuse".
 */
@Component
class VaultDealingReader {

    private static final Logger log = LoggerFactory.getLogger(VaultDealingReader.class);

    /** Output of the uint256 views; every call site spells its function name as a literal (the H9 source scan reads them). */
    private static final List<TypeReference<?>> UINT = List.of(new TypeReference<Uint256>() {});

    private final EvmContractService evm;
    private final AssetDeploymentRepository deployments;

    VaultDealingReader(EvmContractService evm, AssetDeploymentRepository deployments) {
        this.evm = evm;
        this.deployments = deployments;
    }

    /** @return the vault's dealing state; empty when it cannot be read (node down, or a contract without the views) */
    Optional<VaultDealingState> readState(AssetDeployment deployment) {
        try {
            Web3j web3j = evm.evmClient(deployment.getChainConfigId());
            boolean configured = (Boolean) read(web3j, deployment, new Function("dealingCutoffConfigured",
                    List.of(), List.of(new TypeReference<Bool>() {})));
            Instant navStruckAt = instantOrNull(readUint(web3j, deployment,
                    new Function("navStruckAt", List.of(), UINT)));
            if (!configured) {
                return Optional.of(new VaultDealingState(true, true, false, null, null, null, navStruckAt));
            }
            BigInteger cutoff = readUint(web3j, deployment,
                    new Function("dealingCutoffSecondsOfDay", List.of(), UINT));
            BigInteger period = readUint(web3j, deployment, new Function("dealingPeriodSecs", List.of(), UINT));
            BigInteger next = readUint(web3j, deployment, new Function("nextDealingPoint", List.of(), UINT));
            return Optional.of(new VaultDealingState(true, true, true, cutoff.intValueExact(),
                    period.longValueExact(), instantOrNull(next), navStruckAt));
        } catch (RuntimeException e) {
            log.warn("Dealing cut-off of vault {} could not be read: {}", deployment.getContractAddress(), e.getMessage());
            return Optional.empty();
        }
    }

    /** @return the request's dealing point (unix seconds; 0 = placed before the cut-off was configured); empty when unreadable */
    Optional<BigInteger> dealingPointOf(AssetDeployment deployment, BigInteger requestId) {
        try {
            Web3j web3j = evm.evmClient(deployment.getChainConfigId());
            @SuppressWarnings("unchecked")
            List<Type> out = evm.call(web3j, deployment.getContractAddress(), new Function("dealingPointOf",
                    List.of(new Uint256(requestId)), List.of(new TypeReference<Uint256>() {})));
            return out.isEmpty() ? Optional.empty() : Optional.of((BigInteger) out.get(0).getValue());
        } catch (RuntimeException e) {
            log.warn("Dealing point of request {} on vault {} could not be read: {}",
                    requestId, deployment.getContractAddress(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether a fulfilment of {@code requestId} is only waiting for a NAV struck after its dealing point
     * (T1-07), as opposed to having failed for another reason.
     *
     * @return empty when the vault or its state cannot be determined
     */
    Optional<Boolean> isAwaitingNavStrike(UUID assetId, UUID chainConfigId, BigInteger requestId) {
        if (assetId == null || chainConfigId == null || requestId == null) return Optional.empty();
        Optional<AssetDeployment> vault = deployments.findByAssetId(assetId).stream()
                .filter(d -> chainConfigId.equals(d.getChainConfigId()))
                .filter(d -> d.getContractAddress() != null && !d.getContractAddress().isBlank())
                .findFirst();
        if (vault.isEmpty()) return Optional.empty();
        Optional<BigInteger> dealingPoint = dealingPointOf(vault.get(), requestId);
        if (dealingPoint.isEmpty()) return Optional.empty();
        if (dealingPoint.get().signum() == 0) return Optional.of(false);   // legacy request: no forward pricing
        Optional<VaultDealingState> state = readState(vault.get());
        if (state.isEmpty()) return Optional.empty();
        Instant struckAt = state.get().navStruckAt();
        return Optional.of(struckAt == null || struckAt.getEpochSecond() < dealingPoint.get().longValueExact());
    }

    private static Instant instantOrNull(BigInteger epochSeconds) {
        return epochSeconds == null || epochSeconds.signum() == 0 ? null : Instant.ofEpochSecond(epochSeconds.longValueExact());
    }

    private BigInteger readUint(Web3j web3j, AssetDeployment deployment, Function function) {
        return (BigInteger) read(web3j, deployment, function);
    }

    @SuppressWarnings("rawtypes")
    private Object read(Web3j web3j, AssetDeployment deployment, Function function) {
        List<Type> out = evm.call(web3j, deployment.getContractAddress(), function);
        if (out.isEmpty()) {
            throw new IllegalStateException(function.getName() + "() returned no value");
        }
        return out.get(0).getValue();
    }
}
