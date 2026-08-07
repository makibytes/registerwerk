package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel;
import de.makibytes.registerwerk.customer.api.RiskTolerance;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;

import java.time.Instant;
import java.util.UUID;

public record SuitabilityAssessmentResponse(
        UUID id,
        UUID entityId,
        KnowledgeExperienceLevel knowledgeExperience,
        RiskTolerance riskTolerance,
        Integer investmentHorizonYears,
        boolean financialSituationAdequate,
        String notes,
        Instant assessedAt,
        UUID assessedBy
) {
    public static SuitabilityAssessmentResponse from(SuitabilityAssessment a) {
        return new SuitabilityAssessmentResponse(
                a.getId(), a.getEntityId(), a.getKnowledgeExperience(), a.getRiskTolerance(),
                a.getInvestmentHorizonYears(), a.isFinancialSituationAdequate(), a.getNotes(),
                a.getAssessedAt(), a.getAssessedBy());
    }
}
