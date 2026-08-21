package de.makibytes.registerwerk.finality.web.dto;

import java.time.Instant;

/** A superset of {@code shared.api.ErrorResponse} — same first four fields (name and position),
 *  so an existing frontend interceptor reading only those keeps working unmodified, plus the
 *  structured decision data a "why is this blocked, and roughly when will it not be" UI needs. */
public record FinalityErrorResponse(
        int status,
        String message,
        Instant timestamp,
        String path,
        String operation,
        String requiredLevel,
        String currentLevel,
        String reason) {
}
