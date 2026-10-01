package de.makibytes.registerwerk.auth.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A revoked session token ({@code jti}); the row can be pruned after {@code expiresAt}. */
@Entity
@Table(name = "session_revocation")
class SessionRevocation {

    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(length = 40)
    private String reason;

    protected SessionRevocation() {}

    SessionRevocation(String jti, UUID userId, Instant expiresAt, String reason) {
        this.jti = jti;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.reason = reason;
    }
}
