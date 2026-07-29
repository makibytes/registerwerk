package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record WalletDefaultChangedEvent(
        UUID walletId, UUID actorId, String actorRole, UUID chainConfigId, UUID dualControlApproverId)
        implements AuditableEvent {
    public String eventType()   { return "WALLET_DEFAULT_CHANGED"; }
    public String subjectType() { return "OperatorWallet"; }
    public UUID   subjectId()   { return walletId; }
    public Map<String, Object> payload() { return chainConfigId != null ? Map.of("chainConfigId", chainConfigId.toString()) : Map.of(); }
    public UUID   dualControlApproverId() { return dualControlApproverId; }
}
