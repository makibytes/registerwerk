package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigInteger;
import java.util.Map;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/slots}.
 *
 * @param slotId     On-chain slot identifier (uint256 serialized as string to avoid integer overflow).
 * @param name       Human-readable slot name (e.g. "Bond Series A — 5% 2030").
 * @param metadata   Off-chain coupon/maturity/tranche metadata stored in JSONB.
 * @param supplyCap  Maximum total value mintable in this slot (0 = unlimited).
 */
public record CreateSlotRequest(
        @NotNull
        BigInteger slotId,

        String name,

        Map<String, Object> metadata,

        BigInteger supplyCap
) {}
