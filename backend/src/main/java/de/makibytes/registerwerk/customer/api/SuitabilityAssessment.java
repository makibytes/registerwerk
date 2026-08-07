package de.makibytes.registerwerk.customer.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A point-in-time MiFID suitability record for a {@link LegalEntity} — knowledge/experience,
 * risk tolerance, investment horizon, and whether its financial situation was assessed as
 * adequate for the products it invests in. Immutable once created (a reassessment is a new
 * row, not an edit) so the history of what was known and when is preserved for audit —
 * mirroring how {@code EntityNameHistory} treats name changes as appended history, not patches.
 */
@Entity
@Table(name = "suitability_assessment")
public class SuitabilityAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_experience", nullable = false, length = 20)
    private KnowledgeExperienceLevel knowledgeExperience;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tolerance", nullable = false, length = 20)
    private RiskTolerance riskTolerance;

    @Column(name = "investment_horizon_years")
    private Integer investmentHorizonYears;

    @Column(name = "financial_situation_adequate", nullable = false)
    private boolean financialSituationAdequate;

    @Column(length = 2000)
    private String notes;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt = Instant.now();

    @Column(name = "assessed_by")
    private UUID assessedBy;

    public UUID getId() { return id; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public KnowledgeExperienceLevel getKnowledgeExperience() { return knowledgeExperience; }
    public void setKnowledgeExperience(KnowledgeExperienceLevel knowledgeExperience) { this.knowledgeExperience = knowledgeExperience; }

    public RiskTolerance getRiskTolerance() { return riskTolerance; }
    public void setRiskTolerance(RiskTolerance riskTolerance) { this.riskTolerance = riskTolerance; }

    public Integer getInvestmentHorizonYears() { return investmentHorizonYears; }
    public void setInvestmentHorizonYears(Integer investmentHorizonYears) { this.investmentHorizonYears = investmentHorizonYears; }

    public boolean isFinancialSituationAdequate() { return financialSituationAdequate; }
    public void setFinancialSituationAdequate(boolean financialSituationAdequate) { this.financialSituationAdequate = financialSituationAdequate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getAssessedAt() { return assessedAt; }

    public UUID getAssessedBy() { return assessedBy; }
    public void setAssessedBy(UUID assessedBy) { this.assessedBy = assessedBy; }
}
