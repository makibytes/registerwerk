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
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    // Evicted by every writer of this asset's row: updateAsset/updateTargetMarket below, and all
    // seven status-transition methods in AssetLifecycleService (submit/approve/reject/issue/
    // suspend/reactivate/redeem) - a miss there would let stale status/name/terms serve for up to
    // this cache's 30s TTL (see CacheConfig).
    @Cacheable(value = "assets", key = "#id")
    @Transactional(readOnly = true)
    public Asset getAsset(UUID id) {
        return assetRepository.findById(id)
            .map(this::initializeTargetMarketCategories)
            .orElseThrow(() -> new EntityNotFoundException("Asset", id));
    }

    /** Upper bound for the free-text {@code search} term of {@link #listAssets}. */
    static final int MAX_SEARCH_LENGTH = 200;

    /**
     * Returns a filtered, paginated list of assets. Every filter is optional and they combine with
     * AND; {@code search} (trimmed) matches the asset name, ISIN and asset number
     * case-insensitively anywhere in the value, and LIKE wildcards in the term are literals.
     *
     * @throws IllegalArgumentException if {@code search} is longer than {@value #MAX_SEARCH_LENGTH}
     *                                  characters (mapped to 400)
     */
    @Transactional(readOnly = true)
    public Page<Asset> listAssets(UUID issuerId, AssetStatus status, TokenStandard tokenStandard,
                                  String search, Pageable pageable) {
        String term = search == null ? "" : search.trim();
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        Page<Asset> assets = assetRepository.findAll(assetFilter(issuerId, status, tokenStandard, term), pageable);
        // The web layer maps the returned entities after this transaction closes
        // (spring.jpa.open-in-view=false). Initialize every relationship that AssetResponse
        // needs here rather than letting a controller or JSON mapper trigger lazy I/O later.
        return assets.map(this::initializeTargetMarketCategories);
    }

    private static Specification<Asset> assetFilter(UUID issuerId, AssetStatus status,
                                                    TokenStandard tokenStandard, String term) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (issuerId != null) {
                predicates.add(cb.equal(root.get("issuerId"), issuerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (tokenStandard != null) {
                predicates.add(cb.equal(root.get("tokenStandard"), tokenStandard));
            }
            if (!term.isEmpty()) {
                String like = "%" + term.toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(root.get("isin")), like, '\\'),
                        cb.like(cb.lower(root.get("assetNumber")), like, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Asset initializeTargetMarketCategories(Asset asset) {
        Hibernate.initialize(asset.getTargetMarketCategories());
        return asset;
    }

    @CacheEvict(value = "assets", key = "#id")
    public Asset updateAsset(UUID id, Asset patch, UUID actorId) {
        Asset existing = getAsset(id);
        requireEconomicTermsUnchanged(existing, patch);
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

    /** Statuses in which ISIN, currency, size, denomination and dates are fixed (approved terms). */
    private static final java.util.Set<AssetStatus> TERMS_LOCKED = java.util.EnumSet.of(
            AssetStatus.APPROVED, AssetStatus.ISSUED, AssetStatus.SUSPENDED,
            AssetStatus.REDEMPTION_PENDING, AssetStatus.REDEEMED, AssetStatus.TRANSFER_PENDING,
            AssetStatus.TRANSFERRED_OUT);
    /** Min. investment / max. holding stay editable up to issuance (subscription set-up), not after. */
    private static final java.util.Set<AssetStatus> INVESTMENT_LIMITS_LOCKED = java.util.EnumSet.of(
            AssetStatus.ISSUED, AssetStatus.SUSPENDED, AssetStatus.REDEMPTION_PENDING, AssetStatus.REDEEMED,
            AssetStatus.TRANSFER_PENDING, AssetStatus.TRANSFERRED_OUT);

    /**
     * T3-10: once approved, the economic terms investors rely on can no longer be edited through
     * the plain PATCH — neither by the issuer nor by a single operator. They change only through
     * the step-up + second-approver amendment ({@code POST /assets/{id}/terms-amendments}), which
     * audits before/after values. Fields sent unchanged (full-form PUTs) are accepted.
     */
    private static void requireEconomicTermsUnchanged(Asset existing, Asset patch) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (TERMS_LOCKED.contains(existing.getStatus())) {
            if (patch.getIsin() != null && !java.util.Objects.equals(
                    patch.getIsin().isBlank() ? null : patch.getIsin().trim().toUpperCase(java.util.Locale.ROOT),
                    existing.getIsin())) changed.add("isin");
            if (patch.getCurrency() != null && !patch.getCurrency().equalsIgnoreCase(
                    java.util.Objects.requireNonNullElse(existing.getCurrency(), ""))) changed.add("currency");
            if (differs(patch.getIssueSize(), existing.getIssueSize())) changed.add("issueSize");
            if (differs(patch.getDenomination(), existing.getDenomination())) changed.add("denomination");
            if (patch.getIssueDate() != null && !patch.getIssueDate().equals(existing.getIssueDate())) changed.add("issueDate");
            if (patch.getMaturityDate() != null && !patch.getMaturityDate().equals(existing.getMaturityDate())) changed.add("maturityDate");
        }
        if (INVESTMENT_LIMITS_LOCKED.contains(existing.getStatus())) {
            if (differs(patch.getMinInvestmentAmount(), existing.getMinInvestmentAmount())) changed.add("minInvestmentAmount");
            if (differs(patch.getMaxHoldingAmount(), existing.getMaxHoldingAmount())) changed.add("maxHoldingAmount");
        }
        if (!changed.isEmpty()) {
            throw new IllegalArgumentException("Economic terms locked after approval (" + String.join(", ", changed)
                    + "); request an amendment via POST /api/v1/assets/" + existing.getId() + "/terms-amendments");
        }
    }

    private static boolean differs(java.math.BigDecimal patched, java.math.BigDecimal current) {
        return patched != null && (current == null || patched.compareTo(current) != 0);
    }

    /**
     * Sets the asset's MiFID II target market (product governance) — an explicit replace, not a
     * merge, since "which categories may buy this" is a single coherent decision, unlike the
     * other patchable fields on {@link #updateAsset}. An empty {@code categories} set means
     * unrestricted (see {@link Asset#isEligibleForTargetMarket}).
     */
    @CacheEvict(value = "assets", key = "#id")
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
