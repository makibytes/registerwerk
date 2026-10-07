package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Emitted when an operator retired a KEK version after the guard verified that no ciphertext references it. */
public record KekVersionRetiredEvent(UUID actorId, String actorRole, UUID dualControlApproverId, String version,
                                     boolean disabledInProcess) implements AuditableEvent {
    private static final UUID SUBJECT = new UUID(0L, 0L);

    public String eventType()   { return "KEK_VERSION_RETIRED"; }
    public String subjectType() { return "KekVersion"; }
    public UUID   subjectId()   { return SUBJECT; }
    public Map<String, Object> payload() { return Map.of("version", version, "disabledInProcess", disabledInProcess); }
}
