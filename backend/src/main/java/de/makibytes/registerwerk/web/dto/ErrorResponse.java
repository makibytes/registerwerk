package de.makibytes.registerwerk.web.dto;

import java.time.Instant;

/**
 * Standard error response body returned by the global exception handler.
 */
public record ErrorResponse(
    int status,
    String message,
    Instant timestamp,
    String path
) {}
