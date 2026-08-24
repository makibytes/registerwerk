package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record SuitabilityAssessmentRecordedEvent(
        UUID entityId, UUID actorId, String actorRole, UUID assessmentId,
        String knowledgeExperience, String riskTolerance
) implements AuditableEvent {
    public String eventType()   { return "SUITABILITY_ASSESSMENT_RECORDED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() {
        return Map.of(
                "assessmentId", assessmentId.toString(),
                "knowledgeExperience", knowledgeExperience,
                "riskTolerance", riskTolerance);
    }
}
