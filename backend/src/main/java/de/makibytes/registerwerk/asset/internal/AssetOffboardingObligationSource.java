package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** An entity that is still the issuer of a live security cannot be closed without a conscious decision (6-23). */
@Component
class AssetOffboardingObligationSource implements OffboardingObligationSource {

    static final String KIND = "ISSUER_ASSET_LIVE";

    private final AssetRepository assetRepository;

    AssetOffboardingObligationSource(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        return assetRepository.findByIssuerId(entityId).stream()
                .filter(a -> a.getStatus() == AssetStatus.ISSUED || a.getStatus() == AssetStatus.SUSPENDED
                        || a.getStatus() == AssetStatus.TRANSFER_PENDING)
                .map(AssetOffboardingObligationSource::toObligation)
                .toList();
    }

    private static OffboardingObligation toObligation(Asset a) {
        return new OffboardingObligation(KIND, a.getId().toString(),
                "Issuer of " + a.getStatus() + " asset " + a.getId() + " (register transfer to a successor or redemption is open)");
    }
}
