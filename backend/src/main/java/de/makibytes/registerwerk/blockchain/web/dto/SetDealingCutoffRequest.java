package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/dealing-cutoff} (T1-07 forward pricing).
 *
 * @param cutoffSecondsOfDay daily cut-off as UTC seconds since midnight (0-86399); 61200 = 17:00 UTC
 * @param periodSeconds      dealing period in seconds (86400 = daily; 3600 to 31 days accepted)
 */
public record SetDealingCutoffRequest(
        @NotNull @Min(0) @Max(86_399) Integer cutoffSecondsOfDay,
        @NotNull @Min(3_600) @Max(2_678_400) Long periodSeconds
) {}
