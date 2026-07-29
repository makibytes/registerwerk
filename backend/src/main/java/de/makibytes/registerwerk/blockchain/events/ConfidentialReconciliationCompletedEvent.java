package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fired every time {@code ConfidentialBalanceReconciliationService} completes a run for an asset
 * — whether the register's plaintext (eWpG §16, legally-canonical) {@code
 * AssetHolder.nominalAmount} matched every holder's decrypted on-chain confidential balance or
 * not. Previously this only fired when a mismatch was found: a clean run —
 * which still decrypts every holder's balance via the registry's operator-viewer key, itself a
 * sensitive action — left no audit record at all, so "who ran reconciliation, when, and did it
 * pass" was unanswerable for the overwhelming majority of runs. {@code mismatchCount == 0} is the
 * "it ran and passed" record; {@code mismatchCount > 0} and {@code details} carry enough to
 * triage a real divergence without re-running the reconciliation.
 */
public record ConfidentialReconciliationCompletedEvent(
        UUID assetId, UUID deploymentId, UUID actorId, String actorRole,
        int holderCount, int mismatchCount, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "CONFIDENTIAL_BALANCE_RECONCILIATION_COMPLETED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("deploymentId", deploymentId);
        p.put("holderCount", holderCount);
        p.put("mismatchCount", mismatchCount);
        p.put("details", details);
        return p;
    }
}
