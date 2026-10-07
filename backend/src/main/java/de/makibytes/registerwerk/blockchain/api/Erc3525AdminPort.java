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
    /** Holder-level controls (EwpgCompliance on EVM, the compliant Cairo class on Starknet). */
    UUID whitelistAddress(UUID deploymentId, String address, UUID actorId, String actorRole);
    UUID unwhitelistAddress(UUID deploymentId, String address, UUID actorId, String actorRole);
    UUID freezeAddress(UUID deploymentId, String address, String reason, UUID actorId, String actorRole);
    UUID unfreezeAddress(UUID deploymentId, String address, UUID actorId, String actorRole);
    /**
     * Lifts the freeze of a wallet after its §16 eWpG Sperrvermerk was lifted. For the Sperrvermerk sync listener
     * only: SYSTEM actor, and no block check (the manual {@link #unfreezeAddress} refuses under an ACTIVE block);
     * the listener has already verified that no other blocking block covers the wallet.
     */
    UUID unfreezeAfterBlockLift(UUID deploymentId, String address);
    UUID forceBurnValue(UUID deploymentId, BigInteger tokenId, BigInteger value, String legalBasis,
                        UUID actorId, String actorRole);
    void recordCouponPayment(UUID assetId, BigInteger slotId, int periodNo,
                             LocalDate scheduledDate, LocalDate paidDate,
                             BigDecimal amountPerUnit, String txRef);
}
