package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthCall;

import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RepoMarketOnchainReader: pinned block and node-lag gate for priceReliable (P4B-8)")
class RepoMarketOnchainReaderConsistencyTest {

    private static final String ID = "ETHEREUM_MAINNET";
    // (uint256 factor = 2e18, bool priceReliable = true)
    private static final String HEALTH_FACTOR_RESULT = "0x"
            + "0000000000000000000000000000000000000000000000001bc16d674ec80000"
            + "0000000000000000000000000000000000000000000000000000000000000001";

    private final BlockchainClientRegistry registry = mock(BlockchainClientRegistry.class);
    private final Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
    private final RepoMarketOnchainReader reader = new RepoMarketOnchainReader(registry);

    private void stubs(int lag) throws Exception {
        when(registry.getEvmClientByIdentifier(ID)).thenReturn(web3j);
        when(registry.routedNodeLag(ID)).thenReturn(Optional.of(lag));
        EthCall call = mock(EthCall.class);
        when(call.isReverted()).thenReturn(false);
        when(call.getValue()).thenReturn(HEALTH_FACTOR_RESULT);
        when(web3j.ethCall(any(), any(DefaultBlockParameter.class)).send()).thenReturn(call);
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(1234));
    }

    @Test
    @DisplayName("a node more than one block behind makes the health factor unreliable")
    void laggingNodeUnreliable() throws Exception {
        stubs(3);
        assertThat(reader.healthFactor(ID, "0x00000000000000000000000000000000000000a1",
                "0x00000000000000000000000000000000000000b1").priceReliable()).isFalse();
    }

    @Test
    @DisplayName("a current node keeps the contract's own reliable flag")
    void currentNodeReliable() throws Exception {
        stubs(1);
        assertThat(reader.healthFactor(ID, "0x00000000000000000000000000000000000000a1",
                "0x00000000000000000000000000000000000000b1").priceReliable()).isTrue();
    }

    @Test
    @DisplayName("a pinned scope runs eth_call at the one block number read at the start")
    void pinnedBlockUsed() throws Exception {
        stubs(0);
        try (RepoMarketOnchainReader.Pin pin = reader.pinBlock(ID)) {
            reader.healthFactor(ID, "0x00000000000000000000000000000000000000a1",
                    "0x00000000000000000000000000000000000000b1");
        }
        org.mockito.ArgumentCaptor<DefaultBlockParameter> block = org.mockito.ArgumentCaptor.forClass(DefaultBlockParameter.class);
        verify(web3j, org.mockito.Mockito.atLeastOnce()).ethCall(any(), block.capture());
        assertThat(block.getAllValues()).filteredOn(java.util.Objects::nonNull).extracting(DefaultBlockParameter::getValue).contains("0x4d2");
    }
}
