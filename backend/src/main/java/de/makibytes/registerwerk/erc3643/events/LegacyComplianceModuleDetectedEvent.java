package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A suite's ModularCompliance still has the legacy {@code EwpgComplianceModule} bound: a build whose
 * setters ({@code setMaxInvestors}, {@code setMaxBalance}, {@code blockCountry}, ...) only require the
 * compliance to be bound, so any wallet can change the token's limits, and which has no {@code syncHolders}
 * back-fill. Raised once per module (operator task + audit); the fix is the audited
 * {@code POST .../compliance-modules/replace} operator action.
 */
public record LegacyComplianceModuleDetectedEvent(UUID suiteId, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "COMPLIANCE_MODULE_LEGACY_DETECTED"; }
    public String subjectType() { return "Erc3643Suite"; }
    public UUID   subjectId()   { return suiteId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
