package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fail-closed §16 eWpG Sperrvermerk gate. An ACTIVE block on either the entity or the
 * wallet address blocks the operation — a block is only ever created against whichever
 * of the two is known at the time (e.g. a court order may name only a wallet address),
 * so both must be checked regardless of which identifiers the caller has on hand.
 */
@Component
class HolderBlockGateImpl implements HolderBlockGate {

    private final HolderBlockRepository holderBlockRepository;

    HolderBlockGateImpl(HolderBlockRepository holderBlockRepository) {
        this.holderBlockRepository = holderBlockRepository;
    }

    @Override
    public boolean isBlocked(UUID entityId, String walletAddress) {
        if (entityId != null && !holderBlockRepository.findByEntityIdAndStatus(entityId, HolderBlock.Status.ACTIVE).isEmpty()) {
            return true;
        }
        if (walletAddress != null && !holderBlockRepository.findByWalletAddressAndStatus(walletAddress, HolderBlock.Status.ACTIVE).isEmpty()) {
            return true;
        }
        return false;
    }
}
