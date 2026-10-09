package de.makibytes.registerwerk.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Standard error response body returned by the global exception handler.
 *
 * <p>{@code code} is an optional machine-readable discriminator (for example
 * {@code STEP_UP_ENROLMENT_REQUIRED}) for the few refusals a client has to tell apart; it is omitted
 * from the JSON when null, so ordinary errors keep their four-field shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    int status,
    String message,
    Instant timestamp,
    String path,
    String code
) {
    public ErrorResponse(int status, String message, Instant timestamp, String path) {
        this(status, message, timestamp, path, null);
    }
}
