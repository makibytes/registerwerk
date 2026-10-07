package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production-mode readiness WARNING for the ClaimIssuer manager key (decision of the Wave 1
 * remediation: the chain's registry signer stays the ClaimIssuer manager).
 *
 * <p>Why the registry signer is the manager: the backend signs KYC/AML claims <em>and</em> revokes
 * them with {@code ClaimIssuer.revokeClaimBySignature}, which ONCHAINID gates with {@code onlyManager}.
 * A cold manager would make every automatic revocation (KYC lapse, sanctions, offboarding) revert.
 * The price is that one hot key can both sign claims and administer the issuer (add or remove claim
 * keys, revoke arbitrary signatures). This check makes that trade-off visible instead of silent: when
 * production mode is on and the registry signer holds a MANAGEMENT (ERC-734 purpose 1) key on a
 * configured ClaimIssuer, it logs at ERROR and sets the gauge
 * {@code registerwerk_claim_issuer_hot_manager}. It never fails the boot: it is a standing risk to be
 * accepted or removed by key custody (HSM/KMS signer) and an on-chain manager rotation, not a
 * misconfiguration that stops the registry from starting.
 *
 * <p>A chain whose ClaimIssuer cannot be read (RPC down, no {@code chain_config}) is reported through
 * {@code registerwerk_claim_issuer_manager_unverified} and a warning; it is never treated as safe.
 * The check runs once after startup on its own thread so a slow node cannot delay readiness.
 */
@Component
class ClaimIssuerManagerReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ClaimIssuerManagerReadinessCheck.class);

    /** ERC-734 key purpose 1 = MANAGEMENT. */
    private static final BigInteger MANAGEMENT_PURPOSE = BigInteger.ONE;

    private final ProductionMode productionMode;
    private final ContractAddressConfig addresses;
    private final ChainConfigRepository chainConfigs;
    private final BlockchainClientRegistry clients;
    private final EvmContractService evm;
    private final AtomicInteger hotManagerChains = new AtomicInteger();
    private final AtomicInteger unverifiedChains = new AtomicInteger();

    ClaimIssuerManagerReadinessCheck(Environment environment, ContractAddressConfig addresses,
                                     ChainConfigRepository chainConfigs, BlockchainClientRegistry clients,
                                     EvmContractService evm, MeterRegistry meters) {
        this.productionMode = ProductionMode.of(environment);
        this.addresses = addresses;
        this.chainConfigs = chainConfigs;
        this.clients = clients;
        this.evm = evm;
        Gauge.builder("registerwerk_claim_issuer_hot_manager", hotManagerChains, AtomicInteger::get)
                .description("Chains where the hot registry signer is also a MANAGEMENT key of the ClaimIssuer "
                        + "(production mode; accepted trade-off, see the production runbook)")
                .register(meters);
        Gauge.builder("registerwerk_claim_issuer_manager_unverified", unverifiedChains, AtomicInteger::get)
                .description("Chains whose ClaimIssuer manager set could not be read at startup (production mode)")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (productionMode.enabled()) {
            Thread.startVirtualThread(this::evaluate);
        }
    }

    /**
     * @return the chain identifiers whose ClaimIssuer lists the registry signer as a manager;
     *         empty (and nothing read) outside production mode
     */
    List<String> evaluate() {
        if (!productionMode.enabled()) {
            return List.of();
        }
        List<String> flagged = new ArrayList<>();
        int unverified = 0;
        for (Map.Entry<String, String> entry : addresses.getClaimIssuer().entrySet()) {
            String claimIssuer = entry.getValue();
            if (claimIssuer == null || claimIssuer.isBlank()) {
                continue;
            }
            String identifier = entry.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                Optional<ChainConfig> chain = chainConfigs.findByIdentifier(identifier);
                if (chain.isEmpty()) {
                    unverified++;
                    log.warn("ClaimIssuer {} is configured for {} but there is no chain_config row; its manager "
                            + "set cannot be checked.", claimIssuer, identifier);
                    continue;
                }
                String signer = evm.signer(chain.get().getId()).address();
                if (registrySignerIsManager(identifier, claimIssuer, signer)) {
                    flagged.add(identifier);
                    log.error("*** SECURITY: on {} the hot registry signer {} is a MANAGEMENT key of the ClaimIssuer "
                            + "{}. One key can sign claims and administer the issuer. This is the accepted default "
                            + "(the backend revokes claims with revokeClaimBySignature, which needs a manager), but "
                            + "it must be backed by HSM/KMS custody of that key. To split the roles, deploy the "
                            + "issuer with CLAIM_ISSUER_MANAGEMENT_KEY set to a cold key and route revocation "
                            + "through it (docs: operator/blockchain/erc3643-setup). ***",
                            identifier, signer, claimIssuer);
                }
            } catch (RuntimeException e) {
                unverified++;
                log.warn("Could not read the manager set of ClaimIssuer {} on {}: {}", claimIssuer, identifier,
                        e.getMessage());
            }
        }
        hotManagerChains.set(flagged.size());
        unverifiedChains.set(unverified);
        return flagged;
    }

    private boolean registrySignerIsManager(String identifier, String claimIssuer, String signer) {
        byte[] keyHash = Hash.sha3(Numeric.hexStringToByteArray(
                Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(signer), 64)));
        Function keyHasPurpose = new Function("keyHasPurpose",
                List.of(new Bytes32(keyHash), new Uint256(MANAGEMENT_PURPOSE)),
                List.of(new TypeReference<Bool>() {}));
        List<Type> result = evm.call(clients.getEvmClientByIdentifier(identifier), claimIssuer, keyHasPurpose);
        return !result.isEmpty() && Boolean.TRUE.equals(result.get(0).getValue());
    }
}
