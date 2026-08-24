package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Published when an operator rejects an issuer's PROPOSED corporate action —
 *  {@code CorporateActionService.rejectProposal}. Terminal: a rejected proposal never
 *  becomes ANNOUNCED; the issuer must submit a fresh proposal if still applicable. */
public record CorporateActionProposalRejectedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, String reason
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_PROPOSAL_REJECTED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("reason", reason != null ? reason : "");
    }
}
