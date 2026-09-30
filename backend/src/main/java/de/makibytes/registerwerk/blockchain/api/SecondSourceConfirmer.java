package de.makibytes.registerwerk.blockchain.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second-source confirmation of registry-mutating transactions (P4C-6 interim / parked T4-08).
 * One RPC node's word is not enough to complete an Eintragung: before such a transaction is completed,
 * {@code eth_getTransactionByHash} on the chain's healthy nodes must return the same block hash, the
 * expected contract ({@code to}) and the same sender as the receipt. With fewer than two healthy nodes
 * the transaction proceeds and {@code registerwerk.confirmation.single_source} counts it (plus a WARN).
 * A node that has no answer yet (lagging) is ignored; a node that answers differently holds the
 * transaction and counts {@code registerwerk.confirmation.second_source_mismatch}. A node that knows the
 * transaction only as pending (no block hash yet) is such a lagging node, not a mismatch. A transaction
 * held as HOLD_PENDING for longer than {@code registerwerk.blockchain.tx.second-source-hold-alert-seconds}
 * counts {@code registerwerk.confirmation.second_source_hold_overdue} once (alert
 * {@code RpcSecondSourceHoldOverdue}). The recipient is compared with the receipt's own {@code to}
 * (null for a contract creation, so nothing is compared), independent of the recorded contract, so contract creations ({@code to} null) and forwarders /
 * multicalls cannot produce a false mismatch.
 */
@Component
public class SecondSourceConfirmer {

    private static final Logger log = LoggerFactory.getLogger(SecondSourceConfirmer.class);

    public enum Verdict { CONFIRMED, SINGLE_SOURCE, HOLD_PENDING, HOLD_MISMATCH }

    private final BlockchainClientRegistry registry;
    private final MeterRegistry meters;
    private final Map<String, Instant> heldSince = new ConcurrentHashMap<>();
    private final java.util.Set<String> overdueReported = ConcurrentHashMap.newKeySet();

    @Value("${registerwerk.blockchain.tx.second-source-hold-alert-seconds:900}")
    private long holdAlertSeconds = 900;

    public SecondSourceConfirmer(BlockchainClientRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
    }

    public Verdict confirm(String identifier, String txHash, String expectedTo, TransactionReceipt receipt) {
        List<BlockchainClientRegistry.EvmNodeClient> healthy = registry.evmNodeClients(identifier).stream()
                .filter(BlockchainClientRegistry.EvmNodeClient::healthy).toList();
        if (healthy.size() < 2) {
            meters.counter("registerwerk.confirmation.single_source", "chain", identifier).increment();
            log.warn("tx {} on {} confirmed from a single source ({} healthy node(s))", txHash, identifier,
                    healthy.size());
            release(txHash);
            return Verdict.SINGLE_SOURCE;
        }
        // Compare against the receipt's own recipient (null for a contract creation = not compared). The
        // recorded contract is not used: a forwarder/multicall or a creation legitimately differs from it.
        String comparedTo = receipt.getTo();
        int agreeing = 0;
        for (BlockchainClientRegistry.EvmNodeClient node : healthy) {
            Optional<Transaction> answer;
            try {
                answer = node.client().ethGetTransactionByHash(txHash).send().getTransaction();
            } catch (Exception e) {
                log.debug("second-source lookup of {} on node {} failed: {}", txHash, node.nodeId(), e.getMessage());
                continue;
            }
            if (answer.isEmpty()) continue;
            Transaction t = answer.get();
            // No block hash yet: this node only knows the tx as pending (lagging) - no answer, not a conflict.
            if (t.getBlockHash() == null) continue;
            if (!same(t.getBlockHash(), receipt.getBlockHash())
                    || (comparedTo != null && !same(t.getTo(), comparedTo))
                    || (receipt.getFrom() != null && !same(t.getFrom(), receipt.getFrom()))) {
                meters.counter("registerwerk.confirmation.second_source_mismatch", "chain", identifier).increment();
                log.error("Second-source MISMATCH for tx {} on {}: node {} reports block {} to {} from {}; receipt says "
                                + "block {} from {}. Holding the transaction.", txHash, identifier, node.nodeId(),
                        t.getBlockHash(), t.getTo(), t.getFrom(), receipt.getBlockHash(), receipt.getFrom());
                return Verdict.HOLD_MISMATCH;
            }
            agreeing++;
        }
        if (agreeing >= 2) {
            release(txHash);
            return Verdict.CONFIRMED;
        }
        checkOverdue(identifier, txHash);
        return Verdict.HOLD_PENDING;
    }

    private void release(String txHash) {
        heldSince.remove(txHash);
        overdueReported.remove(txHash);
    }

    private void checkOverdue(String identifier, String txHash) {
        Instant since = heldSince.computeIfAbsent(txHash, k -> Instant.now());
        if (Duration.between(since, Instant.now()).getSeconds() >= holdAlertSeconds
                && overdueReported.add(txHash)) {
            meters.counter("registerwerk.confirmation.second_source_hold_overdue", "chain", identifier).increment();
            log.error("Second-source confirmation of tx {} on {} is still pending after {}s: no second healthy "
                    + "node has answered. Check RPC node health or add an independent node.",
                    txHash, identifier, holdAlertSeconds);
        }
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
