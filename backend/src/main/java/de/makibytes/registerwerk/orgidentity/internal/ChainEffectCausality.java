package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.BlockIdentity;

import java.util.Objects;
import java.util.UUID;

/** Exact forward-effect ownership check used by org-identity inverse compensators. */
final class ChainEffectCausality {

    private ChainEffectCausality() {
    }

    static boolean matches(ChainEffectRecord effect, UUID chainConfigId, String txHash,
            Long blockNumber, String blockHash) {
        return Objects.equals(chainConfigId, effect.chainConfigId())
                && blockNumber != null && blockNumber == effect.blockNumber()
                && txHash != null && effect.txHash() != null && BlockIdentity.sameHash(txHash, effect.txHash())
                && blockHash != null && effect.blockHash() != null
                && BlockIdentity.sameHash(blockHash, effect.blockHash());
    }
}
