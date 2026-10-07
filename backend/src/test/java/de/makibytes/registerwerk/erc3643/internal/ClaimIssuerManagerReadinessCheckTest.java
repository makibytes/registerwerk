package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision (Wave 1b): the chain's registry signer stays the ClaimIssuer manager, because the backend
 * revokes claims with {@code revokeClaimBySignature} (an {@code onlyManager} call). A hot key that
 * can both sign claims and manage the issuer is a real risk, so production mode reports it loudly
 * (gauge + error-level log) without refusing to boot.
 */
@DisplayName("ClaimIssuerManagerReadinessCheck")
class ClaimIssuerManagerReadinessCheckTest {

    private static final String CLAIM_ISSUER = "0x00000000000000000000000000000000000000c1";
    private static final String SIGNER = "0x00000000000000000000000000000000000000e0";

    private final ChainConfigRepository chainConfigs = mock(ChainConfigRepository.class);
    private final BlockchainClientRegistry clients = mock(BlockchainClientRegistry.class);
    private final EvmContractService evm = mock(EvmContractService.class);
    private final Web3j web3j = mock(Web3j.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ContractAddressConfig addresses = new ContractAddressConfig();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        addresses.setClaimIssuer(Map.of("ethereum-testnet", CLAIM_ISSUER, "polygon-testnet", ""));
        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(chainConfigId);
        when(chainConfigs.findByIdentifier("ETHEREUM_TESTNET")).thenReturn(Optional.of(chain));
        when(clients.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        EvmSigner signer = mock(EvmSigner.class);
        when(signer.address()).thenReturn(SIGNER);
        when(evm.signer(chainConfigId)).thenReturn(signer);
    }

    private ClaimIssuerManagerReadinessCheck check(boolean productionMode) {
        MockEnvironment env = new MockEnvironment();
        if (productionMode) {
            env.setProperty("registerwerk.production-mode", "true");
        }
        return new ClaimIssuerManagerReadinessCheck(env, addresses, chainConfigs, clients, evm, meters);
    }

    private void managementKeyAnswer(boolean registrySignerIsManager) {
        when(evm.call(eq(web3j), eq(CLAIM_ISSUER), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            assertThat(fn.getName()).isEqualTo("keyHasPurpose");
            // purpose 1 = MANAGEMENT (ERC-734)
            assertThat(((org.web3j.abi.datatypes.generated.Uint256) fn.getInputParameters().get(1)).getValue())
                    .isEqualTo(BigInteger.ONE);
            return List.of(new Bool(registrySignerIsManager));
        });
    }

    @Test
    @DisplayName("production mode + the registry signer holds a MANAGEMENT key on the ClaimIssuer -> flagged, gauge 1")
    void productionWarnsWhenTheHotRegistrySignerIsTheManager() {
        managementKeyAnswer(true);
        ClaimIssuerManagerReadinessCheck check = check(true);

        List<String> flagged = check.evaluate();

        assertThat(flagged).containsExactly("ETHEREUM_TESTNET");
        assertThat(meters.get("registerwerk_claim_issuer_hot_manager").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a separate cold manager (the signer holds only a CLAIM key) -> not flagged, gauge 0")
    void coldManagerIsNotFlagged() {
        managementKeyAnswer(false);
        ClaimIssuerManagerReadinessCheck check = check(true);

        assertThat(check.evaluate()).isEmpty();
        assertThat(meters.get("registerwerk_claim_issuer_hot_manager").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("outside production mode nothing is read from the chain (demo stacks use the signer as manager)")
    void notProductionDoesNothing() {
        ClaimIssuerManagerReadinessCheck check = check(false);

        assertThat(check.evaluate()).isEmpty();
        verify(evm, never()).call(any(Web3j.class), anyString(), any(Function.class));
    }

    @Test
    @DisplayName("an unreachable chain is a warning, never a boot failure, and is not reported as safe")
    void unreadableChainNeverFailsTheBoot() {
        when(evm.call(eq(web3j), eq(CLAIM_ISSUER), any(Function.class)))
                .thenThrow(new RuntimeException("rpc down"));
        ClaimIssuerManagerReadinessCheck check = check(true);

        assertThat(check.evaluate()).isEmpty();
        assertThat(meters.get("registerwerk_claim_issuer_manager_unverified").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a configured ClaimIssuer without a chain_config row is unverifiable, not an exception")
    void missingChainConfigIsUnverified() {
        when(chainConfigs.findByIdentifier("ETHEREUM_TESTNET")).thenReturn(Optional.empty());
        ClaimIssuerManagerReadinessCheck check = check(true);

        assertThat(check.evaluate()).isEmpty();
        assertThat(meters.get("registerwerk_claim_issuer_manager_unverified").gauge().value()).isEqualTo(1.0);
    }
}
