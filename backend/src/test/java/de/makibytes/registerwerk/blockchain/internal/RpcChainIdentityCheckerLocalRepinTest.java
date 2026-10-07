package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeChainVerifier.Outcome;
import de.makibytes.registerwerk.chain.events.RpcNodeChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
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

/**
 * Wave 1 docker gate: recreating the local anvil container changes its genesis hash while the demo
 * DB still holds the old pin, so every node of the demo chain was quarantined forever. On a local dev
 * chain with demo seeding on (never production) the pin is re-captured, audited and WARNed; anywhere
 * else the quarantine stays and the operator uses the step-up + 4-eyes genesis-pin reset.
 */
@DisplayName("RpcChainIdentityChecker: local dev chain re-pin")
class RpcChainIdentityCheckerLocalRepinTest {

    private ChainConfigRepository repo;
    private ApplicationEventPublisher events;
    private SimpleMeterRegistry meters;
    private ChainConfig chain;

    @BeforeEach
    void setUp() {
        repo = mock(ChainConfigRepository.class);
        events = mock(ApplicationEventPublisher.class);
        meters = new SimpleMeterRegistry();
        chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier("ETHEREUM_SEPOLIA");
        chain.setChainType(ChainConfig.ChainType.EVM);
        chain.setChainId(11155111L);
        chain.setRpcUrl("http://anvil:8545");
        chain.setGenesisHash("0xoldanvilgenesis");
    }

    private RpcChainIdentityChecker checker(boolean repinAllowed) {
        return new RpcChainIdentityChecker(null, null, repo, meters, 5,
                new LocalDevChainRepinPolicy(repinAllowed, false, "http://anvil:8545"), events,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
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
    @DisplayName("a recreated local dev chain (demo seeding on) is re-pinned, audited, and not quarantined")
    void recreatedLocalChainIsRepinned() throws Exception {
        when(repo.repinGenesisHash(chain.getId(), "0xoldanvilgenesis", "0xnewanvilgenesis")).thenReturn(1);

        assertThat(checker(true).checkEvm(chain, node(11155111, "0xNEWANVILGENESIS")).outcome())
                .isEqualTo(Outcome.MATCH);

        assertThat(chain.getGenesisHash()).isEqualTo("0xnewanvilgenesis");
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        RpcNodeChangedEvent event = (RpcNodeChangedEvent) published.getValue();
        assertThat(event.operation()).isEqualTo("GENESIS_PIN_AUTO_REPIN");
        assertThat(event.oldUrl()).isEqualTo("0xoldanvilgenesis");
        assertThat(event.url()).isEqualTo("0xnewanvilgenesis");
        assertThat(meters.find("registerwerk.rpc.chain_mismatch").counter()).isNull();
    }

    @Test
    @DisplayName("everywhere else a genesis mismatch stays a MISMATCH and never touches the pin")
    void otherChainsKeepQuarantine() throws Exception {
        assertThat(checker(false).checkEvm(chain, node(11155111, "0xnewanvilgenesis")).outcome())
                .isEqualTo(Outcome.MISMATCH);

        verify(repo, never()).repinGenesisHash(any(), anyString(), anyString());
        verify(events, never()).publishEvent(any(Object.class));
        assertThat(chain.getGenesisHash()).isEqualTo("0xoldanvilgenesis");
    }
}
