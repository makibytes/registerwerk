package de.makibytes.registerwerk.webhook.api;

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

/** One delivery attempt record for a {@link WebhookSubscription} — kept even after success, as
 *  the debugging/audit log an integrator's own ops team will ask for. */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private WebhookEventType eventType;

    /** Raw JSON body sent (or to be sent) — stored as text so the retry job can resend the exact
     *  same bytes rather than re-serializing (and risking a different signature/body pairing). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }

    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }

    public WebhookEventType getEventType() { return eventType; }
    public void setEventType(WebhookEventType eventType) { this.eventType = eventType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public WebhookDeliveryStatus getStatus() { return status; }
    public void setStatus(WebhookDeliveryStatus status) { this.status = status; }

    public Integer getResponseCode() { return responseCode; }
    public void setResponseCode(Integer responseCode) { this.responseCode = responseCode; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public void setLastAttemptedAt(Instant lastAttemptedAt) { this.lastAttemptedAt = lastAttemptedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
