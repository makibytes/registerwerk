package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** An active Sperrvermerk on the entity: positions are legally frozen and cannot simply be migrated away (6-23). */
@Component
class HolderBlockOffboardingObligationSource implements OffboardingObligationSource {

    private final HolderBlockRepository blocks;

    HolderBlockOffboardingObligationSource(HolderBlockRepository blocks) {
        this.blocks = blocks;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        return blocks.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING).stream()
                .map(b -> new OffboardingObligation("SPERRVERMERK_HOLDING", b.getId().toString(),
                        "Active Sperrvermerk " + b.getId() + (b.getAssetId() != null ? " on asset " + b.getAssetId() : "")))
                .toList();
    }
}
