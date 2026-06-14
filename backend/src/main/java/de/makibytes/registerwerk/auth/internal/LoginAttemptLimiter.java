package de.makibytes.registerwerk.auth.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory brute-force throttle for the built-in HS256 login.
 *
 * <p>The operator frontend bypasses Kong (see CLAUDE.md), so the Kong
 * rate-limiting plugin does not protect {@code POST /api/v1/public/auth/login}.
 * This limiter locks an account key after {@code maxAttempts} consecutive
 * failures within the sliding window; the counter resets on successful login
 * or after the window expires.
 *
 * <p>Note: per-instance state. For multi-replica deployments, complement with
 * gateway-level rate limiting or a shared store.
 */
@Component
public class LoginAttemptLimiter {

    private final int maxAttempts;
    private final Cache<String, AtomicInteger> attempts;

    public LoginAttemptLimiter(
            @Value("${registerwerk.auth.login-max-attempts:5}") int maxAttempts,
            @Value("${registerwerk.auth.login-lockout-minutes:15}") long lockoutMinutes) {
        this.maxAttempts = maxAttempts;
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(lockoutMinutes))
                .maximumSize(100_000)
                .build();
    }

    /** Returns true if the key is currently locked out. */
    public boolean isBlocked(String key) {
        AtomicInteger counter = attempts.getIfPresent(normalize(key));
        return counter != null && counter.get() >= maxAttempts;
    }

    /** Records a failed attempt for the key. */
    public void recordFailure(String key) {
        attempts.get(normalize(key), k -> new AtomicInteger()).incrementAndGet();
    }

    /** Clears the counter after a successful login. */
    public void recordSuccess(String key) {
        attempts.invalidate(normalize(key));
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
