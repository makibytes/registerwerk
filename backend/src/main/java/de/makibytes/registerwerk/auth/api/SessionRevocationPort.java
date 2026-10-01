package de.makibytes.registerwerk.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit session revocation for other modules (customer termination, TOTP reset, access
 * review). Setters on {@link AppUser} already bump {@code tokens_valid_after} on every access
 * change; this port is for revocations that change no such field.
 */
public interface SessionRevocationPort {

    /** Rejects every token issued to this user before now (next full second). */
    void revokeAll(UUID userId);

    /** Rejects one token by its {@code jti} until it would have expired anyway. */
    void revokeSession(String jti, UUID userId, Instant tokenExpiresAt, String reason);
}
