package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderAllocatedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderCancelledEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderConfirmedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderRejectedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderSubmittedEvent;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Primary-market subscription/allocation/confirmation flow — see {@link SubscriptionOrder}'s
 * Javadoc for what this replaces. Lives in {@code asset.internal} (not a separate module) so it
 * can call {@link HolderService} directly, same as every other holder-creating path in this
 * module (bond terms, single-entry holders) — a new top-level module would need a cross-module
 * port to reach {@link HolderService}, which is an internal (not {@code api}) class.
 */
@Service
@Transactional
public class SubscriptionOrderService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionOrderService.class);

    private final SubscriptionOrderRepository repository;
    private final AssetRepository assetRepository;
    private final HolderService holderService;
    private final LegalEntityRepository legalEntityRepository;
    private final SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final InvestorLimitService investorLimitService;
    private final ApplicationEventPublisher events;

    public SubscriptionOrderService(
            SubscriptionOrderRepository repository,
            AssetRepository assetRepository,
            HolderService holderService,
            LegalEntityRepository legalEntityRepository,
            SuitabilityAssessmentRepository suitabilityAssessmentRepository,
            AssetHolderRepository assetHolderRepository,
            InvestorLimitService investorLimitService,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.holderService = holderService;
        this.legalEntityRepository = legalEntityRepository;
        this.suitabilityAssessmentRepository = suitabilityAssessmentRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.investorLimitService = investorLimitService;
        this.events = events;
    }

    /** Orders are only accepted while the asset is APPROVED (pre-issuance) or already ISSUED
     *  (an ongoing/reopened subscription window) — not DRAFT/PENDING_APPROVAL/SUSPENDED/REDEEMED. */
    private static final java.util.Set<AssetStatus> ORDERABLE_STATUSES =
            java.util.Set.of(AssetStatus.APPROVED, AssetStatus.ISSUED);

    public SubscriptionOrder submit(UUID assetId, UUID investorEntityId, String walletAddress,
                                     BigDecimal requestedAmount, UUID actorId, String actorRole) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (!ORDERABLE_STATUSES.contains(asset.getStatus())) {
            throw new IllegalArgumentException(
                    "Asset is not open for subscription (status=" + asset.getStatus() + ")");
        }
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("requestedAmount must be positive");
        }
        requireEligibleForTargetMarket(asset, investorEntityId);
        BigDecimal minInvestment = investorLimitService.effectiveMinInvestment(asset, investorEntityId);
        if (minInvestment != null && requestedAmount.compareTo(minInvestment) < 0) {
            throw new IllegalArgumentException(
                    "requestedAmount " + requestedAmount + " is below the minimum investment of " + minInvestment);
        }

        SubscriptionOrder order = new SubscriptionOrder();
        order.setAssetId(assetId);
        order.setInvestorEntityId(investorEntityId);
        order.setWalletAddress(walletAddress);
        order.setRequestedAmount(requestedAmount);
        SubscriptionOrder saved = repository.save(order);

        events.publishEvent(new SubscriptionOrderSubmittedEvent(saved.getId(), actorId, actorRole, Map.of(
                "assetId", assetId, "requestedAmount", requestedAmount)));
        log.info("Subscription order submitted: id={} assetId={} investor={} requested={}",
                saved.getId(), assetId, investorEntityId, requestedAmount);
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionOrder> listForAsset(UUID assetId, Pageable pageable) {
        return repository.findByAssetIdOrderBySubmittedAtDesc(assetId, pageable);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionOrder> listForInvestor(UUID investorEntityId) {
        return repository.findByInvestorEntityIdOrderBySubmittedAtDesc(investorEntityId);
    }

    @Transactional(readOnly = true)
    public SubscriptionOrder get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new EntityNotFoundException("SubscriptionOrder", id));
    }

    /**
     * Allocates an amount up to {@code requestedAmount} ("scaling" an oversubscribed issuance).
     * When {@code Asset.issueSize} is set, the total across every ALLOCATED/CONFIRMED order on
     * this asset must not exceed it — the one place this session's economic-terms field
     * (Track 1) actually constrains a workflow, not just a display value.
     */
    public SubscriptionOrder allocate(UUID orderId, BigDecimal allocatedAmount, UUID actorId, String actorRole) {
        SubscriptionOrder order = get(orderId);
        if (order.getStatus() != SubscriptionOrder.Status.SUBMITTED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "ALLOCATED");
        }
        if (allocatedAmount == null || allocatedAmount.signum() <= 0) {
            throw new IllegalArgumentException("allocatedAmount must be positive");
        }
        if (allocatedAmount.compareTo(order.getRequestedAmount()) > 0) {
            throw new IllegalArgumentException("allocatedAmount cannot exceed the requested amount");
        }
        Asset asset = assetRepository.findById(order.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", order.getAssetId()));
        if (asset.getIssueSize() != null) {
            BigDecimal alreadyAllocated = repository.sumAllocated(order.getAssetId());
            if (alreadyAllocated.add(allocatedAmount).compareTo(asset.getIssueSize()) > 0) {
                throw new IllegalArgumentException(
                        "Allocation would exceed the asset's issue size ("
                                + alreadyAllocated + " already allocated of " + asset.getIssueSize() + ")");
            }
        }
        BigDecimal maxHolding = investorLimitService.effectiveMaxHolding(asset, order.getInvestorEntityId());
        if (maxHolding != null) {
            BigDecimal existingHolding = assetHolderRepository
                    .findActiveByInvestorIdAndAssetId(order.getInvestorEntityId(), order.getAssetId())
                    .map(AssetHolder::getNominalAmount)
                    .orElse(BigDecimal.ZERO);
            if (existingHolding.add(allocatedAmount).compareTo(maxHolding) > 0) {
                throw new IllegalArgumentException(
                        "Allocation would take investor " + order.getInvestorEntityId() + "'s holding above its "
                                + "maximum of " + maxHolding + " (currently holds " + existingHolding + ")");
            }
        }

        order.setAllocatedAmount(allocatedAmount);
        order.setStatus(SubscriptionOrder.Status.ALLOCATED);
        order.setAllocatedAt(Instant.now());
        order.setAllocatedBy(actorId);
        SubscriptionOrder saved = repository.save(order);

        events.publishEvent(new SubscriptionOrderAllocatedEvent(orderId, actorId, actorRole, Map.of(
                "assetId", order.getAssetId(), "allocatedAmount", allocatedAmount,
                "requestedAmount", order.getRequestedAmount(), "investorEntityId", order.getInvestorEntityId())));
        return saved;
    }

    public SubscriptionOrder reject(UUID orderId, String reason, UUID actorId, String actorRole) {
        SubscriptionOrder order = get(orderId);
        if (order.getStatus() != SubscriptionOrder.Status.SUBMITTED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "REJECTED");
        }
        order.setStatus(SubscriptionOrder.Status.REJECTED);
        order.setRejectionReason(reason);
        SubscriptionOrder saved = repository.save(order);

        events.publishEvent(new SubscriptionOrderRejectedEvent(orderId, actorId, actorRole, Map.of(
                "reason", reason, "investorEntityId", order.getInvestorEntityId())));
        return saved;
    }

    /** Investor-initiated, only before allocation — once allocated, the operator has committed
     *  capacity to this order and it must go through allocate/reject instead. */
    public SubscriptionOrder cancel(UUID orderId, UUID actorId, String actorRole) {
        SubscriptionOrder order = get(orderId);
        if (order.getStatus() != SubscriptionOrder.Status.SUBMITTED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "CANCELLED");
        }
        order.setStatus(SubscriptionOrder.Status.CANCELLED);
        SubscriptionOrder saved = repository.save(order);

        events.publishEvent(new SubscriptionOrderCancelledEvent(orderId, actorId, actorRole, Map.of()));
        return saved;
    }

    /**
     * Investor confirms the allocation — this is the one call that actually enters the position
     * on the register, via {@link HolderService#addHolder}, so nothing is entered until the
     * investor has explicitly accepted what they were allocated.
     */
    public SubscriptionOrder confirm(UUID orderId, UUID actorId, String actorRole) {
        SubscriptionOrder order = get(orderId);
        if (order.getStatus() != SubscriptionOrder.Status.ALLOCATED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "CONFIRMED");
        }

        AssetHolder holder = holderService.addHolder(
                order.getAssetId(), order.getInvestorEntityId(), order.getWalletAddress(),
                order.getAllocatedAmount(), actorId, actorRole);

        order.setStatus(SubscriptionOrder.Status.CONFIRMED);
        order.setConfirmedAt(Instant.now());
        order.setResultingHolderId(holder.getId());
        SubscriptionOrder saved = repository.save(order);

        events.publishEvent(new SubscriptionOrderConfirmedEvent(orderId, actorId, actorRole, Map.of(
                "assetId", order.getAssetId(), "holderId", holder.getId(),
                "allocatedAmount", order.getAllocatedAmount(), "investorEntityId", order.getInvestorEntityId())));
        log.info("Subscription order confirmed: id={} holderId={}", orderId, holder.getId());
        return saved;
    }

    /**
     * MiFID II product-governance gate (F-BLOCKER-11): an investor whose client category or
     * knowledge/experience falls outside the asset's declared target market cannot subscribe.
     * An asset with no target market configured is unrestricted (see
     * {@link Asset#isEligibleForTargetMarket}) — this never blocks legacy/demo assets.
     */
    private void requireEligibleForTargetMarket(Asset asset, UUID investorEntityId) {
        LegalEntity investor = legalEntityRepository.findById(investorEntityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", investorEntityId));
        SuitabilityAssessment latest = suitabilityAssessmentRepository
                .findFirstByEntityIdOrderByAssessedAtDesc(investorEntityId).orElse(null);
        boolean eligible = asset.isEligibleForTargetMarket(
                investor.getClientCategory(), latest != null ? latest.getKnowledgeExperience() : null);
        if (!eligible) {
            throw new ComplianceGateException(
                    "Investor " + investorEntityId + " is outside this asset's MiFID target market "
                    + "(client category and/or knowledge/experience requirement not met).");
        }
    }
}
