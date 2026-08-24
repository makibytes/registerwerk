package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.InvestorLimit;
import de.makibytes.registerwerk.asset.api.InvestorLimitGate;
import de.makibytes.registerwerk.asset.api.InvestorLimitRepository;
import de.makibytes.registerwerk.asset.events.InvestorLimitSetEvent;
import de.makibytes.registerwerk.asset.events.InvestorLimitDeletedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves and manages per-investor limit overrides on top of an {@link Asset}'s own
 * min-investment/max-holding defaults (F-BLOCKER-12). An investor's <em>effective</em> limit is
 * their {@link InvestorLimit} override if one exists, otherwise the asset's default, otherwise
 * unrestricted — a specific override always wins, including one that deliberately clears a limit
 * (a null override field is "use the asset default," not automatically present unless someone
 * created the row for another reason like a lockup).
 */
@Service
@Transactional
public class InvestorLimitService implements InvestorLimitGate {

    private static final Logger log = LoggerFactory.getLogger(InvestorLimitService.class);

    private final InvestorLimitRepository repository;
    private final ApplicationEventPublisher events;

    public InvestorLimitService(InvestorLimitRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public BigDecimal effectiveMinInvestment(Asset asset, UUID investorEntityId) {
        return repository.findByAssetIdAndInvestorEntityId(asset.getId(), investorEntityId)
                .map(InvestorLimit::getMinInvestmentOverride)
                .orElse(asset.getMinInvestmentAmount());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal effectiveMaxHolding(Asset asset, UUID investorEntityId) {
        return repository.findByAssetIdAndInvestorEntityId(asset.getId(), investorEntityId)
                .map(InvestorLimit::getMaxHoldingOverride)
                .orElse(asset.getMaxHoldingAmount());
    }

    @Transactional(readOnly = true)
    public LocalDate lockupUntil(UUID assetId, UUID investorEntityId) {
        return repository.findByAssetIdAndInvestorEntityId(assetId, investorEntityId)
                .map(InvestorLimit::getLockupUntil)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLockedUp(UUID assetId, UUID investorEntityId) {
        LocalDate until = lockupUntil(assetId, investorEntityId);
        return until != null && LocalDate.now().isBefore(until);
    }

    /** Creates or replaces the override row for this (asset, investor) pair. */
    public InvestorLimit setLimit(UUID assetId, UUID investorEntityId, BigDecimal minInvestmentOverride,
                                   BigDecimal maxHoldingOverride, LocalDate lockupUntil, UUID actorId) {
        requirePositive("minInvestmentOverride", minInvestmentOverride);
        requirePositive("maxHoldingOverride", maxHoldingOverride);
        if (minInvestmentOverride != null && maxHoldingOverride != null
                && minInvestmentOverride.compareTo(maxHoldingOverride) > 0) {
            throw new IllegalArgumentException(
                    "minInvestmentOverride must not exceed maxHoldingOverride");
        }
        InvestorLimit limit = repository.findByAssetIdAndInvestorEntityId(assetId, investorEntityId)
                .orElseGet(() -> {
                    InvestorLimit created = new InvestorLimit();
                    created.setAssetId(assetId);
                    created.setInvestorEntityId(investorEntityId);
                    return created;
                });
        limit.setMinInvestmentOverride(minInvestmentOverride);
        limit.setMaxHoldingOverride(maxHoldingOverride);
        limit.setLockupUntil(lockupUntil);
        limit.setUpdatedBy(actorId);
        InvestorLimit saved = repository.save(limit);
        events.publishEvent(new InvestorLimitSetEvent(saved.getId(), actorId, null, Map.of(
                "assetId", assetId.toString(), "investorEntityId", investorEntityId.toString())));
        log.info("Set investor limit: assetId={} investorEntityId={}", assetId, investorEntityId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<InvestorLimit> listForAsset(UUID assetId) {
        return repository.findByAssetIdOrderByUpdatedAtDesc(assetId);
    }

    @Transactional(readOnly = true)
    public Optional<InvestorLimit> getLimit(UUID assetId, UUID investorEntityId) {
        return repository.findByAssetIdAndInvestorEntityId(assetId, investorEntityId);
    }

    public void deleteLimit(UUID assetId, UUID investorEntityId, UUID actorId) {
        InvestorLimit limit = repository.findByAssetIdAndInvestorEntityId(assetId, investorEntityId)
                .orElseThrow(() -> new EntityNotFoundException("InvestorLimit", investorEntityId));
        repository.delete(limit);
        events.publishEvent(new InvestorLimitDeletedEvent(limit.getId(), actorId, null, Map.of(
                "assetId", assetId.toString(), "investorEntityId", investorEntityId.toString())));
        log.info("Deleted investor limit: assetId={} investorEntityId={}", assetId, investorEntityId);
    }

    private static void requirePositive(String field, BigDecimal value) {
        if (value != null && value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
    }
}
