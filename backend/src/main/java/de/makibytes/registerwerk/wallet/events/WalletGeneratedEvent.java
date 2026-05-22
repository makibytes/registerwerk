package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record WalletGeneratedEvent(UUID walletId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "WALLET_GENERATED"; }
    public String subjectType() { return "OperatorWallet"; }
    public UUID   subjectId()   { return walletId; }
    public Map<String, Object> payload() { return Map.of(); }
}
