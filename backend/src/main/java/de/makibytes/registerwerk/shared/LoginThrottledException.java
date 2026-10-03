package de.makibytes.registerwerk.shared;

/**
 * A login attempt refused by the brute-force throttle. Mapped to HTTP 429 with a {@code Retry-After}
 * header. It is raised for known and unknown accounts alike (the throttle keys on the e-mail and the client
 * address, never on whether the account exists), so it reveals nothing about which e-mails are registered.
 */
public class LoginThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginThrottledException(long retryAfterSeconds) {
        super("Too many login attempts. Try again later.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Whole seconds, at least 1, after which the same attempt may be made again. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
