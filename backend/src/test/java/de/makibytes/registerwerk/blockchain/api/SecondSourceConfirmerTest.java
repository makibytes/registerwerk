package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
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
        receipt.setBlockNumber("0x10");
        receipt.setTransactionIndex("0x1");
        receipt.setStatus("0x1");
        receipt.setGasUsed("0x5208");
        receipt.setCumulativeGasUsed("0x9c40");
        receipt.setFrom(SIGNER);
        receipt.setTo(CONTRACT);
        receipt.setLogs(List.of(log(0, "0xdead", "0x01")));
    }

    private static Log log(int index, String topic, String data) {
        Log l = new Log();
        l.setAddress(CONTRACT);
        l.setTopics(List.of(topic));
        l.setData(data);
        l.setLogIndex("0x" + Integer.toHexString(index));
        return l;
    }

    private static TransactionReceipt copy(TransactionReceipt r) {
        TransactionReceipt c = new TransactionReceipt();
        c.setBlockHash(r.getBlockHash());
        c.setBlockNumber(r.getBlockNumberRaw());
        c.setTransactionIndex(r.getTransactionIndexRaw());
        c.setStatus(r.getStatus());
        c.setGasUsed(r.getGasUsedRaw());
        c.setCumulativeGasUsed(r.getCumulativeGasUsedRaw());
        c.setFrom(r.getFrom());
        c.setTo(r.getTo());
        c.setLogs(r.getLogs());
        return c;
    }

    /** A node that serves the same receipt as the primary (the honest case). */
    private BlockchainClientRegistry.EvmNodeClient node(boolean healthy, Transaction answer) throws Exception {
        boolean mined = answer != null && answer.getBlockHash() != null;
        return node(healthy, answer, mined ? copy(receipt) : null);
    }

    private BlockchainClientRegistry.EvmNodeClient node(boolean healthy, Transaction answer,
            TransactionReceipt nodeReceipt) throws Exception {
        Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
        when(web3j.ethGetTransactionByHash(HASH).send().getTransaction()).thenReturn(Optional.ofNullable(answer));
        when(web3j.ethGetTransactionReceipt(HASH).send().getTransactionReceipt())
                .thenReturn(Optional.ofNullable(nodeReceipt));
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

    // ── H9(b): the second node's full receipt must agree, not just eth_getTransactionByHash ──────────

    @Test
    @DisplayName("H9(b): a second node whose receipt says FAILED holds a transaction the primary reports as SUCCESS")
    void forgedSuccessIsHeldWhenSecondNodeReceiptDisagreesOnStatus() throws Exception {
        TransactionReceipt failed = copy(receipt);
        failed.setStatus("0x0");
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), failed));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);
        assertThat(meters.counter("registerwerk.confirmation.second_source_mismatch", "chain", "ETH_MAINNET").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("H9(b): a second node with different logs (event forged or dropped) holds the transaction")
    void forgedLogsAreHeld() throws Exception {
        TransactionReceipt otherLogs = copy(receipt);
        otherLogs.setLogs(List.of(log(0, "0xdead", "0x02")));   // same count, different data
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), otherLogs));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);

        TransactionReceipt noLogs = copy(receipt);
        noLogs.setLogs(List.of());                               // the event the registry relies on is missing
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), noLogs));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);
    }

    @Test
    @DisplayName("H9(b): a second node reporting another block number or gasUsed holds the transaction")
    void forgedBlockNumberOrGasHeld() throws Exception {
        TransactionReceipt otherBlock = copy(receipt);
        otherBlock.setBlockNumber("0x11");
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), otherBlock));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);

        TransactionReceipt otherGas = copy(receipt);
        otherGas.setGasUsed("0x1");
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), otherGas));
        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_MISMATCH);
    }

    @Test
    @DisplayName("H9(b): a node that knows the transaction but has no receipt yet is lagging, not a mismatch")
    void missingReceiptIsLaggingNotMismatch() throws Exception {
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), null));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_PENDING);
        assertThat(meters.find("registerwerk.confirmation.second_source_mismatch").counter()).isNull();
    }

    @Test
    @DisplayName("H9(b): identical receipts (case and quantity encoding differences aside) still confirm")
    void equivalentReceiptEncodingsConfirm() throws Exception {
        TransactionReceipt same = copy(receipt);
        same.setStatus("0x01");
        same.setGasUsed("0x005208");
        same.setBlockNumber("0x010");
        Log upper = log(0, "0xDEAD", "0x01");
        upper.setAddress(CONTRACT.toUpperCase().replace("0X", "0x"));
        same.setLogs(List.of(upper));
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)),
                node(true, tx("0xblock", CONTRACT, SIGNER), same));

        assertThat(confirmer.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.CONFIRMED);
    }

    // ── H9(a): production mode refuses single-source confirmation ───────────────────────────────────

    private SimpleMeterRegistry prodMeters;

    /** A production-mode confirmer on its own registry (the gauge is registered once per registry). */
    private SecondSourceConfirmer production() {
        prodMeters = new SimpleMeterRegistry();
        return new SecondSourceConfirmer(registry, prodMeters, ProductionMode.of(true));
    }

    @Test
    @DisplayName("H9(a): in production a single healthy node HOLDS a registry-mutating transaction (counted once)")
    void productionSingleSourceHolds() throws Exception {
        SecondSourceConfirmer prod = production();
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)));

        assertThat(prod.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_SINGLE_SOURCE);
        assertThat(prod.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_SINGLE_SOURCE);

        assertThat(prodMeters.counter("registerwerk.confirmation.single_source_held", "chain", "ETH_MAINNET").count())
                .as("counted once per held transaction, not once per poll").isEqualTo(1.0);
        assertThat(prodMeters.find("registerwerk.confirmation.single_source").counter())
                .as("the non-production pass-through is never taken").isNull();
        assertThat(prodMeters.get("registerwerk.confirmation.second_source_held").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("H9(a): in production no healthy node at all is a hold too, and the overdue alert counter fires once")
    void productionNoNodesHoldsAndAlerts() throws Exception {
        SecondSourceConfirmer prod = production();
        org.springframework.test.util.ReflectionTestUtils.setField(prod, "holdAlertSeconds", 0L);
        nodes();

        assertThat(prod.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_SINGLE_SOURCE);
        prod.confirm("ETH_MAINNET", HASH, CONTRACT, receipt);

        assertThat(prodMeters.counter("registerwerk.confirmation.second_source_hold_overdue", "chain", "ETH_MAINNET")
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("H9(a): the hold lifts as soon as a second healthy node agrees; the gauge returns to zero")
    void productionHoldLiftsWhenSecondNodeAppears() throws Exception {
        SecondSourceConfirmer prod = production();
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)));
        assertThat(prod.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_SINGLE_SOURCE);

        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)), node(true, tx("0xblock", CONTRACT, SIGNER)));
        assertThat(prod.confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.CONFIRMED);
        assertThat(prodMeters.get("registerwerk.confirmation.second_source_held").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("H9(a): in production an unhealthy second node is still not a second source")
    void productionUnhealthySecondNodeStillHolds() throws Exception {
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)), node(false, tx("0xblock", CONTRACT, SIGNER)));

        assertThat(production().confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.HOLD_SINGLE_SOURCE);
    }

    @Test
    @DisplayName("H9(a): in production two agreeing healthy nodes confirm exactly as outside production")
    void productionTwoNodesConfirm() throws Exception {
        nodes(node(true, tx("0xblock", CONTRACT, SIGNER)), node(true, tx("0xblock", CONTRACT, SIGNER)));

        assertThat(production().confirm("ETH_MAINNET", HASH, CONTRACT, receipt))
                .isEqualTo(SecondSourceConfirmer.Verdict.CONFIRMED);
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
