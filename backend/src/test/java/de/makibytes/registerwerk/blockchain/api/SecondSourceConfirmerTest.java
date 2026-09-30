package de.makibytes.registerwerk.blockchain.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SecondSourceConfirmer (P4C-6)")
class SecondSourceConfirmerTest {

    private static final String HASH = "0xabc";
    private static final String CONTRACT = "0x00000000000000000000000000000000000000c1";
    private static final String SIGNER = "0x00000000000000000000000000000000000000a1";

    private BlockchainClientRegistry registry;
    private SecondSourceConfirmer confirmer;
    private SimpleMeterRegistry meters;
    private TransactionReceipt receipt;

    @BeforeEach
    void setUp() {
        registry = mock(BlockchainClientRegistry.class);
        meters = new SimpleMeterRegistry();
        confirmer = new SecondSourceConfirmer(registry, meters);
        receipt = new TransactionReceipt();
        receipt.setBlockHash("0xblock");
        receipt.setFrom(SIGNER);
        receipt.setTo(CONTRACT);
    }

    private BlockchainClientRegistry.EvmNodeClient node(boolean healthy, Transaction answer) throws Exception {
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        when(web3j.ethGetTransactionByHash(HASH).send().getTransaction()).thenReturn(Optional.ofNullable(answer));
        return new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), web3j, healthy);
    }

    private void nodes(BlockchainClientRegistry.EvmNodeClient... nodes) {
        when(registry.evmNodeClients("ETH_MAINNET")).thenReturn(List.of(nodes));
    }

    private static Transaction tx(String blockHash, String to, String from) {
        Transaction t = new Transaction();
        t.setBlockHash(blockHash);
        t.setTo(to);
        t.setFrom(from);
        return t;
    }

    @Test
    @DisplayName("a single healthy node proceeds and is counted as single-source")
    void singleSource() throws Exception {
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.SINGLE_SOURCE);
        assertThat(meters.counter("registerwerk.confirmation.single_source", "chain", "ETH_MAINNET").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("two nodes agreeing on block hash, contract and sender confirm")
    void twoAgree() throws Exception {
        nodes(
                node(true, tx("0xBLOCK", CONTRACT, SIGNER)), node(true, tx("0xblock", CONTRACT.toUpperCase().replace("0X", "0x"), SIGNER)));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.CONFIRMED);
    }

    @Test
    @DisplayName("a node reporting another block hash holds the transaction")
    void disagreementHolds() throws Exception {
        nodes(
                node(true, tx("0xblock", CONTRACT, SIGNER)), node(true, tx("0xother", CONTRACT, SIGNER)));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);
        assertThat(meters.counter("registerwerk.confirmation.second_source_mismatch", "chain", "ETH_MAINNET").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a different recipient contract holds the transaction")
    void wrongContractHolds() throws Exception {
        nodes(
                node(true, tx("0xblock", "0x00000000000000000000000000000000000000ff", SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER)));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);
    }

    @Test
    @DisplayName("a lagging node without an answer leaves the transaction pending")
    void laggingNodePending() throws Exception {
        nodes(
                node(true, tx("0xblock", CONTRACT, SIGNER)), node(true, null));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_PENDING);
    }

    @Test
    @DisplayName("unhealthy nodes are not second sources")
    void unhealthyIgnored() throws Exception {
        nodes(
                node(true, tx("0xblock", CONTRACT, SIGNER)), node(false, tx("0xevil", CONTRACT, SIGNER)));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.SINGLE_SOURCE);
    }

    @Test
    @DisplayName("a lagging node that knows the tx only as pending (null block hash) is no mismatch")
    void pendingAnswerIsNotMismatch() throws Exception {
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)), node(true, tx(null, null, SIGNER)));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_PENDING);
        assertThat(meters.find("registerwerk.confirmation.second_source_mismatch").counter()).isNull();
    }

    @Test
    @DisplayName("contract creation (to null) and forwarders do not false-mismatch")
    void creationAndForwarder() throws Exception {
        receipt.setTo(null);
        // contract creation: receipt.to null, tx.to null on both nodes, recorded expected address is the new contract
        nodes(node(true, tx("0xblock", null, SIGNER)), node(true, tx("0xblock", null, SIGNER)));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.CONFIRMED);

        // forwarder: both nodes report the forwarder as tx.to, recorded contract differs
        String forwarder = "0x00000000000000000000000000000000000000f0";
        receipt.setTo(forwarder);
        nodes(node(true, tx("0xblock", forwarder, SIGNER)), node(true, tx("0xblock", forwarder, SIGNER)));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.CONFIRMED);

        // a node reporting a DIFFERENT recipient than the receipt is still a mismatch
        nodes(node(true, tx("0xblock", forwarder, SIGNER)), node(true, tx("0xblock", CONTRACT, SIGNER)));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);
    }

    @Test
    @DisplayName("a hold that outlives the alert deadline counts the overdue metric exactly once")
    void overdueHoldCountedOnce() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(confirmer, "holdAlertSeconds", 0L);
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)), node(true, null));

        confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt);
        confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt);

        assertThat(meters.counter("registerwerk.confirmation.second_source_hold_overdue", "chain", "ETH_MAINNET").count())
                .isEqualTo(1.0);
    }
}
