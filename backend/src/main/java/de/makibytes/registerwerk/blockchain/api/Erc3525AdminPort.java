package de.makibytes.registerwerk.blockchain.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Public port for ERC-3525 semi-fungible token admin operations. Used by asset/web/Erc3525SlotController.
 *
 * <p>Every state-mutating method takes {@code actorId}/{@code actorRole}: actions are submitted
 * on-chain and tracked via {@code BlockchainTransactionService}, and publish a
 * {@code TokenAdminActionEvent} so they reach the audit trail.
 */
public interface Erc3525AdminPort {
    UUID createSlot(UUID deploymentId, BigInteger slotId, String name,
                    Map<String, Object> metadata, BigInteger supplyCap, UUID actorId, String actorRole);
    UUID pauseSlot(UUID deploymentId, BigInteger slotId, UUID actorId, String actorRole);
    UUID unpauseSlot(UUID deploymentId, BigInteger slotId, UUID actorId, String actorRole);
    UUID setSlotSupplyCap(UUID deploymentId, BigInteger slotId, BigInteger cap, UUID actorId, String actorRole);
    UUID setSlotMetadataHash(UUID deploymentId, BigInteger slotId, byte[] metadataHash, UUID actorId, String actorRole);
    UUID mintIntoSlot(UUID deploymentId, BigInteger slotId, String toAddress, BigInteger value,
                      UUID actorId, String actorRole);
    UUID freezeToken(UUID deploymentId, BigInteger tokenId, String reason, UUID actorId, String actorRole);
    UUID unfreezeToken(UUID deploymentId, BigInteger tokenId, UUID actorId, String actorRole);
    UUID forcedValueTransfer(UUID deploymentId, BigInteger fromTokenId, BigInteger toTokenId,
                             BigInteger value, String legalBasis, UUID actorId, String actorRole);
    UUID forceBurnValue(UUID deploymentId, BigInteger tokenId, BigInteger value, String legalBasis,
                        UUID actorId, String actorRole);
    void recordCouponPayment(UUID assetId, BigInteger slotId, int periodNo,
                             LocalDate scheduledDate, LocalDate paidDate,
                             BigDecimal amountPerUnit, String txRef);
}
