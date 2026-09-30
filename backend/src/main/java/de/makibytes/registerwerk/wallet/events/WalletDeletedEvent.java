package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** Soft delete (P4C-5): address and name are recorded so the key stays identifiable after the purge. */
public record WalletDeletedEvent(UUID walletId, UUID actorId, String actorRole,
                                 UUID approverId, String address, String name) implements AuditableEvent {
    public String eventType()   { return "WALLET_DELETED"; }
    public String subjectType() { return "OperatorWallet"; }
    public UUID   subjectId()   { return walletId; }
    public UUID   dualControlApproverId() { return approverId; }
    public Map<String, Object> payload() {
        return Map.of("address", address == null ? "" : address, "name", name == null ? "" : name, "soft", true);
    }
}
