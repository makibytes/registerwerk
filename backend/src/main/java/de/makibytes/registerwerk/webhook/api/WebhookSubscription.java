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

    /** HMAC-SHA256 signing secret, stored as {@code enc:v1:<b64>} (AES-256-GCM under a KEK-wrapped
     *  data key, AAD = subscription id). Generated server-side; the plaintext is returned once, in
     *  the create / rotate-secret response, and never re-displayed. Rows from before V28 may still
     *  hold legacy plaintext until the startup maintenance re-encrypts them. */
    @Column(nullable = false)
    private String secret;

    /** Previous secret (encrypted the same way), still accepted for signing during the rotation
     *  overlap window; see {@code registerwerk.webhook.rotation-overlap-hours}. */
    @Column(name = "secret_previous_enc")
    private String secretPreviousEnc;

    @Column(name = "secret_rotated_at")
    private Instant secretRotatedAt;

    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    /** Why the subscription was disabled by the platform (URL_POLICY, CIRCUIT_BREAKER); null if
     *  enabled or disabled by its owner. */
    @Column(name = "disabled_reason", length = 40)
    private String disabledReason;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

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

    public String getSecretPreviousEnc() { return secretPreviousEnc; }
    public void setSecretPreviousEnc(String secretPreviousEnc) { this.secretPreviousEnc = secretPreviousEnc; }

    public Instant getSecretRotatedAt() { return secretRotatedAt; }
    public void setSecretRotatedAt(Instant secretRotatedAt) { this.secretRotatedAt = secretRotatedAt; }

    public int getKeyVersion() { return keyVersion; }
    public void setKeyVersion(int keyVersion) { this.keyVersion = keyVersion; }

    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }

    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

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
