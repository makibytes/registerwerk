package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fail-closed §16 eWpG Sperrvermerk gate. A blocking block (ACTIVE, or EXPIRY_REVIEW after its
 * expiry date passed without a confirmed lift) on either the entity or the wallet address blocks
 * the operation — a block is only ever created against whichever of the two is known at the time
 * (e.g. a court order may name only a wallet address), so both must be checked regardless of which
 * identifiers the caller has on hand.
 *
 * <p>The wallet is compared in canonical form ({@link AddressNormalizer}): blocks are stored
 * normalised (V10 backfilled older rows), so a checksum-cased caller argument still matches.
 */
@Component
class HolderBlockGateImpl implements HolderBlockGate {

    private final HolderBlockRepository holderBlockRepository;
    private final AssetHolderRepository holderRepository;

    HolderBlockGateImpl(HolderBlockRepository holderBlockRepository, AssetHolderRepository holderRepository) {
        this.holderBlockRepository = holderBlockRepository;
        this.holderRepository = holderRepository;
    }

    @Override
    public boolean isBlocked(UUID entityId, String walletAddress) {
        if (entityId != null && !holderBlockRepository.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING).isEmpty()) {
            return true;
        }
        if (walletAddress != null && !holderBlockRepository.findByWalletAddressAndStatusIn(
                AddressNormalizer.normalize(walletAddress), HolderBlock.BLOCKING).isEmpty()) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isEntityBlocked(UUID entityId) {
        if (entityId == null) {
            return false;
        }
        if (isBlocked(entityId, null)) {
            return true;
        }
        for (AssetHolder holder : holderRepository.findActiveByInvestorId(entityId)) {
            String wallet = holder.getWalletAddress();
            if (wallet != null && isBlocked(null, wallet)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBlockedForAsset(String walletAddress, UUID assetId) {
        if (walletAddress == null) {
            return false;
        }
        String wallet = AddressNormalizer.normalize(walletAddress);
        if (covers(holderBlockRepository.findByWalletAddressAndStatusIn(wallet, HolderBlock.BLOCKING), assetId)) {
            return true;
        }
        // An entity-scoped block covers every wallet the entity holds units on.
        return holderRepository.findByWalletAddressIn(List.of(wallet)).stream()
                .map(AssetHolder::getInvestorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .anyMatch(entity -> covers(
                        holderBlockRepository.findByEntityIdAndStatusIn(entity, HolderBlock.BLOCKING), assetId));
    }

    private static boolean covers(List<HolderBlock> blocks, UUID assetId) {
        return blocks.stream().anyMatch(b -> b.getAssetId() == null || assetId == null || b.getAssetId().equals(assetId));
    }
}
