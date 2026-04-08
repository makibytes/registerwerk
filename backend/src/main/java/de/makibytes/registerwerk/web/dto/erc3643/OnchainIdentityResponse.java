package de.makibytes.registerwerk.web.dto.erc3643;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for an ONCHAINID identity, including its active claims.
 */
public record OnchainIdentityResponse(
    UUID id,
    UUID legalEntityId,
    /** Stable chain identifier (e.g. "ETHEREUM_MAINNET") resolved from the chain config. */
    String chainIdentifier,
    String identityAddress,
    Instant deployedAt,
    List<ClaimInfo> activeClaims
) {}
