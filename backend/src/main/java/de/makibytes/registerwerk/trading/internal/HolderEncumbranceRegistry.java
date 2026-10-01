package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.HolderEncumbranceSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sums every {@link HolderEncumbranceSource} (the repo desk contributes one; none exist until it
 * does, in which case nothing is encumbered). Summing is conservative: an entity holding one asset
 * in several wallets sees the full encumbrance against each holder row.
 */
@Component
class HolderEncumbranceRegistry {

    private final ObjectProvider<HolderEncumbranceSource> sources;

    HolderEncumbranceRegistry(ObjectProvider<HolderEncumbranceSource> sources) {
        this.sources = sources;
    }

    BigDecimal encumbered(UUID entityId, UUID assetId) {
        BigDecimal total = BigDecimal.ZERO;
        for (HolderEncumbranceSource source : (Iterable<HolderEncumbranceSource>) sources.orderedStream()::iterator) {
            BigDecimal part = source.encumbered(entityId, assetId);
            if (part != null && part.signum() > 0) {
                total = total.add(part);
            }
        }
        return total;
    }
}
