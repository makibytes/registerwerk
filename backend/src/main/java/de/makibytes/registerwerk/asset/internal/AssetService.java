package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.customer.CustomerApi;
import de.makibytes.registerwerk.asset.events.AssetCreatedEvent;
import de.makibytes.registerwerk.asset.events.AssetUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * CRUD service for digital assets.
 */
@Service
@Transactional
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerApi customerApi;

    public AssetService(
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            CustomerApi customerApi) {
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.customerApi = customerApi;
    }

    public Asset createAsset(Asset asset, UUID actorId) {
        validateCreate(asset);
        asset.setAssetNumber(customerApi.nextEntityNumber());
        asset.setStatus(AssetStatus.DRAFT);
        Asset saved = assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetCreatedEvent(saved.getId(), actorId, null, saved.getAssetNumber(), saved.getName()));
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

    public Asset updateAsset(UUID id, Asset patch, UUID actorId) {
        Asset existing = getAsset(id);
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getIsin() != null) {
            existing.setIsin(patch.getIsin().isBlank()
                    ? null
                    : de.makibytes.registerwerk.shared.IsinValidator.validateOrThrow(patch.getIsin()));
        }
        if (patch.getPublicData() != null) existing.setPublicData(patch.getPublicData());
        if (patch.getJurisdiction() != null) existing.setJurisdiction(patch.getJurisdiction());
        if (patch.getChain() != null) existing.setChain(patch.getChain());
        if (patch.getNetwork() != null) existing.setNetwork(patch.getNetwork());
        if (patch.getCurrency() != null) existing.setCurrency(patch.getCurrency());
        if (patch.getIssueSize() != null) existing.setIssueSize(patch.getIssueSize());
        if (patch.getDenomination() != null) existing.setDenomination(patch.getDenomination());
        if (patch.getIssueDate() != null) existing.setIssueDate(patch.getIssueDate());
        if (patch.getMaturityDate() != null) existing.setMaturityDate(patch.getMaturityDate());
        if (patch.getMinInvestmentAmount() != null) existing.setMinInvestmentAmount(patch.getMinInvestmentAmount());
        if (patch.getMaxHoldingAmount() != null) existing.setMaxHoldingAmount(patch.getMaxHoldingAmount());
        Asset saved = assetRepository.save(existing);
        eventPublisher.publishEvent(new AssetUpdatedEvent(id, actorId, null));
        return saved;
    }

    /**
     * Sets the asset's MiFID II target market (product governance) — an explicit replace, not a
     * merge, since "which categories may buy this" is a single coherent decision, unlike the
     * other patchable fields on {@link #updateAsset}. An empty {@code categories} set means
     * unrestricted (see {@link Asset#isEligibleForTargetMarket}).
     */
    public Asset updateTargetMarket(UUID id, java.util.Set<de.makibytes.registerwerk.customer.api.ClientCategory> categories,
                                     de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel minExperience, UUID actorId) {
        Asset existing = getAsset(id);
        existing.setTargetMarketCategories(categories);
        existing.setTargetMarketMinExperience(minExperience);
        Asset saved = assetRepository.save(existing);
        eventPublisher.publishEvent(new AssetUpdatedEvent(id, actorId, null));
        log.info("Updated target market for asset: id={} categories={} minExperience={}", id, categories, minExperience);
        return saved;
    }

    private void validateCreate(Asset asset) {
        if (asset.getIssuerId() == null) {
            throw new IllegalArgumentException("issuerId is required to create an asset");
        }
        if (asset.getIsin() != null && !asset.getIsin().isBlank()) {
            // A malformed ISIN would flow into MiFIR RTS 22 filings — reject at the door.
            asset.setIsin(de.makibytes.registerwerk.shared.IsinValidator.validateOrThrow(asset.getIsin()));
        }

        boolean hasEitherDeploymentField = asset.getChain() != null || asset.getNetwork() != null;
        boolean hasBothDeploymentFields = asset.getChain() != null && asset.getNetwork() != null;

        if (hasEitherDeploymentField && !hasBothDeploymentFields) {
            throw new IllegalArgumentException("chain and network must both be provided together");
        }

        if (asset.getOnchainLevel() != OnchainLevel.NONE && !hasBothDeploymentFields) {
            throw new IllegalArgumentException(
                    "chain and network are required when onchainLevel is not NONE");
        }
    }
}
