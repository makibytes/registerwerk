package de.makibytes.registerwerk.application.asset;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.customer.EntityNumberGenerator;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * CRUD service for digital assets.
 */
@Service
@Transactional
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetRepository assetRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final EntityNumberGenerator entityNumberGenerator;

    public AssetService(
            AssetRepository assetRepository,
            AuditEventPublisher auditEventPublisher,
            EntityNumberGenerator entityNumberGenerator) {
        this.assetRepository = assetRepository;
        this.auditEventPublisher = auditEventPublisher;
        this.entityNumberGenerator = entityNumberGenerator;
    }

    public Asset createAsset(Asset asset, UUID actorId) {
        asset.setAssetNumber(entityNumberGenerator.generateAssetNumber());
        asset.setStatus(AssetStatus.DRAFT);
        Asset saved = assetRepository.save(asset);
        auditEventPublisher.publish("ASSET_CREATED", "Asset", saved.getId(), actorId, null,
            Map.of("assetNumber", saved.getAssetNumber(), "name", saved.getName()));
        log.info("Created asset: id={}, number={}", saved.getId(), saved.getAssetNumber());
        return saved;
    }

    @Transactional(readOnly = true)
    public Asset getAsset(UUID id) {
        return assetRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Asset", id));
    }

    @Transactional(readOnly = true)
    public Page<Asset> listAssets(UUID issuerId, AssetStatus status, Pageable pageable) {
        if (issuerId != null && status != null) {
            return assetRepository.findByIssuerIdAndStatus(issuerId, status, pageable);
        } else if (issuerId != null) {
            return assetRepository.findByIssuerId(issuerId, pageable);
        } else if (status != null) {
            return assetRepository.findByStatus(status, pageable);
        }
        return assetRepository.findAll(pageable);
    }

    public Asset updateAsset(UUID id, Asset patch) {
        Asset existing = getAsset(id);
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getIsin() != null) existing.setIsin(patch.getIsin());
        if (patch.getPublicData() != null) existing.setPublicData(patch.getPublicData());
        if (patch.getJurisdiction() != null) existing.setJurisdiction(patch.getJurisdiction());
        Asset saved = assetRepository.save(existing);
        auditEventPublisher.publish("ASSET_UPDATED", "Asset", id, null, null, null);
        return saved;
    }

    public Asset patchAsset(UUID id, Asset patch) {
        return updateAsset(id, patch);
    }
}
