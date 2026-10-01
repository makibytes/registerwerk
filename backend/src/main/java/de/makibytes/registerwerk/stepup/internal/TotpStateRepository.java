package de.makibytes.registerwerk.stepup.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * DB-backed TOTP replay and lockout state ({@code totp_state}), shared by every replica (K3, 6-09).
 * Each operation commits in its own transaction so a rejected verification still persists its failure
 * and an accepted code is consumed even if the caller's transaction later rolls back.
 */
@Repository
class TotpStateRepository {

    static final int MAX_ATTEMPTS = 5;
    static final int LOCK_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    TotpStateRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    boolean isLocked(UUID userId) {
        Integer one = jdbc.query("SELECT 1 FROM totp_state WHERE user_id = ? AND locked_until > now()",
                rs -> rs.next() ? 1 : null, userId);
        return one != null;
    }

    /** Counts a failed verification; locks the account after {@value #MAX_ATTEMPTS} failures in the window. */
    void recordFailure(UUID userId) {
        requiresNew.executeWithoutResult(status -> {
            ensureRow(userId);
            int[] row = jdbc.query("""
                    SELECT CASE WHEN updated_at < now() - make_interval(mins => ?)
                                  OR (locked_until IS NOT NULL AND locked_until <= now())
                                THEN 0 ELSE failed_attempts END
                    FROM totp_state WHERE user_id = ? FOR UPDATE
                    """, rs -> rs.next() ? new int[]{rs.getInt(1)} : null, LOCK_MINUTES, userId);
            int failures = (row == null ? 0 : row[0]) + 1;
            jdbc.update("""
                    UPDATE totp_state SET failed_attempts = ?,
                        locked_until = CASE WHEN ? >= ? THEN now() + make_interval(mins => ?) ELSE NULL END,
                        updated_at = now()
                    WHERE user_id = ?
                    """, failures, failures, MAX_ATTEMPTS, LOCK_MINUTES, userId);
        });
    }

    /**
     * Atomically records {@code step} as the last accepted time step (RFC 6238 section 5.2) and clears the
     * failure counter. @return false when a code at or after this step was already accepted - on this or
     * any other replica - i.e. a replay.
     */
    boolean acceptStep(UUID userId, long step) {
        Boolean accepted = requiresNew.execute(status -> {
            ensureRow(userId);
            return jdbc.update("""
                    UPDATE totp_state SET last_accepted_step = ?, failed_attempts = 0, locked_until = NULL,
                                          updated_at = now()
                    WHERE user_id = ? AND last_accepted_step < ?
                    """, step, userId, step) == 1;
        });
        return Boolean.TRUE.equals(accepted);
    }

    /** Forgets all state (disenrolment / operator reset): a fresh secret starts from step 0, unlocked. */
    void reset(UUID userId) {
        jdbc.update("DELETE FROM totp_state WHERE user_id = ?", userId);
    }

    private void ensureRow(UUID userId) {
        jdbc.update("INSERT INTO totp_state (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
    }
}
