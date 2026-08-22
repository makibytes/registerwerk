package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when an operator approves an issuer's PROPOSED corporate action, moving it to
 * ANNOUNCED — {@code CorporateActionService.approveProposal}. The action's own
 * {@link CorporateActionAnnouncedEvent} is published alongside this one (unchanged), so
 * "announced" audit consumers see every announced action regardless of how it was created;
 * this event exists specifically to record the operator's review decision.
 */
public record CorporateActionProposalApprovedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, UUID proposedBy
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_PROPOSAL_APPROVED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("proposedBy", proposedBy != null ? proposedBy.toString() : "");
    }
}
