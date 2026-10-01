package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Production mode started with the SoftHSM profile because
 * {@code registerwerk.wallet.hsm.allow-software-token=true} acknowledged it (7B-08 / T7-05 interim).
 */
public record SoftwareHsmAcknowledgedEvent(String profile) implements AuditableEvent {
    private static final UUID SUBJECT = UUID.nameUUIDFromBytes("registerwerk-software-hsm".getBytes());
    public String eventType()   { return "SOFTWARE_HSM_ACKNOWLEDGED"; }
    public String subjectType() { return "HsmProfile"; }
    public UUID   subjectId()   { return SUBJECT; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("profile", profile == null ? "" : profile, "productionMode", true);
    }
}
