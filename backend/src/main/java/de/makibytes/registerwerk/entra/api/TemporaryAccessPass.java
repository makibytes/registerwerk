package de.makibytes.registerwerk.entra.api;

import java.time.Instant;

/**
 * A freshly issued Temporary Access Pass — the credential an operator hands a customer who
 * has lost their authenticator, so they can sign in once and re-register a method.
 *
 * <p><strong>The {@code value} is a bearer credential that fully authenticates as the target
 * user.</strong> Graph returns it only on creation; a later GET returns null. It must never be
 * persisted, logged, or put into an audit payload. {@link #toString()} is overridden to redact
 * it, because the single most likely way to leak it is an incidental
 * {@code log.debug("… {}", tap)}.
 *
 * @param id               Graph method id — safe to log and audit
 * @param value            the pass itself — never log, never store
 * @param startAt          when it becomes usable
 * @param expiresAt        derived from {@code startAt + lifetimeInMinutes}
 * @param lifetimeMinutes  configured lifetime
 * @param usableOnce       whether it is single-use
 */
public record TemporaryAccessPass(
        String id,
        String value,
        Instant startAt,
        Instant expiresAt,
        int lifetimeMinutes,
        boolean usableOnce) {

    private static final String REDACTED = "***REDACTED***";

    @Override
    public String toString() {
        return "TemporaryAccessPass[id=" + id
                + ", value=" + REDACTED
                + ", startAt=" + startAt
                + ", expiresAt=" + expiresAt
                + ", lifetimeMinutes=" + lifetimeMinutes
                + ", usableOnce=" + usableOnce + "]";
    }
}
