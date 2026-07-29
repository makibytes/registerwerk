package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Emitted after a deliberate KEK-rotation attempt for a wallet —
 * {@code rotated=false} means the wallet predates envelope encryption (legacy keystore, no
 * wrapped DEK to rotate), not a failure.
 */
public record WalletKekRotatedEvent(UUID walletId, UUID actorId, String actorRole, boolean rotated)
        implements AuditableEvent {
    public String eventType()   { return "WALLET_KEK_ROTATED"; }
    public String subjectType() { return "OperatorWallet"; }
    public UUID   subjectId()   { return walletId; }
    public Map<String, Object> payload() { return Map.of("rotated", rotated); }
}
