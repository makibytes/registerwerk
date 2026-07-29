package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fired once per on-chain confidential transfer/mint event {@code ConfidentialTravelRuleScreeningService}
 * processes — decrypting a holder's transfer amount via the registry's registered-viewer key is
 * itself a sensitive action, so "who decrypted whose confidential balance and when" must be
 * answerable from this log; every attempt is recorded regardless of the Travel Rule outcome,
 * not only failures.
 */
public record ConfidentialTransferScreenedEvent(
        UUID assetId, UUID deploymentId, UUID actorId, String actorRole,
        String confidentialEventType, String fromWallet, String toWallet,
        String transactionHash, int logIndex, boolean decryptSucceeded, String error)
        implements AuditableEvent {

    public String eventType()   { return "CONFIDENTIAL_TRANSFER_SCREENED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deploymentId", deploymentId);
        details.put("confidentialEventType", confidentialEventType);
        details.put("fromWallet", fromWallet);
        details.put("toWallet", toWallet);
        details.put("transactionHash", transactionHash);
        details.put("logIndex", logIndex);
        details.put("decryptSucceeded", decryptSucceeded);
        if (error != null) {
            details.put("error", error);
        }
        return details;
    }
}
