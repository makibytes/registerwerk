package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "screening_run")
public class ScreeningRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "natural_person_id")
    private UUID naturalPersonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private ScreeningTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScreeningStatus status;

    @Column(nullable = false)
    private String provider;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "lists_checked", columnDefinition = "text[]")
    private List<String> listsChecked;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "initiated_by")
    private UUID initiatedBy;

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public UUID getNaturalPersonId() { return naturalPersonId; }
    public void setNaturalPersonId(UUID naturalPersonId) { this.naturalPersonId = naturalPersonId; }
    public ScreeningTrigger getTriggerType() { return triggerType; }
    public void setTriggerType(ScreeningTrigger triggerType) { this.triggerType = triggerType; }
    public ScreeningStatus getStatus() { return status; }
    public void setStatus(ScreeningStatus status) { this.status = status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public List<String> getListsChecked() { return listsChecked; }
    public void setListsChecked(List<String> listsChecked) { this.listsChecked = listsChecked; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(UUID initiatedBy) { this.initiatedBy = initiatedBy; }
}
