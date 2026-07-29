package de.makibytes.registerwerk.dora.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DORA Art. 24/25 digital operational resilience testing record (vulnerability scans,
 * scenario-based testing, and threat-led penetration testing per RTS (EU) 2025/301).
 * TLPT is mandatory only for entities/systems designated critical by the competent
 * authority — {@link #tlptRequired} tracks that designation per test subject.
 */
@Entity
@Table(name = "resilience_test")
public class ResilienceTest {

    public enum TestType { VULNERABILITY_SCAN, SCENARIO_BASED, TLPT }
    public enum Result { PASSED, FINDINGS_OPEN, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestType testType;

    /** What was tested — e.g. "T-REX identity registry", "EwpgPaymaster", a named ICT provider. */
    @Column(nullable = false)
    private String scope;

    /** Set when the tested subject is a designated-critical function/provider (Art. 26 TLPT scope). */
    @Column(name = "tlpt_required", nullable = false)
    private boolean tlptRequired = false;

    @Column(name = "third_party_provider_id")
    private UUID thirdPartyProviderId;

    @Column(name = "performed_at", nullable = false)
    private LocalDate performedAt;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Result result;

    @Column(columnDefinition = "TEXT")
    private String findings;

    @Column(name = "tester_name")
    private String testerName;

    @Column(name = "report_ref")
    private String reportRef;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public TestType getTestType() { return testType; }
    public void setTestType(TestType t) { this.testType = t; }
    public String getScope() { return scope; }
    public void setScope(String s) { this.scope = s; }
    public boolean isTlptRequired() { return tlptRequired; }
    public void setTlptRequired(boolean b) { this.tlptRequired = b; }
    public UUID getThirdPartyProviderId() { return thirdPartyProviderId; }
    public void setThirdPartyProviderId(UUID u) { this.thirdPartyProviderId = u; }
    public LocalDate getPerformedAt() { return performedAt; }
    public void setPerformedAt(LocalDate d) { this.performedAt = d; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    public void setNextDueDate(LocalDate d) { this.nextDueDate = d; }
    public Result getResult() { return result; }
    public void setResult(Result r) { this.result = r; }
    public String getFindings() { return findings; }
    public void setFindings(String s) { this.findings = s; }
    public String getTesterName() { return testerName; }
    public void setTesterName(String s) { this.testerName = s; }
    public String getReportRef() { return reportRef; }
    public void setReportRef(String s) { this.reportRef = s; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID u) { this.createdBy = u; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
