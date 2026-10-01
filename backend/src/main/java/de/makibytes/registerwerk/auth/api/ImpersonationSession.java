package de.makibytes.registerwerk.auth.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One operator impersonation of a customer entity. {@code id} doubles as the session token's
 * {@code jti}. Lives in {@code auth.api} (not {@code admin}) because the session guard in
 * {@code auth} must read it and {@code admin} already depends on {@code auth}.
 */
@Entity
@Table(name = "impersonation_session")
public class ImpersonationSession {

    @Id
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "target_entity_id", nullable = false)
    private UUID targetEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImpersonationMode mode;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "ticket_ref", length = 100)
    private String ticketRef;

    @Column(name = "approver_id")
    private UUID approverId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_by")
    private UUID endedBy;

    @Column(name = "end_reason", length = 40)
    private String endReason;

    @Column(name = "handoff_code_hash", nullable = false, length = 64)
    private String handoffCodeHash;

    @Column(name = "handoff_expires_at", nullable = false)
    private Instant handoffExpiresAt;

    @Column(name = "handoff_consumed_at")
    private Instant handoffConsumedAt;

    protected ImpersonationSession() {}

    public ImpersonationSession(UUID id, UUID actorId, UUID targetEntityId, ImpersonationMode mode, String reason,
                                String ticketRef, UUID approverId, Instant expiresAt,
                                String handoffCodeHash, Instant handoffExpiresAt) {
        this.id = id;
        this.actorId = actorId;
        this.targetEntityId = targetEntityId;
        this.mode = mode;
        this.reason = reason;
        this.ticketRef = ticketRef;
        this.approverId = approverId;
        this.expiresAt = expiresAt;
        this.handoffCodeHash = handoffCodeHash;
        this.handoffExpiresAt = handoffExpiresAt;
    }

    /** SHA-256 hex of a handoff code; only the hash is ever stored. */
    public static String hashHandoffCode(String code) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isActive(Instant now) {
        return endedAt == null && expiresAt.isAfter(now);
    }

    public void end(UUID by, String why, Instant now) {
        if (endedAt == null) {
            this.endedAt = now;
            this.endedBy = by;
            this.endReason = why;
        }
    }

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public UUID getTargetEntityId() { return targetEntityId; }
    public ImpersonationMode getMode() { return mode; }
    public String getReason() { return reason; }
    public String getTicketRef() { return ticketRef; }
    public UUID getApproverId() { return approverId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getEndedAt() { return endedAt; }
    public UUID getEndedBy() { return endedBy; }
    public String getEndReason() { return endReason; }
    public Instant getHandoffExpiresAt() { return handoffExpiresAt; }
    public Instant getHandoffConsumedAt() { return handoffConsumedAt; }
}
