package de.makibytes.registerwerk.wallet.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Emitted after a KEK re-wrap run over every envelope-encrypted secret type. Carries counts per type only -
 * never a secret, a ciphertext or a wrapped key. {@code actorId} is null for the nightly job.
 */
public record KekRewrapCompletedEvent(UUID actorId, String actorRole, UUID dualControlApproverId,
                                      String activeVersion, Map<String, int[]> countsByType)
        implements AuditableEvent {
    private static final UUID SUBJECT = new UUID(0L, 0L);

    public String eventType()   { return "KEK_REWRAP_COMPLETED"; }
    public String subjectType() { return "KekRewrap"; }
    public UUID   subjectId()   { return SUBJECT; }

    public Map<String, Object> payload() {
        Map<String, Object> counts = new LinkedHashMap<>();
        countsByType.forEach((type, c) -> counts.put(type, Map.of("rewrapped", c[0], "failed", c[1])));
        return Map.of("activeVersion", activeVersion == null ? "" : activeVersion, "counts", counts);
    }
}
