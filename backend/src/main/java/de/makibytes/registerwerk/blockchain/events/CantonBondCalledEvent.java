package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CantonBondCalledEvent(UUID deploymentId, UUID actorId, Instant callDate, BigDecimal callPrice) implements AuditableEvent {
    public String eventType()   { return "CANTON_BOND_CALLED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() {
        return Map.of("callDate", callDate.toString(), "callPrice", callPrice.toPlainString());
    }
}
