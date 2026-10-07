package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * A bond's redemption is past its payment date but the delay is on the REGISTRY side (record-date snapshot blocked,
 * register frozen / handed over, settlement held by the system), not the issuer's: the bond is deliberately NOT moved
 * to OVERDUE or DEFAULTED, which customers see and which repo / lending controls read (Wave 0b H6). An operator task
 * is raised; the event is audited once per distinct cause. System-attributed. 9A-04R: the same event reports a
 * redemption that waits for the OPERATOR (cause starts {@code OPERATOR_CONFIRMATION_MISSING} /
 * {@code SETTLEMENT_NOT_DISPATCHED}) - operator slowness is not issuer non-payment either.
 */
public record BondRedemptionBlockedEvent(UUID assetId, UUID corporateActionId, String cause)
        implements AuditableEvent {
    @Override public String eventType()   { return "BOND_REDEMPTION_BLOCKED"; }
    @Override public String subjectType() { return "Asset"; }
    @Override public UUID   subjectId()   { return assetId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }
    @Override public Map<String, Object> payload() {
        return Map.of("corporateActionId", String.valueOf(corporateActionId), "cause", cause);
    }
}
