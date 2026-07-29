package de.makibytes.registerwerk.registerstatement.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;

import java.util.Map;
import java.util.UUID;

/**
 * Fired whenever a §19 eWpG register statement (Registerauszug) is issued to a holder —
 * both the automated/event-driven path ({@code RegisterStatementService.issueForHolder},
 * triggers INITIAL_ENTRY/CHANGE/ANNUAL) and the holder's own on-demand self-service download
 * ({@code RegisterStatementService.renderForDownload}, §19(1)). Previously issuance was only
 * ever recorded on the {@code register_statement} row itself, with nothing published to the
 * audit trail — despite this being exactly the kind of statutory
 * disclosure the audit log exists to evidence.
 */
public record RegisterStatementIssuedEvent(
        UUID statementId, UUID holderId, UUID assetId, UUID investorId, StatementTrigger trigger)
        implements AuditableEvent {

    public String eventType()   { return "REGISTER_STATEMENT_ISSUED"; }
    public String subjectType() { return "RegisterStatement"; }
    public UUID   subjectId()   { return statementId; }
    // Issuance is always system-driven — either the daily record-keeping jobs (INITIAL_ENTRY/
    // CHANGE/ANNUAL) or the holder's own self-service download (ON_DEMAND); no operator acts.
    public UUID   actorId()     { return null; }
    public String actorRole()  { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("holderId", holderId.toString(), "assetId", assetId.toString(), "trigger", trigger.name());
    }
}
