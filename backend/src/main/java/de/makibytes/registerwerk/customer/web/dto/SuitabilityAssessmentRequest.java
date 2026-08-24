package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel;
import de.makibytes.registerwerk.customer.api.RiskTolerance;
import jakarta.validation.constraints.NotNull;

public record SuitabilityAssessmentRequest(
        @NotNull KnowledgeExperienceLevel knowledgeExperience,
        @NotNull RiskTolerance riskTolerance,
        Integer investmentHorizonYears,
        boolean financialSituationAdequate,
        String notes
) {}
