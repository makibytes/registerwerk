package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.VaultDealingState;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("VaultDealingReader — live on-chain forward-pricing reads (T1-07)")
class VaultDealingReaderTest {

    @Mock EvmContractService evm;
    @Mock AssetDeploymentRepository deployments;

    private VaultDealingReader reader;
    private AssetDeployment deployment;
    /** What the mocked vault answers per function name; a missing key makes the call fail. */
    private final Map<String, Type<?>> onChain = new HashMap<>();
    private BigInteger dealingPointOfRequest = BigInteger.valueOf(1_800_014_400L);

    @BeforeEach
    void setUp() {
        reader = new VaultDealingReader(evm, deployments);
        deployment = new AssetDeployment();
        deployment.setId(UUID.randomUUID());
        deployment.setAssetId(UUID.randomUUID());
        deployment.setChainConfigId(UUID.randomUUID());
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setContractAddress("0x0000000000000000000000000000000000000001");
        Web3j web3j = mock(Web3j.class);
        lenient().when(evm.evmClient(deployment.getChainConfigId())).thenReturn(web3j);
        lenient().when(evm.call(eq(web3j), eq(deployment.getContractAddress()), any(Function.class)))
                .thenAnswer(inv -> {
                    Function fn = inv.getArgument(2);
                    if (fn.getName().equals("dealingPointOf")) {
                        return List.<Type>of(new Uint256(dealingPointOfRequest));
                    }
                    Type<?> value = onChain.get(fn.getName());
                    if (value == null) throw new RuntimeException("eth_call error: execution reverted");
                    return List.<Type>of(value);
                });
        lenient().when(deployments.findByAssetId(deployment.getAssetId())).thenReturn(List.of(deployment));
    }

    private void configured() {
        onChain.put("dealingCutoffConfigured", new Bool(true));
        onChain.put("dealingCutoffSecondsOfDay", new Uint256(61_200));
        onChain.put("dealingPeriodSecs", new Uint256(86_400));
        onChain.put("nextDealingPoint", new Uint256(BigInteger.valueOf(1_800_014_400L)));
        onChain.put("navStruckAt", new Uint256(BigInteger.valueOf(1_799_971_200L)));
    }

    @Test
    @DisplayName("a configured vault is read into cut-off, period, next dealing point and last strike")
    void configuredVault_isReadCompletely() {
        configured();

        VaultDealingState state = reader.readState(deployment).orElseThrow();

        assertThat(state.applicable()).isTrue();
        assertThat(state.available()).isTrue();
        assertThat(state.configured()).isTrue();
        assertThat(state.cutoffSecondsOfDay()).isEqualTo(61_200);
        assertThat(state.periodSeconds()).isEqualTo(86_400L);
        assertThat(state.nextDealingPoint()).isEqualTo(Instant.ofEpochSecond(1_800_014_400L));
        assertThat(state.navStruckAt()).isEqualTo(Instant.ofEpochSecond(1_799_971_200L));
    }

    @Test
    @DisplayName("an unconfigured vault reports configured=false and no cut-off, but still its last NAV strike")
    void unconfiguredVault_hasNoCutoff() {
        onChain.put("dealingCutoffConfigured", new Bool(false));
        onChain.put("navStruckAt", new Uint256(BigInteger.valueOf(1_799_971_200L)));

        VaultDealingState state = reader.readState(deployment).orElseThrow();

        assertThat(state.configured()).isFalse();
        assertThat(state.cutoffSecondsOfDay()).isNull();
        assertThat(state.nextDealingPoint()).isNull();
        assertThat(state.navStruckAt()).isEqualTo(Instant.ofEpochSecond(1_799_971_200L));
    }

    @Test
    @DisplayName("navStruckAt = 0 means no NAV has ever been struck")
    void neverStruck_isNull() {
        configured();
        onChain.put("navStruckAt", new Uint256(BigInteger.ZERO));

        assertThat(reader.readState(deployment).orElseThrow().navStruckAt()).isNull();
    }

    @Test
    @DisplayName("an unreadable vault (a node error, or an older contract without the views) is unknown, never 'configured'")
    void unreadable_isEmpty() {
        assertThat(reader.readState(deployment)).isEmpty();
    }

    @Test
    @DisplayName("dealingPointOf reads the request's dealing point; a failed read is unknown")
    void dealingPointOf_readsAndFailsSoft() {
        assertThat(reader.dealingPointOf(deployment, BigInteger.TEN)).contains(BigInteger.valueOf(1_800_014_400L));

        lenient().when(evm.call(any(), any(), any(Function.class))).thenThrow(new RuntimeException("node down"));
        assertThat(reader.dealingPointOf(deployment, BigInteger.TEN)).isEmpty();
    }

    @Test
    @DisplayName("isAwaitingNavStrike: true while the last strike predates the request's dealing point")
    void awaiting_trueBeforeTheStrike() {
        configured();   // navStruckAt 1799971200 < dealing point 1800014400

        assertThat(reader.isAwaitingNavStrike(deployment.getAssetId(), deployment.getChainConfigId(), BigInteger.TEN))
                .contains(true);
    }

    @Test
    @DisplayName("isAwaitingNavStrike: false once a NAV was struck at/after the dealing point, and for legacy requests (point 0)")
    void awaiting_falseAfterTheStrikeAndForLegacyRequests() {
        configured();
        onChain.put("navStruckAt", new Uint256(BigInteger.valueOf(1_800_014_400L)));   // equality is accepted
        assertThat(reader.isAwaitingNavStrike(deployment.getAssetId(), deployment.getChainConfigId(), BigInteger.TEN))
                .contains(false);

        dealingPointOfRequest = BigInteger.ZERO;
        onChain.put("navStruckAt", new Uint256(BigInteger.ONE));
        assertThat(reader.isAwaitingNavStrike(deployment.getAssetId(), deployment.getChainConfigId(), BigInteger.TEN))
                .contains(false);
    }

    @Test
    @DisplayName("isAwaitingNavStrike: unknown when the vault cannot be found or read")
    void awaiting_unknownWhenUnreadable() {
        assertThat(reader.isAwaitingNavStrike(deployment.getAssetId(), deployment.getChainConfigId(), BigInteger.TEN))
                .isEmpty();
        assertThat(reader.isAwaitingNavStrike(UUID.randomUUID(), UUID.randomUUID(), BigInteger.TEN)).isEmpty();
    }
}
