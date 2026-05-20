package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CantonFloatingRateFixedEvent(UUID deploymentId, UUID actorId, BigDecimal rate, Instant fixingDate) implements AuditableEvent {
    public String eventType()   { return "CANTON_FLOATING_RATE_FIXED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() {
        return Map.of("rate", rate.toPlainString(), "fixingDate", fixingDate.toString());
    }
}
