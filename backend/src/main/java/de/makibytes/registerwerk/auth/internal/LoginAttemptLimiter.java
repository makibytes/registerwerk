package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.events.LoginLockedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, DB-backed brute-force throttle for the built-in HS256 login (K3, 6-10).
 *
 * <p>The operator frontend bypasses Kong, so the Kong rate-limiting plugin does not protect
 * {@code POST /api/v1/public/auth/login}. Throttle state lives in {@code login_attempt}, not in a
 * per-JVM cache, so every replica enforces the same counters.
 *
 * <p>Four counters per failure, all in the same table under typed keys:
 * <ul>
 *   <li><b>PAIR</b> {@code p|email|ip}: the only hard lock. {@code maxAttempts} failures inside the
 *       window lock that account from that source address for {@code lockoutMinutes}, doubling for every
 *       further episode (capped at {@code maxLockoutMinutes}). An attacker elsewhere cannot lock the
 *       real user out (the old key was the e-mail alone, so anyone could).</li>
 *   <li><b>IP</b> {@code i|ip}: {@code ipMaxFailures} failures from one address inside the window block
 *       that address (password spray across many accounts).</li>
 *   <li><b>ACCOUNT</b> {@code a|email}: never a lock. After {@code maxAttempts} failures from anywhere the
 *       next attempt must wait 1, 2, 4 s (capped) after the last failure, applied to known and unknown
 *       e-mails alike. The wait is answered with a 429 and {@code Retry-After}: a request thread never sleeps
 *       (it used to, inside a database transaction).</li>
 *   <li><b>GLOBAL</b> {@code g|}: once the platform-wide failure rate exceeds the per-minute cap, addresses
 *       that already have failures in the window are refused; clean addresses keep working.</li>
 * </ul>
 *
 * <p>Growth is bounded: unknown e-mails create rows, so new PAIR/ACCOUNT rows are only inserted while the
 * table is below {@code maxTrackedKeys} (IP rows up to 1.5x), and {@link #purgeStale()} deletes expired rows.
 */
@Component
public class LoginAttemptLimiter {

    private static final long GLOBAL_WINDOW_SECONDS = 60;
    private static final long EPISODE_MEMORY_SECONDS = 24 * 3600L;
    private static final long COUNT_CACHE_MILLIS = 5_000;
    private static final String GLOBAL_KEY = "g|";

    private final int maxAttempts;
    private final long lockoutMinutes;
    private final long maxLockoutMinutes;
    private final int ipMaxFailures;
    private final int globalMaxFailuresPerMinute;
    private final long maxTrackedKeys;
    private final long maxDelayMillis;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    private final AtomicLong cachedCount = new AtomicLong();
    private volatile long cachedCountAt = 0;

    public LoginAttemptLimiter(
            @Value("${registerwerk.auth.login-max-attempts:5}") int maxAttempts,
            @Value("${registerwerk.auth.login-lockout-minutes:15}") long lockoutMinutes,
            @Value("${registerwerk.auth.login-max-lockout-minutes:240}") long maxLockoutMinutes,
            @Value("${registerwerk.auth.login-ip-max-failures:30}") int ipMaxFailures,
            @Value("${registerwerk.auth.login-global-max-failures-per-minute:600}") int globalMaxFailuresPerMinute,
            @Value("${registerwerk.auth.login-max-tracked-keys:200000}") long maxTrackedKeys,
            @Value("${registerwerk.auth.login-max-delay-millis:4000}") long maxDelayMillis,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events) {
        this.maxAttempts = maxAttempts;
        this.lockoutMinutes = lockoutMinutes;
        this.maxLockoutMinutes = Math.max(maxLockoutMinutes, lockoutMinutes);
        this.ipMaxFailures = ipMaxFailures;
        this.globalMaxFailuresPerMinute = globalMaxFailuresPerMinute;
        this.maxTrackedKeys = maxTrackedKeys;
        this.maxDelayMillis = maxDelayMillis;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Outcome of {@link #check}: proceed, or refuse with the whole seconds after which to try again. */
    public record Decision(boolean blocked, long retryAfterSeconds) {
        public static final Decision OPEN = new Decision(false, 0);

        public static Decision refuseFor(double seconds) {
            return new Decision(true, Math.max(1, (long) Math.ceil(seconds)));
        }
    }

    public Decision check(String email, String clientIp) {
        String e = normalizeEmail(email);
        String ip = normalizeIp(clientIp);
        String pairKey = pairKey(e, ip);
        String ipKey = "i|" + ip;
        String accountKey = "a|" + e;

        record Row(int count, double lockLeftSeconds, double ageSeconds, double sinceUpdateSeconds) {}
        Map<String, Row> rows = new HashMap<>();
        jdbc.query("""
                SELECT login_key, attempt_count,
                       COALESCE(EXTRACT(EPOCH FROM (locked_until - now())), 0) AS lock_left,
                       EXTRACT(EPOCH FROM (now() - window_start)) AS age,
                       EXTRACT(EPOCH FROM (now() - updated_at)) AS since_update
                FROM login_attempt WHERE login_key IN (?, ?, ?, ?)
                """, rs -> {
            rows.put(rs.getString(1), new Row(rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
        }, pairKey, ipKey, accountKey, GLOBAL_KEY);

        long windowSeconds = lockoutMinutes * 60;
        Row pair = rows.get(pairKey);
        if (pair != null && pair.lockLeftSeconds() > 0) {
            return Decision.refuseFor(pair.lockLeftSeconds());
        }
        Row ipRow = rows.get(ipKey);
        boolean ipHasFailures = ipRow != null && ipRow.ageSeconds() < windowSeconds && ipRow.count() > 0;
        if (ipHasFailures && ipRow.count() >= ipMaxFailures) {
            return Decision.refuseFor(windowSeconds - ipRow.ageSeconds());
        }
        Row global = rows.get(GLOBAL_KEY);
        if (ipHasFailures && global != null && global.ageSeconds() < GLOBAL_WINDOW_SECONDS
                && global.count() >= globalMaxFailuresPerMinute) {
            return Decision.refuseFor(GLOBAL_WINDOW_SECONDS - global.ageSeconds());
        }
        Row account = rows.get(accountKey);
        if (account != null && account.ageSeconds() < windowSeconds && account.count() >= maxAttempts) {
            long exponent = Math.min(20, account.count() - maxAttempts);
            double waitSeconds = Math.min(maxDelayMillis, 1000L << exponent) / 1000.0;
            double remaining = waitSeconds - account.sinceUpdateSeconds();
            if (remaining > 0) {
                return Decision.refuseFor(remaining);
            }
        }
        return Decision.OPEN;
    }

    /**
     * Records a failed attempt on all counters; locks the pair when it reaches the threshold. Plain
     * {@code REQUIRED}: the login path holds no transaction, so this is one short transaction on one pooled
     * connection - it used to be {@code REQUIRES_NEW} inside the login transaction, i.e. a second connection
     * requested while the first was held, which parallel failing logins turned into pool exhaustion (C4).
     */
    @Transactional
    public void recordFailure(String email, String clientIp) {
        String e = normalizeEmail(email);
        String ip = normalizeIp(clientIp);
        long tracked = trackedRows();
        boolean pairOk = tracked < maxTrackedKeys;
        boolean ipOk = tracked < maxTrackedKeys + maxTrackedKeys / 2;
        long windowSeconds = lockoutMinutes * 60;

        Integer pairCount = bump(pairKey(e, ip), "PAIR", windowSeconds, pairOk);
        if (pairCount != null && pairCount >= maxAttempts) {
            lockPair(pairKey(e, ip), e, ip);
        }
        bump("i|" + ip, "IP", windowSeconds, ipOk);
        bump("a|" + e, "ACCOUNT", windowSeconds, pairOk);
        bump(GLOBAL_KEY, "GLOBAL", GLOBAL_WINDOW_SECONDS, true);
        // Upper bound of the rows this failure may have created, so a burst inside the 5 s count cache
        // cannot overshoot the cap by much.
        cachedCount.addAndGet(3);
    }

    /** Clears the pair and account counters after a successful login (the IP counter is left alone). */
    @Transactional
    public void recordSuccess(String email, String clientIp) {
        String e = normalizeEmail(email);
        jdbc.update("DELETE FROM login_attempt WHERE login_key IN (?, ?)",
                pairKey(e, normalizeIp(clientIp)), "a|" + e);
    }

    private Integer bump(String key, String kind, long windowSeconds, boolean allowInsert) {
        String inWindow = "login_attempt.window_start > now() - make_interval(secs => ?)";
        String set = """
                attempt_count = CASE WHEN %1$s THEN login_attempt.attempt_count + 1 ELSE 1 END,
                window_start = CASE WHEN %1$s THEN login_attempt.window_start ELSE now() END,
                lock_episodes = CASE WHEN login_attempt.updated_at < now() - make_interval(secs => ?)
                                     THEN 0 ELSE login_attempt.lock_episodes END,
                updated_at = now()
                """.formatted(inWindow);
        double w = windowSeconds;
        double memory = EPISODE_MEMORY_SECONDS;
        if (allowInsert) {
            return jdbc.query("""
                    INSERT INTO login_attempt (login_key, kind, attempt_count, window_start, updated_at)
                    VALUES (?, ?, 1, now(), now())
                    ON CONFLICT (login_key) DO UPDATE SET
                    """ + set + " RETURNING attempt_count",
                    rs -> rs.next() ? rs.getInt(1) : null, key, kind, w, w, memory);
        }
        return jdbc.query("UPDATE login_attempt SET " + set.replace("login_attempt.", "")
                        + " WHERE login_key = ? RETURNING attempt_count",
                rs -> rs.next() ? rs.getInt(1) : null, w, w, memory, key);
    }

    private void lockPair(String pairKey, String email, String ip) {
        double base = lockoutMinutes * 60.0;
        double cap = maxLockoutMinutes * 60.0;
        Map<String, Object> row = jdbc.queryForMap("""
                UPDATE login_attempt
                SET locked_until = now() + make_interval(secs => LEAST(? * power(2, lock_episodes), ?)),
                    lock_episodes = lock_episodes + 1,
                    attempt_count = 0,
                    window_start = now()
                WHERE login_key = ?
                RETURNING locked_until, lock_episodes
                """, base, cap, pairKey);
        Instant lockedUntil = ((Timestamp) row.get("locked_until")).toInstant();
        events.publishEvent(new LoginLockedEvent(email, ip, ((Number) row.get("lock_episodes")).intValue(), lockedUntil));
    }

    /** Deletes expired rows (never an active lock); returns the number removed. */
    public int purgeStale() {
        double windowTtl = lockoutMinutes * 60.0 * 2;
        double memory = EPISODE_MEMORY_SECONDS;
        int total = 0;
        int n;
        do {
            n = jdbc.update("""
                    DELETE FROM login_attempt WHERE login_key IN (
                        SELECT login_key FROM login_attempt
                        WHERE (locked_until IS NULL OR locked_until < now())
                          AND updated_at < now() - make_interval(secs => CASE WHEN lock_episodes > 0 THEN ? ELSE ? END)
                        LIMIT 5000)
                    """, memory, windowTtl);
            total += n;
        } while (n >= 5000);
        cachedCountAt = 0;
        return total;
    }

    long trackedRows() {
        long now = System.currentTimeMillis();
        if (now - cachedCountAt > COUNT_CACHE_MILLIS) {
            Long c = jdbc.queryForObject("SELECT count(*) FROM login_attempt", Long.class);
            cachedCount.set(c == null ? 0 : c);
            cachedCountAt = now;
        }
        return cachedCount.get();
    }

    private static String pairKey(String email, String ip) {
        return "p|" + email + "|" + ip;
    }

    private static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return e.length() > 254 ? e.substring(0, 254) : e;
    }

    private static String normalizeIp(String ip) {
        String v = ip == null || ip.isBlank() ? "unknown" : ip.trim().toLowerCase(Locale.ROOT);
        return v.length() > 45 ? v.substring(0, 45) : v;
    }
}
