package de.makibytes.registerwerk.erc3643.web.dto;

import de.makibytes.registerwerk.shared.api.AsyncDataStatus;

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
    AsyncDataStatus syncStatus,
    boolean active,
    boolean verified,
    String externalId,
    String legalEntityExternalId
) {}
