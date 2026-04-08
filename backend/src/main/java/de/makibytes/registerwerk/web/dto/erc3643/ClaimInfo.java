package de.makibytes.registerwerk.web.dto.erc3643;

import java.time.Instant;

/**
 * Concise view of a single on-chain claim, embedded in identity responses.
 */
public record ClaimInfo(
    long topic,
    String topicLabel,
    String issuerAddress,
    Instant issuedAt,
    Instant expiresAt,
    boolean isRevoked,
    /** Hex-encoded ABI-encoded claim data bytes (null if not stored). */
    String claimData,
    /** Hex-encoded ECDSA signature of the issuer (null if not stored). */
    String claimSignature
) {}
