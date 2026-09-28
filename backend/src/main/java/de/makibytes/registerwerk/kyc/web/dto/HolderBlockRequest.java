package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record HolderBlockRequest(
        UUID entityId,
        UUID assetId,
        @NotBlank String walletAddress,
        @NotNull HolderBlock.BlockType blockType,
        @NotBlank String legalBasis,
        String courtRef,
        UUID documentId,
        Instant expiresAt
) {
    public HolderBlock toEntity() {
        HolderBlock b = new HolderBlock();
        b.setEntityId(entityId);
        b.setAssetId(assetId);
        // Stored in the register's canonical form (0x addresses lowercased) — the gate, the
        // on-chain sync listener and asset_holder all compare exact strings (T3-15).
        b.setWalletAddress(AddressNormalizer.normalize(walletAddress));
        b.setBlockType(blockType);
        b.setLegalBasis(legalBasis);
        b.setCourtRef(courtRef);
        b.setDocumentId(documentId);
        b.setExpiresAt(expiresAt);
        return b;
    }
}
