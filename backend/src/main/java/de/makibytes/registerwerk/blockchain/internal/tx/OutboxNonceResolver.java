package de.makibytes.registerwerk.blockchain.internal.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Decides what became of an unmined transaction that has a durable-outbox row (P4B-4 / P4B-5): was it
 * mined under a re-priced replacement hash (same call, so the business intent executed), was its nonce
 * consumed by a cancel or by an outside transaction (so it can never mine: REPLACED), or is it still
 * simply not mined (no decision - TIMEOUT stays "awaiting chain"). Transactions without an outbox row
 * (the immediate path records no nonce) are never decided here.
 */
@Component
public class OutboxNonceResolver {

    private static final Logger log = LoggerFactory.getLogger(OutboxNonceResolver.class);

    public sealed interface Resolution permits None, MinedAsReplacement, MinedAsCancel, NonceConsumed {}

    public record None() implements Resolution {}

    /** A REPRICE replacement of the same call was mined: the operation executed under {@code hash}. */
    public record MinedAsReplacement(TransactionReceipt receipt, String hash) implements Resolution {}

    /** The operator's cancel replacement was mined: the original call did not and will not execute. */
    public record MinedAsCancel(TransactionReceipt receipt, String hash) implements Resolution {}

    /** The chain has irreversibly moved past this nonce without any known hash of ours. */
    public record NonceConsumed(BigInteger chainCount) implements Resolution {}

    private final EvmSignedSubmissionRepository submissions;

    public OutboxNonceResolver(EvmSignedSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    public Resolution resolve(BlockchainTransaction tx, Web3j web3j, Duration consumedConfirmation) throws Exception {
        Optional<EvmSignedSubmission> own = submissions.findByTxHash(tx.getTxHash());
        if (own.isEmpty()) {
            return new None();
        }
        EvmSignedSubmission row = own.get();
        List<EvmSignedSubmission> sameNonce = submissions.findByChainIdAndSenderAddressIgnoreCaseAndNonceOrderByCreatedAtAsc(
                row.getChainId(), row.getSenderAddress(), row.getNonce());
        for (EvmSignedSubmission sibling : sameNonce) {
            if (sibling.getTxHash().equalsIgnoreCase(row.getTxHash())) {
                continue;
            }
            Optional<TransactionReceipt> receipt =
                    web3j.ethGetTransactionReceipt(sibling.getTxHash()).send().getTransactionReceipt();
            if (receipt.isPresent()) {
                return sibling.getKind() == EvmSignedSubmission.Kind.CANCEL
                        && row.getKind() != EvmSignedSubmission.Kind.CANCEL
                        ? new MinedAsCancel(receipt.get(), sibling.getTxHash())
                        : new MinedAsReplacement(receipt.get(), sibling.getTxHash());
            }
        }
        // No hash of ours mined. If the chain's irreversible count for the signer is beyond this nonce
        // and the row has been unmined for a confirmation period, somebody else used the nonce.
        Instant since = tx.getCompletedAt() != null ? tx.getCompletedAt() : tx.getCreatedAt();
        if (since.plus(consumedConfirmation).isAfter(Instant.now())) {
            return new None();
        }
        BigInteger count = irreversibleCount(web3j, row.getSenderAddress());
        if (count.compareTo(row.getNonce()) > 0) {
            // Re-check the original once more: a lagging node could have answered empty before.
            if (web3j.ethGetTransactionReceipt(tx.getTxHash()).send().getTransactionReceipt().isPresent()) {
                return new None();
            }
            return new NonceConsumed(count);
        }
        return new None();
    }

    /** Marks every row on the nonce except {@code keepHash} (the one that mined, if any) ABANDONED: none can ever mine. */
    public void abandonNonce(BlockchainTransaction tx, String keepHash, String reason, String by,
            java.util.UUID approver) {
        submissions.findByTxHash(tx.getTxHash()).ifPresent(row ->
                submissions.findByChainIdAndSenderAddressIgnoreCaseAndNonceOrderByCreatedAtAsc(
                                row.getChainId(), row.getSenderAddress(), row.getNonce()).stream()
                        .filter(r -> keepHash == null || !r.getTxHash().equalsIgnoreCase(keepHash))
                        .filter(r -> r.getStatus() != EvmSignedSubmission.Status.ABANDONED)
                        .forEach(r -> {
                            r.setStatus(EvmSignedSubmission.Status.ABANDONED);
                            r.setAbandonedAt(Instant.now());
                            r.setAbandonedBy(by);
                            r.setAbandonApproverId(approver);
                            r.setAbandonReason(reason);
                            submissions.save(r);
                        }));
    }

    /** {@code FINALIZED} count where the node supports the tag, otherwise the {@code LATEST} count. */
    static BigInteger irreversibleCount(Web3j web3j, String address) throws Exception {
        try {
            EthGetTransactionCount finalized =
                    web3j.ethGetTransactionCount(address, DefaultBlockParameterName.FINALIZED).send();
            if (!finalized.hasError()) {
                return finalized.getTransactionCount();
            }
            log.debug("finalized nonce unavailable for {} ({}); using latest", address, finalized.getError().getMessage());
        } catch (Exception e) {
            log.debug("finalized nonce lookup failed for {} ({}); using latest", address, e.getMessage());
        }
        EthGetTransactionCount latest = web3j.ethGetTransactionCount(address, DefaultBlockParameterName.LATEST).send();
        if (latest.hasError()) {
            throw new RuntimeException(latest.getError().getMessage());
        }
        return latest.getTransactionCount();
    }
}
