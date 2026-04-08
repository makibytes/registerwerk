package de.makibytes.registerwerk.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a single on-chain token transfer event.
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
        String chainIdentifier
) {}
