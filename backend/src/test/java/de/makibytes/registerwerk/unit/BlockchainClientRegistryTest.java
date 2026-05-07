package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry;
import de.makibytes.registerwerk.application.blockchain.ChainDescriptor;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.p2p.solanaj.rpc.RpcClient;
import org.web3j.protocol.Web3j;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockchainClientRegistry unit tests")
class BlockchainClientRegistryTest {

    @Mock
    private Web3j ethereumMainnetClient;

    @Mock
    private Web3j polygonTestnetClient;

    @Mock
    private RpcClient solanaTestnetClient;

    private BlockchainClientRegistry registry;

    private static final ChainDescriptor ETHEREUM_MAINNET =
        new ChainDescriptor(Chain.ETHEREUM, Network.MAINNET);
    private static final ChainDescriptor POLYGON_TESTNET =
        new ChainDescriptor(Chain.POLYGON, Network.TESTNET);
    private static final ChainDescriptor SOLANA_TESTNET =
        new ChainDescriptor(Chain.SOLANA, Network.TESTNET);
    private static final ChainDescriptor UNKNOWN_DESCRIPTOR =
        new ChainDescriptor(Chain.CANTON, Network.MAINNET);

    @BeforeEach
    void setUp() {
        Map<ChainDescriptor, Web3j> evmClients = new HashMap<>();
        evmClients.put(ETHEREUM_MAINNET, ethereumMainnetClient);
        evmClients.put(POLYGON_TESTNET, polygonTestnetClient);

        Map<ChainDescriptor, RpcClient> solanaClients = new HashMap<>();
        solanaClients.put(SOLANA_TESTNET, solanaTestnetClient);

        registry = new BlockchainClientRegistry(evmClients, solanaClients);
    }

    // ── getEvmClient ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getEvmClient should return the correct Web3j client for ETHEREUM MAINNET")
    void getEvmClient_shouldReturnCorrectClientForEthereum() {
        Web3j client = registry.getEvmClient(ETHEREUM_MAINNET);

        assertThat(client).isSameAs(ethereumMainnetClient);
    }

    @Test
    @DisplayName("getEvmClient should throw IllegalArgumentException for an unknown chain descriptor")
    void getEvmClient_shouldThrowForUnknownDescriptor() {
        assertThatThrownBy(() -> registry.getEvmClient(UNKNOWN_DESCRIPTOR))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CANTON/MAINNET");
    }

    // ── getSolanaClient ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSolanaClient should return the correct RpcClient for SOLANA TESTNET")
    void getSolanaClient_shouldReturnCorrectClientForSolanaTestnet() {
        RpcClient client = registry.getSolanaClient(SOLANA_TESTNET);

        assertThat(client).isSameAs(solanaTestnetClient);
    }
}
