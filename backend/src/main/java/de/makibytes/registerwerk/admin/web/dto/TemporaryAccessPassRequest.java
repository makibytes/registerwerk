package de.makibytes.registerwerk.admin.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * @param lifetimeMinutes must also fit the tenant's Temporary Access Pass policy; the bounds
 *                        here are Entra's absolute limits (10 minutes to 30 days)
 * @param usableOnce      a single-use pass requires the user to finish registering within
 *                        10 minutes of signing in
 */
public record TemporaryAccessPassRequest(
        @Min(10) @Max(43200) int lifetimeMinutes,
        boolean usableOnce) {
}
