package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when a corporate action's settlement is confirmed — either the automated Canton
 * path ({@code actorId=null, actorRole="SYSTEM"}) or the manual operator attestation
 * ({@code CorporateActionAdminController.markSettled}, real actor). Previously
 * {@code CorporateActionSettlementWriter.markSettled} — the single chokepoint both paths funnel
 * through — published nothing at all: only the settlement <em>request</em> was audited, not its
 * confirmation, so an examiner could not tell from the tamper-evident audit log alone whether,
 * when, or by whom a corporate action was actually settled.
 */
public record CorporateActionSettledEvent(
        UUID corporateActionId, UUID actorId, String actorRole, String txHash
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_SETTLED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("txHash", txHash != null ? txHash : "");
    }
}
