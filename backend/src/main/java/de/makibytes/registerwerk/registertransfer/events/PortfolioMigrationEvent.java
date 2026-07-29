package de.makibytes.registerwerk.registertransfer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** Fired for every portfolio-migration lifecycle transition — mirrors {@link RegisterTransferEvent}
 *  but for the investor-side (per-holding) handover. */
public record PortfolioMigrationEvent(
        UUID migrationId, String stage, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "PORTFOLIO_MIGRATION_" + stage; }
    public String subjectType() { return "PortfolioMigrationRequest"; }
    public UUID   subjectId()   { return migrationId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
