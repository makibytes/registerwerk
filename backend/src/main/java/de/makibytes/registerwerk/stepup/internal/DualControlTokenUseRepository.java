package de.makibytes.registerwerk.stepup.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Single-use ledger of dual-control approver tokens (table {@code dual_control_token_use}). */
@Repository
class DualControlTokenUseRepository {

    private final JdbcTemplate jdbc;

    DualControlTokenUseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically claims the token. Must run in the transaction that also records the approval, so a
     * failed audit write does not burn the approval.
     *
     * @return false when the jti was already used (by this or another replica)
     */
    boolean tryConsume(String jti, UUID approverId, UUID initiatorId, String action, String targetDigest,
                       Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO dual_control_token_use (jti, approver_id, initiator_id, action, target_digest, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (jti) DO NOTHING
                """, jti, approverId, initiatorId, action, targetDigest, Timestamp.from(expiresAt)) == 1;
    }

    int pruneExpired() {
        return jdbc.update("DELETE FROM dual_control_token_use WHERE expires_at < now() - interval '1 hour'");
    }
}
