package de.makibytes.registerwerk.blockchain.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Public port for ERC-3525 semi-fungible token admin operations. Used by asset/web/Erc3525SlotController. */
public interface Erc3525AdminPort {
    UUID createSlot(UUID deploymentId, BigInteger slotId, String name,
                    Map<String, Object> metadata, BigInteger supplyCap);
    UUID pauseSlot(UUID deploymentId, BigInteger slotId);
    UUID unpauseSlot(UUID deploymentId, BigInteger slotId);
    UUID setSlotSupplyCap(UUID deploymentId, BigInteger slotId, BigInteger cap);
    UUID setSlotMetadataHash(UUID deploymentId, BigInteger slotId, byte[] metadataHash);
    UUID mintIntoSlot(UUID deploymentId, BigInteger slotId, String toAddress, BigInteger value);
    UUID freezeToken(UUID deploymentId, BigInteger tokenId, String reason);
    UUID unfreezeToken(UUID deploymentId, BigInteger tokenId);
    UUID forcedValueTransfer(UUID deploymentId, BigInteger fromTokenId, BigInteger toTokenId,
                             BigInteger value, String legalBasis);
    UUID forceBurnValue(UUID deploymentId, BigInteger tokenId, BigInteger value, String legalBasis);
    void recordCouponPayment(UUID assetId, BigInteger slotId, int periodNo,
                             LocalDate scheduledDate, LocalDate paidDate,
                             BigDecimal amountPerUnit, String txRef);
}
