package de.makibytes.registerwerk.asset.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO for a live token holder discovered via on-chain Transfer event scanning.
 *
 * @param walletAddress Full EVM wallet address
 * @param balance Current balance on-chain (in smallest unit)
 * @param entityId UUID of the known investor/issuer in Registerwerk, or null if unknown
 * @param entityName Display name of the known entity, or null if unknown
 * @param known true if the holder is linked to a registered AssetHolder + LegalEntity
 * @param whitelisted true if the holder has been whitelisted for this asset
 */
public record LiveHolderResponse(
    String walletAddress,
    BigDecimal balance,
    UUID entityId,
    String entityName,
    boolean known,
    boolean whitelisted
) {}
