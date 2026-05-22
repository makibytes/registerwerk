package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements deployment module's AssetLookupPort so that blockchain/externalref services
 * can query asset business data without importing the Asset entity directly.
 */
@Component
class AssetLookupPortImpl implements AssetLookupPort {

    private final AssetRepository assetRepository;

    AssetLookupPortImpl(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @Override
    public Optional<AssetInfo> findById(UUID assetId) {
        return assetRepository.findById(assetId).map(this::toInfo);
    }

    @Override
    public List<AssetInfo> findAll() {
        return assetRepository.findAll().stream().map(this::toInfo).toList();
    }

    @Override
    public List<AssetInfo> findByIssuerId(UUID issuerId) {
        return assetRepository.findByIssuerId(issuerId).stream().map(this::toInfo).toList();
    }

    private AssetInfo toInfo(Asset a) {
        return new AssetInfo(
                a.getId(), a.getName(), a.getIsin(), a.getTokenStandard(),
                a.getChain() != null ? a.getChain().name() : null,
                a.getNetwork() != null ? a.getNetwork().name() : null,
                a.getIssuerId(), a.getAssetNumber(),
                a.getStatus() != null ? a.getStatus().name() : null);
    }
}
