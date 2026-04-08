package de.makibytes.registerwerk.web.dto.erc3643;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single entry in the ERC-3643 IdentityRegistry mirror.
 * Combines the registry mapping with identity and entity metadata.
 */
public record IdentityRegistryEntryResponse(
    UUID id,
    UUID suiteId,
    String walletAddress,
    UUID onchainIdentityId,
    String identityAddress,
    UUID legalEntityId,
    String entityName,
    Short countryCode,
    Instant registeredAt,
    String registeredByTx,
    boolean active,
    boolean verified
) {}
