package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.ConfidentialForcedOpOutcomeEvent;
import de.makibytes.registerwerk.blockchain.events.ConfidentialForcedOpOutcomeEvent.Outcome;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies what a confirmed confidential forced operation actually moved (review finding T1-16).
 *
 * <p>{@code ConfidentialERC3643.forcedTransfer} and {@code confidentialBurn} compute
 * {@code select(le(amount, balance), amount, 0)}: if the holder's encrypted balance is below the
 * ordered amount the contract moves 0 and the transaction still succeeds. A SUCCESS row in
 * {@code blockchain_transaction} is therefore not evidence that the court-ordered correction was
 * executed. This verifier reads the {@code ConfidentialTransfer(from,to,handle)} /
 * {@code ConfidentialBurn(from,handle)} log from the receipt, decrypts the moved-amount handle via
 * the registry's already-provisioned operator-viewer ACL ({@code _grantHandleAcl} grants every
 * viewer on that handle; {@link ZamaRelayerClient#requestOperatorDecrypt}), compares it with the
 * ordered amount recorded in the tx params and records the verdict on the tx row plus a
 * {@link ConfidentialForcedOpOutcomeEvent} audit entry.
 *
 * <p>No on-chain Gateway decryption: an async callback state machine with KMS expiry in the
 * correction path was vetoed in favour of this off-chain check, which needs no contract change.
 *
 * <p>Decrypt/receipt failures are retried with exponential backoff ({@link #BASE_BACKOFF} doubled
 * per attempt) up to {@link #MAX_ATTEMPTS}; after that the row is closed as
 * {@link Outcome#UNVERIFIED_DECRYPT_FAILED}, never silently as executed. The decrypted amount is
 * never logged — it goes only into the audit payload.
 */
@Component
class ConfidentialForcedOpVerifier {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialForcedOpVerifier.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    static final String FORCED_TRANSFER = "confidentialForcedTransfer";
    static final String FORCE_BURN = "confidentialForceBurn";
    static final Set<String> METHODS = Set.of(FORCED_TRANSFER, FORCE_BURN);

    /** euint64 is {@code type euint64 is uint256} in the pinned fhEVM lib, so the ABI type is uint256. */
    static final String TRANSFER_TOPIC = EventEncoder.encode(new Event("ConfidentialTransfer", List.of(
            new TypeReference<Address>(true) {}, new TypeReference<Address>(true) {},
            new TypeReference<Uint256>() {})));
    static final String BURN_TOPIC = EventEncoder.encode(new Event("ConfidentialBurn", List.of(
            new TypeReference<Address>(true) {}, new TypeReference<Uint256>() {})));

    static final int MAX_ATTEMPTS = 8;
    static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private final BlockchainTransactionRepository txRepository;
    private final EvmContractService evmContractService;
    private final ZamaRelayerClient zamaRelayerClient;
    private final ApplicationEventPublisher eventPublisher;
    private final IsolatedTransactionExecutor isolatedTransactions;

    ConfidentialForcedOpVerifier(
            BlockchainTransactionRepository txRepository,
            EvmContractService evmContractService,
            ZamaRelayerClient zamaRelayerClient,
            ApplicationEventPublisher eventPublisher,
            IsolatedTransactionExecutor isolatedTransactions) {
        this.txRepository = txRepository;
        this.evmContractService = evmContractService;
        this.zamaRelayerClient = zamaRelayerClient;
        this.eventPublisher = eventPublisher;
        this.isolatedTransactions = isolatedTransactions;
    }

    @SchedulerLock(name = "confidentialForcedOpVerifier", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 50_000)
    public void verifyPending() {
        Instant now = Instant.now();
        for (BlockchainTransaction tx : txRepository.findByMethodNameInAndStatusAndExecutionOutcomeIsNull(
                METHODS, BlockchainTransaction.Status.SUCCESS)) {
            if (!isDue(tx, now)) {
                continue;
            }
            try {
                isolatedTransactions.run(() -> verify(tx.getId()));
            } catch (Exception e) {
                log.warn("Confidential forced-op verification failed for tx={}: {}", tx.getTxHash(), e.getMessage());
            }
        }
    }

    static boolean isDue(BlockchainTransaction tx, Instant now) {
        if (tx.getExecutionOutcomeCheckedAt() == null || tx.getExecutionOutcomeAttempts() == 0) {
            return true;
        }
        long factor = 1L << Math.min(tx.getExecutionOutcomeAttempts() - 1, 16);
        return !tx.getExecutionOutcomeCheckedAt().plus(BASE_BACKOFF.multipliedBy(factor)).isAfter(now);
    }

    void verify(UUID txId) {
        BlockchainTransaction tx = txRepository.findById(txId).orElse(null);
        if (tx == null || tx.getExecutionOutcome() != null
                || tx.getStatus() != BlockchainTransaction.Status.SUCCESS) {
            return;
        }
        Map<String, Object> params = tx.getParams() != null ? tx.getParams() : Map.of();
        String ordered = stringParam(params, "amount");
        String from = stringParam(params, "from");
        String to = stringParam(params, "to");
        boolean transfer = FORCED_TRANSFER.equals(tx.getMethodName());
        BigInteger orderedAmount = parseAmount(ordered);
        if (orderedAmount == null || from == null || (transfer && to == null)) {
            close(tx, Outcome.UNVERIFIED_INCONSISTENT, ordered, null,
                    "Transaction params lack the ordered amount/from/to needed for verification");
            return;
        }

        Optional<BigInteger> handle;
        try {
            handle = movedAmountHandle(tx, transfer, from, to);
        } catch (Exception e) {
            recordFailedAttempt(tx, ordered, "Receipt unavailable: " + e.getMessage());
            return;
        }
        if (handle.isEmpty()) {
            close(tx, Outcome.UNVERIFIED_INCONSISTENT, ordered, null,
                    "Receipt carries no " + (transfer ? "ConfidentialTransfer" : "ConfidentialBurn")
                            + " log from the token contract for this holder");
            return;
        }

        BigInteger moved;
        try {
            if (!zamaRelayerClient.isConfigured()) {
                throw new IllegalStateException("Zama relayer sidecar is not configured");
            }
            moved = zamaRelayerClient.requestOperatorDecrypt(
                    EvmUtils.uint256ToBytes32Hex(handle.get()), tx.getContractAddress());
            if (moved == null) {
                throw new IllegalStateException("Relayer returned no plaintext");
            }
        } catch (Exception e) {
            recordFailedAttempt(tx, ordered, "Operator decrypt failed: " + e.getMessage());
            return;
        }

        if (moved.equals(orderedAmount)) {
            close(tx, Outcome.EXECUTED, ordered, moved.toString(), null);
        } else if (moved.signum() == 0) {
            close(tx, Outcome.NOT_EXECUTED_INSUFFICIENT_BALANCE, ordered, moved.toString(),
                    "Holder balance was below the ordered amount; the contract moved 0");
        } else {
            close(tx, Outcome.UNVERIFIED_INCONSISTENT, ordered, moved.toString(),
                    "Decrypted moved amount is neither 0 nor the ordered amount");
        }
    }

    private Optional<BigInteger> movedAmountHandle(BlockchainTransaction tx, boolean transfer,
                                                   String from, String to) throws Exception {
        if (tx.getChainConfigId() == null) {
            throw new IllegalStateException("tx has no chainConfigId");
        }
        TransactionReceipt receipt = evmContractService.evmClient(tx.getChainConfigId())
                .ethGetTransactionReceipt(tx.getTxHash()).send().getTransactionReceipt()
                .orElseThrow(() -> new IllegalStateException("receipt for " + tx.getTxHash() + " not returned"));
        String topic0 = transfer ? TRANSFER_TOPIC : BURN_TOPIC;
        for (Log l : receipt.getLogs()) {
            List<String> topics = l.getTopics();
            if (topics == null || topics.isEmpty() || !topic0.equalsIgnoreCase(topics.get(0))
                    || tx.getContractAddress() == null || !tx.getContractAddress().equalsIgnoreCase(l.getAddress())) {
                continue;
            }
            if (topics.size() < (transfer ? 3 : 2) || !sameAddress(topics.get(1), from)
                    || (transfer && !sameAddress(topics.get(2), to))) {
                continue;
            }
            if (l.getData() == null || Numeric.cleanHexPrefix(l.getData()).length() < 64) {
                continue;
            }
            return Optional.of(Numeric.toBigInt(Numeric.cleanHexPrefix(l.getData()).substring(0, 64)));
        }
        return Optional.empty();
    }

    private void recordFailedAttempt(BlockchainTransaction tx, String ordered, String reason) {
        int attempts = tx.getExecutionOutcomeAttempts() + 1;
        tx.setExecutionOutcomeAttempts(attempts);
        tx.setExecutionOutcomeCheckedAt(Instant.now());
        if (attempts >= MAX_ATTEMPTS) {
            close(tx, Outcome.UNVERIFIED_DECRYPT_FAILED, ordered, null,
                    reason + " (gave up after " + attempts + " attempts)");
            return;
        }
        log.warn("Confidential forced-op verification attempt {}/{} failed for tx={}: {}",
                attempts, MAX_ATTEMPTS, tx.getTxHash(), reason);
        txRepository.save(tx);
    }

    private void close(BlockchainTransaction tx, Outcome outcome, String ordered, String decrypted, String reason) {
        tx.setExecutionOutcome(outcome.name());
        tx.setExecutionOutcomeCheckedAt(Instant.now());
        txRepository.save(tx);
        // Deliberately no amount in the log line — the decrypted value lives only in the audit payload.
        if (outcome == Outcome.EXECUTED) {
            log.info("Confidential forced op tx={} method={} verified EXECUTED", tx.getTxHash(), tx.getMethodName());
        } else {
            log.warn("Confidential forced op tx={} method={} verified {}: {}",
                    tx.getTxHash(), tx.getMethodName(), outcome, reason);
        }
        eventPublisher.publishEvent(new ConfidentialForcedOpOutcomeEvent(
                tx.getAssetId(), tx.getDeploymentId(), tx.getId(), tx.getTxHash(), tx.getMethodName(),
                outcome, ordered, decrypted, reason, SYSTEM_ACTOR, "SYSTEM"));
    }

    private static boolean sameAddress(String topic, String address) {
        String t = Numeric.cleanHexPrefix(topic).toLowerCase(Locale.ROOT);
        String a = Numeric.cleanHexPrefix(address).toLowerCase(Locale.ROOT);
        return t.length() >= 40 && a.length() == 40 && t.endsWith(a);
    }

    private static BigInteger parseAmount(String value) {
        try {
            return value != null ? new BigInteger(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : null;
    }
}
