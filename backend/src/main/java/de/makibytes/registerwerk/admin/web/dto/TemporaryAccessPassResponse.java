package de.makibytes.registerwerk.admin.web.dto;

import java.time.Instant;

/**
 * A freshly issued Temporary Access Pass.
 *
 * <p>{@code value} is returned exactly once, here. Microsoft Graph will not return it again, and
 * Registerwerk deliberately does not store it: it is a bearer credential that authenticates as
 * the target user. The operator must deliver it out-of-band immediately. The response is marked
 * {@code Cache-Control: no-store} for the same reason.
 */
public record TemporaryAccessPassResponse(
        String id,
        String value,
        Instant startAt,
        Instant expiresAt,
        int lifetimeMinutes,
        boolean usableOnce) {
}
