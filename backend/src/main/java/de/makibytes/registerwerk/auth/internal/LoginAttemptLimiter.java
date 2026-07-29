package de.makibytes.registerwerk.auth.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Shared, DB-backed brute-force throttle for the built-in HS256 login.
 *
 * <p>The operator frontend bypasses Kong (see CLAUDE.md), so the Kong rate-limiting
 * plugin does not protect {@code POST /api/v1/public/auth/login}. This limiter locks
 * an account key after {@code maxAttempts} consecutive failures within the sliding
 * window; the counter resets on successful login or after the window expires.
 *
 * <p>Backed by the {@code login_attempt} table (V4 migration) rather than an
 * in-memory Caffeine cache — behind a load balancer, an attacker's requests spread
 * across backend instances round-robin, so a per-instance counter would silently
 * multiply the effective lockout threshold by the instance count.
 */
@Component
public class LoginAttemptLimiter {

    private final int maxAttempts;
    private final long lockoutMinutes;
    private final JdbcTemplate jdbc;

    public LoginAttemptLimiter(
            @Value("${registerwerk.auth.login-max-attempts:5}") int maxAttempts,
            @Value("${registerwerk.auth.login-lockout-minutes:15}") long lockoutMinutes,
            JdbcTemplate jdbc) {
        this.maxAttempts = maxAttempts;
        this.lockoutMinutes = lockoutMinutes;
        this.jdbc = jdbc;
    }

    /** Returns true if the key is currently locked out. */
    public boolean isBlocked(String key) {
        Integer count = jdbc.query(
                "SELECT attempt_count FROM login_attempt "
                        + "WHERE login_key = ? AND updated_at > now() - (? || ' minutes')::interval",
                rs -> rs.next() ? rs.getInt(1) : null,
                normalize(key), lockoutMinutes);
        return count != null && count >= maxAttempts;
    }

    /**
     * Records a failed attempt for the key, upserting the counter. A row outside the
     * lockout window is treated as expired and restarted at 1 rather than incremented.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String key) {
        jdbc.update("""
                INSERT INTO login_attempt (login_key, attempt_count, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (login_key) DO UPDATE SET
                    attempt_count = CASE
                        WHEN login_attempt.updated_at > now() - (? || ' minutes')::interval
                            THEN login_attempt.attempt_count + 1
                        ELSE 1
                    END,
                    updated_at = now()
                """, normalize(key), lockoutMinutes);
    }

    /** Clears the counter after a successful login. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String key) {
        jdbc.update("DELETE FROM login_attempt WHERE login_key = ?", normalize(key));
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
