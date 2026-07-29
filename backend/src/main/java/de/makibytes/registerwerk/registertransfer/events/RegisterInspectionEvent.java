package de.makibytes.registerwerk.registertransfer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired for every §10 eWpG register-inspection decision — auto-approval of a Berechtigter
 * at submission, and an operator's explicit approve/reject of a LEGITIMATE_INTEREST request
 *. Previously {@code RegisterInspectionService} recorded the decision
 * only on the {@code RegisterInspectionRequest} row itself, with no audit trail of who
 * decided a third party's access to another entity's register data, or why.
 */
public record RegisterInspectionEvent(
        UUID requestId, UUID assetId, String stage, UUID actorId, String actorRole, String reason)
        implements AuditableEvent {

    public String eventType()   { return "REGISTER_INSPECTION_" + stage; }
    public String subjectType() { return "RegisterInspectionRequest"; }
    public UUID   subjectId()   { return requestId; }
    public Map<String, Object> payload() {
        return Map.of("assetId", assetId != null ? assetId.toString() : "", "reason", reason != null ? reason : "");
    }
}
