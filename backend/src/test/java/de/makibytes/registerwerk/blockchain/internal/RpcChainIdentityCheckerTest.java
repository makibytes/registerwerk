package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeChainVerifier.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RpcChainIdentityChecker: chain id + genesis pin (P4C-1)")
class RpcChainIdentityCheckerTest {

    private ChainConfigRepository repo;
    private RpcChainIdentityChecker checker;
    private SimpleMeterRegistry meters;
    private ChainConfig chain;

    @BeforeEach
    void setUp() {
        repo = mock(ChainConfigRepository.class);
        meters = new SimpleMeterRegistry();
        checker = new RpcChainIdentityChecker(null, null, repo, meters, 5,
                new LocalDevChainRepinPolicy(false, false, "http://anvil:8545"),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier("ETHEREUM_MAINNET");
        chain.setChainType(ChainConfig.ChainType.EVM);
        chain.setChainId(1L);
    }

    private static Web3j node(long chainId, String genesisHash) throws Exception {
        Web3j w = mock(Web3j.class, RETURNS_DEEP_STUBS);
        var idResp = mock(org.web3j.protocol.core.methods.response.EthChainId.class);
        when(idResp.getChainId()).thenReturn(BigInteger.valueOf(chainId));
        when(w.ethChainId().sendAsync()).thenReturn(CompletableFuture.completedFuture(idResp));
        EthBlock.Block block = new EthBlock.Block();
        block.setHash(genesisHash);
        var blockResp = mock(EthBlock.class);
        when(blockResp.getBlock()).thenReturn(block);
        when(w.ethGetBlockByNumber(DefaultBlockParameterName.EARLIEST, false).sendAsync())
                .thenReturn(CompletableFuture.completedFuture(blockResp));
        return w;
    }

    @Test
    @DisplayName("a node answering chain id 137 on a chain pinned to 1 is a MISMATCH")
    void foreignChainIdMismatch() throws Exception {
        assertThat(checker.checkEvm(chain, node(137, "0xabc")).outcome()).isEqualTo(Outcome.MISMATCH);
        assertThat(meters.counter("registerwerk.rpc.chain_mismatch", "chain", "ETHEREUM_MAINNET").count()).isEqualTo(1.0);
        verify(repo, never()).pinGenesisHashIfAbsent(any(), anyString());
    }

    @Test
    @DisplayName("the first matching node pins the genesis hash; a later node with another genesis is a MISMATCH")
    void genesisPinnedThenEnforced() throws Exception {
        when(repo.pinGenesisHashIfAbsent(chain.getId(), "0xgenesis")).thenReturn(1);
        assertThat(checker.checkEvm(chain, node(1, "0xGENESIS")).outcome()).isEqualTo(Outcome.MATCH);
        assertThat(chain.getGenesisHash()).isEqualTo("0xgenesis");

        assertThat(checker.checkEvm(chain, node(1, "0xgenesis")).outcome()).isEqualTo(Outcome.MATCH);
        assertThat(checker.checkEvm(chain, node(1, "0xforkedgenesis")).outcome()).isEqualTo(Outcome.MISMATCH);
    }

    @Test
    @DisplayName("an unpinned chain id cannot be verified (and never captures a genesis hash)")
    void unpinnedChainIdUnverifiable() throws Exception {
        chain.setChainId(null);
        assertThat(checker.checkEvm(chain, node(1, "0xg")).outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        verify(repo, never()).pinGenesisHashIfAbsent(any(), anyString());
    }
}
