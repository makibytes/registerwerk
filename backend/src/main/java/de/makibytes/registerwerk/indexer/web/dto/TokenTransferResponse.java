package de.makibytes.registerwerk.indexer.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a single on-chain token transfer event.
 *
 * @param finalityStatus the raw {@code FinalityLevel} name — always technical, stable across
 *                       roles (kept as-is so a badge component can key its color/icon off it, and
 *                       so technical/audit consumers get an unambiguous machine value)
 * @param finalityLabel  the display text — resolved server-side by the caller's role (see
 *                       {@code TokenTransferMapper}): the same value as {@code finalityStatus} for
 *                       operator staff, plain language ("Being confirmed", "Confirmed", ...) for
 *                       customer-side roles, so the frontend never chooses the vocabulary itself
 */
public record TokenTransferResponse(
        UUID id,
        String contractAddress,
        String fromAddress,
        String toAddress,
        BigDecimal tokenId,
        BigDecimal amount,
        String eventType,
        String txHash,
        Long blockNumber,
        Instant occurredAt,
        String explorerTxUrl,
        String chainIdentifier,
        String finalityStatus,
        String finalityLabel
) {}
