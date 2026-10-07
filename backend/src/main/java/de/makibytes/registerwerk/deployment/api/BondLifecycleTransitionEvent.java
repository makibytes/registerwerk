package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A bond's status really changed by the system (MATURED, OVERDUE, DEFAULTED, CALLED at maturity). One record, five
 * auditor-visible event types derived from the target state ({@code BOND_MATURED}, {@code BOND_OVERDUE},
 * {@code BOND_DEFAULTED}, {@code BOND_CALLED}); never published when the status stays as it was (9A-04R).
 * Customers, repo and lending read the status, so the change must be reconstructable: the payload carries the
 * redemption action ids, the earliest payment date and the principal grace so a DEFAULTED declaration can be re-derived.
 * System-attributed.
 */
public record BondLifecycleTransitionEvent(UUID assetId, BondStatus from, BondStatus to, String cause,
                                           List<UUID> actionIds, LocalDate earliestPaymentDate, Integer graceDays)
        implements AuditableEvent {

    @Override public String eventType()   { return "BOND_" + to; }
    @Override public String subjectType() { return "Asset"; }
    @Override public UUID   subjectId()   { return assetId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", String.valueOf(from));
        payload.put("to", String.valueOf(to));
        payload.put("cause", cause);
        payload.put("actionIds", actionIds == null ? List.of() : actionIds.stream().map(UUID::toString).toList());
        if (earliestPaymentDate != null) {
            payload.put("earliestPaymentDate", earliestPaymentDate.toString());
        }
        if (graceDays != null) {
            payload.put("graceDays", graceDays);
        }
        return payload;
    }
}
