package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Verified outcome of a confidential (Zama fhEVM) forced operation — {@code
 * confidentialForcedTransfer} (eWpG §24 Berichtigung) or {@code confidentialForceBurn} (§26
 * Einziehung). The contract's FHE {@code select(le(amount, balance), amount, 0)} is
 * all-or-nothing, so a confirmed transaction may have moved nothing; {@code
 * ConfidentialForcedOpVerifier} decrypts the moved-amount handle from the receipt with the
 * registry's operator-viewer key and publishes exactly one of these per transaction.
 *
 * <p>{@code decryptedAmount} is intentionally carried only in this audit payload (never logged),
 * the same convention {@link ConfidentialReconciliationCompletedEvent} uses. Decrypting it is
 * itself a sensitive action, so the record exists for every outcome, not only failures.
 */
public record ConfidentialForcedOpOutcomeEvent(
        UUID assetId, UUID deploymentId, UUID txId, String txHash, String methodName,
        Outcome outcome, String orderedAmount, String decryptedAmount, String reason,
        UUID actorId, String actorRole)
        implements AuditableEvent {

    public enum Outcome {
        /** Decrypted moved amount equals the ordered amount. */
        EXECUTED,
        /** Decrypted moved amount is 0: the holder's balance was below the ordered amount. */
        NOT_EXECUTED_INSUFFICIENT_BALANCE,
        /** The moved-amount handle could not be decrypted within the retry budget. */
        UNVERIFIED_DECRYPT_FAILED,
        /** No matching event in the receipt, or a decrypted amount that is neither 0 nor the
         *  ordered amount — impossible for the current contracts, so it needs a human. */
        UNVERIFIED_INCONSISTENT
    }

    public String eventType()   { return "CONFIDENTIAL_FORCED_OP_OUTCOME"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("deploymentId", deploymentId);
        p.put("txId", txId);
        p.put("txHash", txHash);
        p.put("methodName", methodName);
        p.put("outcome", outcome.name());
        p.put("orderedAmount", orderedAmount);
        if (decryptedAmount != null) {
            p.put("decryptedAmount", decryptedAmount);
        }
        if (reason != null) {
            p.put("reason", reason);
        }
        return p;
    }
}
