package de.makibytes.registerwerk.webhook.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An external endpoint that a {@code LegalEntity} has asked to be POSTed to when curated events
 * about it occur (F-BLOCKER-2). {@code eventTypes} is stored as a comma-separated string rather
 * than an {@code @ElementCollection} table — a small, entirely-owned-by-this-row list, not a
 * queried-independently join.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(nullable = false, length = 2048)
    private String url;

    /** HMAC-SHA256 signing secret — generated server-side, shown to the caller once at
     *  creation, never re-displayed afterward (see the controller's create response). */
    @Column(nullable = false, length = 128)
    private String secret;

    @Column(name = "event_types", nullable = false, length = 1000)
    private String eventTypesRaw = "";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    public UUID getId() { return id; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    /** Empty set means "subscribed to every curated event type." */
    public Set<WebhookEventType> getEventTypes() {
        if (eventTypesRaw == null || eventTypesRaw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventTypesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(WebhookEventType::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setEventTypes(Set<WebhookEventType> eventTypes) {
        this.eventTypesRaw = eventTypes == null || eventTypes.isEmpty()
                ? ""
                : eventTypes.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    public boolean isSubscribedTo(WebhookEventType type) {
        Set<WebhookEventType> types = getEventTypes();
        return types.isEmpty() || types.contains(type);
    }
}
