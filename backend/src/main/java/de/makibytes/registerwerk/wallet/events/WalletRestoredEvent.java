package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A soft-deleted operator wallet was restored within its retention window (P4C-5). */
public record WalletRestoredEvent(UUID walletId, UUID actorId, String actorRole, UUID approverId)
        implements AuditableEvent {
    public String eventType()   { return "WALLET_RESTORED"; }
    public String subjectType() { return "OperatorWallet"; }
    public UUID   subjectId()   { return walletId; }
    public UUID   dualControlApproverId() { return approverId; }
    public Map<String, Object> payload() { return Map.of(); }
}
