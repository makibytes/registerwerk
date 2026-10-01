package de.makibytes.registerwerk.dora.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Last alert sent for one (incident, breach type), so the 15-minute job does not re-send every run. */
@Entity
@Table(name = "ict_incident_alert")
public class IctIncidentAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "breach_type", nullable = false, updatable = false)
    private String breachType;

    @Column(name = "alerted_at", nullable = false)
    private Instant alertedAt = Instant.now();

    protected IctIncidentAlert() {}

    public IctIncidentAlert(UUID incidentId, String breachType) {
        this.incidentId = incidentId;
        this.breachType = breachType;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getBreachType() { return breachType; }
    public Instant getAlertedAt() { return alertedAt; }
    public void setAlertedAt(Instant t) { this.alertedAt = t; }
}
