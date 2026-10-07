package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second-source confirmation of registry-mutating transactions (P4C-6 interim / parked T4-08).
 * One RPC node's word is not enough to complete an Eintragung: before such a transaction is completed,
 * every healthy node of the chain must return, for the transaction hash, the same transaction
 * ({@code eth_getTransactionByHash}: block hash, expected contract {@code to}, sender) <em>and</em> the same
 * receipt ({@code eth_getTransactionReceipt}: status, block number and hash, transaction index, gasUsed,
 * cumulativeGasUsed, contract address and the full log list by count and digest) as the receipt being
 * completed (H9). Comparing only the transaction would let a forged "success" through, because a hostile
 * node controls the receipt it serves but cannot make an honest node agree with it.
 *
 * <p><strong>Fewer than two healthy nodes.</strong> In production mode ({@link ProductionMode}) the transaction is
 * <em>held</em> (verdict {@link Verdict#HOLD_SINGLE_SOURCE}, it stays PENDING and is re-checked every poll):
 * {@code registerwerk.confirmation.single_source_held} counts it once and, after
 * {@code registerwerk.blockchain.tx.second-source-hold-alert-seconds}, {@code ...second_source_hold_overdue}
 * counts it (alert {@code RpcSecondSourceHoldOverdue}); the gauge {@code registerwerk.confirmation.second_source_held}
 * shows how many transactions wait. Outside production the transaction proceeds and
 * {@code registerwerk.confirmation.single_source} counts it (plus a WARN) - the demo and development behaviour.
 *
 * <p>A node that has no answer yet (lagging: no transaction, no receipt, or only a pending transaction) is
 * ignored; a node that answers differently holds the transaction and counts
 * {@code registerwerk.confirmation.second_source_mismatch}. A transaction held as HOLD_PENDING for longer than
 * the alert seconds counts {@code ...second_source_hold_overdue} once. The recipient is compared with the
 * receipt's own {@code to} (null for a contract creation, so nothing is compared), independent of the recorded
 * contract, so contract creations ({@code to} null) and forwarders / multicalls cannot produce a false mismatch.
 */
@Component
public class SecondSourceConfirmer {

    private static final Logger log = LoggerFactory.getLogger(SecondSourceConfirmer.class);

    /**
     * CONFIRMED / SINGLE_SOURCE (non-production only) let the transaction complete; the three HOLD_* verdicts
     * keep it PENDING: HOLD_PENDING = a second node has not answered yet, HOLD_MISMATCH = a node answered
     * differently, HOLD_SINGLE_SOURCE = production mode with fewer than two healthy nodes.
     */
    public enum Verdict { CONFIRMED, SINGLE_SOURCE, HOLD_PENDING, HOLD_MISMATCH, HOLD_SINGLE_SOURCE }

    private final BlockchainClientRegistry registry;
    private final MeterRegistry meters;
    private final ProductionMode productionMode;
    private final Map<String, Instant> heldSince = new ConcurrentHashMap<>();
    private final java.util.Set<String> overdueReported = ConcurrentHashMap.newKeySet();

    @Value("${registerwerk.blockchain.tx.second-source-hold-alert-seconds:900}")
    private long holdAlertSeconds = 900;

    @Autowired
    public SecondSourceConfirmer(BlockchainClientRegistry registry, MeterRegistry meters, Environment environment) {
        this(registry, meters, ProductionMode.of(environment));
    }

    /** Non-production behaviour (unit tests, tooling). */
    public SecondSourceConfirmer(BlockchainClientRegistry registry, MeterRegistry meters) {
        this(registry, meters, ProductionMode.of(false));
    }

    public SecondSourceConfirmer(BlockchainClientRegistry registry, MeterRegistry meters,
                                 ProductionMode productionMode) {
        this.registry = registry;
        this.meters = meters;
        this.productionMode = productionMode;
        if (meters != null) {
            meters.gauge("registerwerk.confirmation.second_source_held", heldSince, Map::size);
        }
    }

    public Verdict confirm(String identifier, String txHash, String expectedTo, TransactionReceipt receipt) {
        List<BlockchainClientRegistry.EvmNodeClient> healthy = registry.evmNodeClients(identifier).stream()
                .filter(BlockchainClientRegistry.EvmNodeClient::healthy).toList();
        if (healthy.size() < 2) {
            if (productionMode.enabled()) {
                // Production: one node's word is not enough for a registry-mutating transaction (H9). Hold it
                // (it stays PENDING and is re-checked on every poll) until a second healthy node can answer.
                if (heldSince.putIfAbsent(txHash, Instant.now()) == null) {
                    meters.counter("registerwerk.confirmation.single_source_held", "chain", identifier).increment();
                    log.error("tx {} on {} is held: only {} healthy node(s), production mode requires a second "
                            + "independent source for registry-mutating transactions. Repair or add an RPC node.",
                            txHash, identifier, healthy.size());
                }
                checkOverdue(identifier, txHash);
                return Verdict.HOLD_SINGLE_SOURCE;
            }
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
            // H9(b): the node must also serve the same receipt (status, logs, gas, block), not just know the tx.
            Optional<TransactionReceipt> theirs;
            try {
                theirs = node.client().ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            } catch (Exception e) {
                log.debug("second-source receipt lookup of {} on node {} failed: {}", txHash, node.nodeId(),
                        e.getMessage());
                continue;
            }
            if (theirs.isEmpty()) continue;   // knows the tx but has not produced the receipt yet: lagging
            String difference = receiptDifference(receipt, theirs.get());
            if (difference != null) {
                meters.counter("registerwerk.confirmation.second_source_mismatch", "chain", identifier).increment();
                log.error("Second-source RECEIPT MISMATCH for tx {} on {}: node {} differs in {}. Holding the "
                        + "transaction.", txHash, identifier, node.nodeId(), difference);
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

    /**
     * @return a description of the first receipt field that differs between the receipt being completed and
     *         another node's receipt for the same transaction, or {@code null} when they agree. All of these
     *         are determined by consensus: two honest nodes on the same canonical block cannot disagree.
     */
    static String receiptDifference(TransactionReceipt mine, TransactionReceipt theirs) {
        if (!sameQuantity(mine.getStatus(), theirs.getStatus())) {
            return "status (" + mine.getStatus() + " vs " + theirs.getStatus() + ")";
        }
        if (!sameText(mine.getBlockHash(), theirs.getBlockHash())) return "blockHash";
        if (!sameQuantity(mine.getBlockNumberRaw(), theirs.getBlockNumberRaw())) return "blockNumber";
        if (!sameQuantity(mine.getTransactionIndexRaw(), theirs.getTransactionIndexRaw())) return "transactionIndex";
        if (!sameQuantity(mine.getGasUsedRaw(), theirs.getGasUsedRaw())) return "gasUsed";
        if (!sameQuantity(mine.getCumulativeGasUsedRaw(), theirs.getCumulativeGasUsedRaw())) return "cumulativeGasUsed";
        if (!sameText(mine.getContractAddress(), theirs.getContractAddress())) return "contractAddress";
        int mineLogs = mine.getLogs() == null ? 0 : mine.getLogs().size();
        int theirLogs = theirs.getLogs() == null ? 0 : theirs.getLogs().size();
        if (mineLogs != theirLogs) return "log count (" + mineLogs + " vs " + theirLogs + ")";
        if (!logsDigest(mine.getLogs()).equals(logsDigest(theirs.getLogs()))) return "logs digest";
        return null;
    }

    /** keccak-256 over the logs in order: emitter, topics and data of each (indices are derived, not hashed). */
    static String logsDigest(List<Log> logs) {
        StringBuilder canonical = new StringBuilder();
        if (logs != null) {
            for (Log log : logs) {
                canonical.append(lower(log.getAddress())).append('|');
                if (log.getTopics() != null) {
                    for (String topic : log.getTopics()) canonical.append(lower(topic)).append(',');
                }
                canonical.append('|').append(lower(log.getData())).append(';');
            }
        }
        return Numeric.toHexString(Hash.sha3(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean sameText(String a, String b) {
        return Objects.equals(a == null ? null : a.toLowerCase(Locale.ROOT), b == null ? null : b.toLowerCase(Locale.ROOT));
    }

    /** Hex quantities compare by value ("0x1" == "0x01"); an unparseable value never equals a parseable one. */
    private static boolean sameQuantity(String a, String b) {
        return Objects.equals(quantity(a), quantity(b));
    }

    private static BigInteger quantity(String hex) {
        if (hex == null || hex.isBlank()) return null;
        try {
            String digits = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
            return digits.isEmpty() ? null : new BigInteger(digits, 16);
        } catch (NumberFormatException e) {
            return BigInteger.valueOf(-1);   // garbage from a node: differs from any real value
        }
    }
}
