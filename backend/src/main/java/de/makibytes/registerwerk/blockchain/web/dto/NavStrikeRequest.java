package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/nav-strike}.
 *
 * @param navPerShare  Net-asset-value per share (positive). Scale: 1.0 = par value.
 * @param effectiveAt  Timestamp when this NAV becomes effective (ISO-8601).
 * @param reportDocId  ID of the uploaded NAV attestation document (optional).
 */
public record NavStrikeRequest(
        @NotNull @Positive
        BigDecimal navPerShare,

        @NotNull
        Instant effectiveAt,

        UUID reportDocId
) {}
