package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.RegisterFreezeGuard;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderAcceptedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderAllocatedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderLapsedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderPaymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderReleasedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderSettledEvent;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.schedule.Target2Calendar;
import de.makibytes.registerwerk.erc3643.api.Erc3643MintPort;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import de.makibytes.registerwerk.shared.RegisterClock;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
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

    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final SubscriptionOrderRepository repository;
    private final AssetRepository assetRepository;
    private final HolderService holderService;
    private final LegalEntityRepository legalEntityRepository;
    private final SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final InvestorLimitService investorLimitService;
    private final ApplicationEventPublisher events;
    private final RegisterClock registerClock;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final HolderBlockGate holderBlockGate;
    private final ScreeningGate screeningGate;
    private final FinalityGate finalityGate;
    private final TokenAdminPort tokenAdminPort;
    private final Erc3643MintPort erc3643MintPort;

    public SubscriptionOrderService(
            SubscriptionOrderRepository repository,
            AssetRepository assetRepository,
            HolderService holderService,
            LegalEntityRepository legalEntityRepository,
            SuitabilityAssessmentRepository suitabilityAssessmentRepository,
            AssetHolderRepository assetHolderRepository,
            InvestorLimitService investorLimitService,
            ApplicationEventPublisher events,
            RegisterClock registerClock,
            AssetDeploymentRepository deploymentRepository,
            AssetBondTermsRepository bondTermsRepository,
            HolderBlockGate holderBlockGate,
            ScreeningGate screeningGate,
            FinalityGate finalityGate,
            TokenAdminPort tokenAdminPort,
            Erc3643MintPort erc3643MintPort) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.holderService = holderService;
        this.legalEntityRepository = legalEntityRepository;
        this.suitabilityAssessmentRepository = suitabilityAssessmentRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.investorLimitService = investorLimitService;
        this.events = events;
        this.registerClock = registerClock;
        this.deploymentRepository = deploymentRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.holderBlockGate = holderBlockGate;
        this.screeningGate = screeningGate;
        this.finalityGate = finalityGate;
        this.tokenAdminPort = tokenAdminPort;
        this.erc3643MintPort = erc3643MintPort;
    }

    /** Orders are only accepted while the asset is APPROVED (pre-issuance) or already ISSUED
     *  (an ongoing/reopened subscription window) — not DRAFT/PENDING_APPROVAL/SUSPENDED/REDEEMED. */
    public static final java.util.Set<AssetStatus> ORDERABLE_STATUSES =
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
        order.setWalletAddress(AddressNormalizer.normalize(walletAddress));
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
     * When {@code Asset.issueSize} is set, the total across every open/settled allocation on this
     * asset must not exceed it, and the investor's holding cap counts their active holding <em>plus</em>
     * their other open allocations. The asset row is locked for the checks (T3-08), so two parallel
     * allocations cannot each pass on their own.
     */
    public SubscriptionOrder allocate(UUID orderId, BigDecimal allocatedAmount, UUID actorId, String actorRole) {
        SubscriptionOrder order = get(orderId);
        Asset asset = assetRepository.findByIdForUpdate(order.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", order.getAssetId()));
        RegisterFreezeGuard.requireOpen(asset, "Subscription allocation");
        if (order.getStatus() != SubscriptionOrder.Status.SUBMITTED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "ALLOCATED");
        }
        if (allocatedAmount == null || allocatedAmount.signum() <= 0) {
            throw new IllegalArgumentException("allocatedAmount must be positive");
        }
        if (allocatedAmount.compareTo(order.getRequestedAmount()) > 0) {
            throw new IllegalArgumentException("allocatedAmount cannot exceed the requested amount");
        }
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
            BigDecimal existingHolding = activeHolding(order);
            BigDecimal otherOpen = repository.sumOpenAllocatedForInvestor(order.getAssetId(), order.getInvestorEntityId());
            if (otherOpen == null) otherOpen = BigDecimal.ZERO;
            if (existingHolding.add(otherOpen).add(allocatedAmount).compareTo(maxHolding) > 0) {
                throw new IllegalArgumentException(
                        "Allocation would take investor " + order.getInvestorEntityId() + "'s holding above its "
                                + "maximum of " + maxHolding + " (currently holds " + existingHolding
                                + ", plus " + otherOpen + " in other open allocations)");
            }
        }

        Instant now = Instant.now();
        order.setAllocatedAmount(allocatedAmount);
        order.setStatus(SubscriptionOrder.Status.ALLOCATED);
        order.setAllocatedAt(now);
        order.setAllocatedBy(actorId);
        order.setAllocationExpiresAt(paymentDeadline(asset));
        // Bonds: tell the investor what to pay right away (allocated x face value x issue price).
        AssetBondTerms terms = bondTermsRepository.findById(order.getAssetId()).orElse(null);
        if (terms != null && terms.getFaceValue() != null) {
            String currency = terms.getCurrencyIso() != null ? terms.getCurrencyIso() : asset.getCurrency();
            order.setAmountDue(amountDue(order.getAllocatedAmount(), terms, currency));
            order.setPaymentCurrency(currency);
        }
        SubscriptionOrder saved = repository.save(order);

        events.publishEvent(new SubscriptionOrderAllocatedEvent(orderId, actorId, actorRole, Map.of(
                "assetId", order.getAssetId(), "allocatedAmount", allocatedAmount,
                "requestedAmount", order.getRequestedAmount(), "investorEntityId", order.getInvestorEntityId(),
                "allocationExpiresAt", saved.getAllocationExpiresAt().toString())));
        return saved;
    }

    private BigDecimal activeHolding(SubscriptionOrder order) {
        BigDecimal total = assetHolderRepository
                .sumActiveNominalByInvestorIdAndAssetId(order.getInvestorEntityId(), order.getAssetId());
        return total != null ? total : BigDecimal.ZERO;
    }

    /** End of the payment window in the register zone: N TARGET business days after today. */
    private Instant paymentDeadline(Asset asset) {
        LocalDate due = Target2Calendar.INSTANCE.addBusinessDays(registerClock.today(),
                Math.max(1, asset.getSubscriptionPaymentWindowBd()));
        return registerClock.endOfDay(due);
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
     * The investor accepts the allocation (T3-08). This no longer touches the register: the position is
     * only entered once the payment is confirmed and the order is settled.
     */
    public SubscriptionOrder accept(UUID orderId, UUID actorId, String actorRole) {
        SubscriptionOrder order = get(orderId);
        RegisterFreezeGuard.requireOpen(assetRepository, order.getAssetId(), "Subscription acceptance");
        if (order.getStatus() != SubscriptionOrder.Status.ALLOCATED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "ACCEPTED");
        }
        if (order.getAcceptedAt() != null) {
            return order; // idempotent
        }
        if (order.getAllocationExpiresAt() != null && order.getAllocationExpiresAt().isBefore(Instant.now())) {
            throw new InvalidStateTransitionException(
                    "The allocation expired at " + order.getAllocationExpiresAt() + " and can no longer be accepted");
        }
        order.setAcceptedAt(Instant.now());
        SubscriptionOrder saved = repository.save(order);
        events.publishEvent(new SubscriptionOrderAcceptedEvent(orderId, actorId, actorRole, Map.of(
                "assetId", order.getAssetId(), "allocatedAmount", order.getAllocatedAmount(),
                "investorEntityId", order.getInvestorEntityId())));
        return saved;
    }

    /**
     * Issuer/operator confirms that the cash for an accepted allocation arrived. Bonds: the amount due is
     * {@code allocated x faceValue x issuePrice} and underpayment is refused; overpayment is accepted with
     * {@code refundDue} recorded. Non-bond assets: the entered amount is recorded as due (payment basis is
     * still an open policy question).
     */
    public SubscriptionOrder confirmPayment(UUID orderId, BigDecimal paidAmount, String reference,
                                            LocalDate valueDate, UUID actorId, String actorRole) {
        SubscriptionOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("SubscriptionOrder", orderId));
        RegisterFreezeGuard.requireOpen(assetRepository, order.getAssetId(), "Subscription payment confirmation");
        if (order.getStatus() != SubscriptionOrder.Status.ALLOCATED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "PAYMENT_CONFIRMED");
        }
        if (order.getAcceptedAt() == null) {
            throw new InvalidStateTransitionException(
                    "The investor has not accepted the allocation yet — payment cannot be confirmed");
        }
        if (paidAmount == null || paidAmount.signum() <= 0) {
            throw new IllegalArgumentException("paidAmount must be positive");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("paymentReference is required");
        }
        Asset asset = assetRepository.findById(order.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", order.getAssetId()));
        AssetBondTerms terms = bondTermsRepository.findById(order.getAssetId()).orElse(null);
        BigDecimal due;
        String currency;
        if (terms != null && terms.getFaceValue() != null) {
            currency = terms.getCurrencyIso() != null ? terms.getCurrencyIso() : asset.getCurrency();
            due = amountDue(order.getAllocatedAmount(), terms, currency);
            if (paidAmount.compareTo(due) < 0) {
                throw new IllegalArgumentException("Payment of " + paidAmount + " is below the amount due of " + due
                        + " " + currency + " — an underpayment cannot be confirmed");
            }
        } else {
            currency = asset.getCurrency();
            due = paidAmount;
        }
        order.setAmountDue(due);
        order.setPaymentCurrency(currency);
        order.setPaidAmount(paidAmount);
        BigDecimal refund = paidAmount.subtract(due);
        order.setRefundDue(refund.signum() > 0 ? refund : BigDecimal.ZERO);
        order.setPaymentReference(reference);
        order.setPaymentValueDate(valueDate);
        order.setPaymentConfirmedAt(Instant.now());
        order.setPaymentConfirmedBy(actorId);
        order.setStatus(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
        SubscriptionOrder saved = repository.save(order);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assetId", order.getAssetId());
        details.put("investorEntityId", order.getInvestorEntityId());
        details.put("amountDue", due);
        details.put("paidAmount", paidAmount);
        details.put("refundDue", saved.getRefundDue());
        details.put("currency", currency);
        details.put("paymentReference", reference);
        events.publishEvent(new SubscriptionOrderPaymentConfirmedEvent(orderId, actorId, actorRole, details));
        return saved;
    }

    private static BigDecimal amountDue(BigDecimal allocated, AssetBondTerms terms, String currency) {
        BigDecimal price = terms.getIssuePrice() != null ? terms.getIssuePrice() : BigDecimal.ONE;
        return allocated.multiply(terms.getFaceValue()).multiply(price)
                .setScale(minorUnits(currency), RoundingMode.HALF_UP);
    }

    private static int minorUnits(String currencyCode) {
        try {
            return currencyCode != null ? java.util.Currency.getInstance(currencyCode).getDefaultFractionDigits() : 2;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /**
     * Settles a paid order: re-runs the compliance gates that trade settlement applies (asset open,
     * finality, KYC, sanctions screening, Sperrvermerk, target market, holding cap) and then issues the
     * units — a mint where the asset has a confirmed deployment (the holder sync credits the register from
     * the transfer), a direct register credit otherwise. Everything is checked before anything is written.
     */
    public SubscriptionOrder settle(UUID orderId, UUID actorId, String actorRole) {
        SubscriptionOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("SubscriptionOrder", orderId));
        Asset asset = assetRepository.findByIdForUpdate(order.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", order.getAssetId()));
        RegisterFreezeGuard.requireOpen(asset, "Subscription settlement");
        if (order.getStatus() != SubscriptionOrder.Status.PAYMENT_CONFIRMED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "SETTLED");
        }
        if (!ORDERABLE_STATUSES.contains(asset.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Asset is not open for subscription settlement (status=" + asset.getStatus() + ")");
        }
        finalityGate.require(GatedOperation.SUBSCRIPTION_ORDER_ALLOCATE, asset.getId(),
                asset.getTokenStandard(), FinalityLevel.FINALIZED);
        UUID investorId = order.getInvestorEntityId();
        LegalEntity investor = legalEntityRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", investorId));
        requireCompliant(investor, order.getWalletAddress());
        requireEligibleForTargetMarket(asset, investorId);
        BigDecimal allocated = order.getAllocatedAmount();
        BigDecimal maxHolding = investorLimitService.effectiveMaxHolding(asset, investorId);
        if (maxHolding != null && activeHolding(order).add(allocated).compareTo(maxHolding) > 0) {
            throw new ComplianceGateException("Settling would take investor " + investorId
                    + "'s holding above its maximum of " + maxHolding);
        }

        AssetDeployment deployment = deploymentRepository.findByAssetId(asset.getId()).stream()
                .filter(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED)
                .findFirst().orElse(null);
        UUID holderId;
        UUID txId = null;
        if (deployment != null) {
            if (allocated.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("Allocated amount " + allocated.toPlainString()
                        + " is not a whole number of units and cannot be minted");
            }
            if (asset.getTokenStandard() != TokenStandard.ERC20 && asset.getTokenStandard() != TokenStandard.ERC3643) {
                throw new InvalidStateTransitionException("No automated mint is wired for token standard "
                        + asset.getTokenStandard() + " — the units must be issued by an operator on the deployment "
                        + "(the order stays PAYMENT_CONFIRMED)");
            }
            holderId = holderService.ensureMappingRow(asset.getId(), investorId, order.getWalletAddress(),
                    actorId, actorRole, orderId).getId();
            UUID actor = actorId != null ? actorId : SYSTEM_ACTOR;
            txId = asset.getTokenStandard() == TokenStandard.ERC3643
                    ? erc3643MintPort.mint(deployment.getId(), order.getWalletAddress(), allocated, actor, actorRole)
                    : tokenAdminPort.mint(deployment.getId(), order.getWalletAddress(), allocated.toBigIntegerExact(),
                            actor, actorRole);
        } else {
            boolean consumer = investor.getClientCategory() == ClientCategory.RETAIL;
            holderId = holderService.creditPosition(asset.getId(), investorId, order.getWalletAddress(), allocated,
                    consumer, actorId, actorRole, orderId).getId();
        }

        order.setStatus(SubscriptionOrder.Status.SETTLED);
        order.setSettledAt(Instant.now());
        order.setResultingHolderId(holderId);
        order.setSettlementTxId(txId);
        SubscriptionOrder saved = repository.save(order);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assetId", asset.getId());
        details.put("holderId", holderId);
        details.put("allocatedAmount", allocated);
        details.put("investorEntityId", investorId);
        details.put("onchainMint", deployment != null);
        if (txId != null) details.put("mintTxId", txId);
        events.publishEvent(new SubscriptionOrderSettledEvent(orderId, actorId, actorRole, details));
        log.info("Subscription order settled: id={} holderId={} mint={}", orderId, holderId, deployment != null);
        return saved;
    }

    /** Same three checks as {@code TradingService.requireCompliant}: KYC, sanctions screening, Sperrvermerk. */
    private void requireCompliant(LegalEntity entity, String walletAddress) {
        UUID entityId = entity.getId();
        if (entity.getKycStatus() != KycStatus.APPROVED) {
            throw new ComplianceGateException("Entity " + entityId + " does not have an approved KYC status "
                    + "(current: " + entity.getKycStatus() + ") — subscription cannot settle.");
        }
        if (screeningGate.hasUnresolvedHit(entityId)) {
            throw new ComplianceGateException("Entity " + entityId
                    + " has an unresolved sanctions screening hit — subscription cannot settle.");
        }
        if (holderBlockGate.isBlocked(entityId, walletAddress)) {
            throw new ComplianceGateException("Entity " + entityId + " (or its wallet) is subject to an active "
                    + "§16 eWpG Sperrvermerk (legal block) — subscription cannot settle.");
        }
    }

    /**
     * The issuer/operator gives an allocation back (before payment or, for a paid order that cannot be
     * settled, with the payment marked for refund). Frees the capacity again.
     */
    public SubscriptionOrder release(UUID orderId, String reason, UUID actorId, String actorRole) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A release reason is required");
        }
        SubscriptionOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("SubscriptionOrder", orderId));
        if (order.getStatus() != SubscriptionOrder.Status.ALLOCATED
                && order.getStatus() != SubscriptionOrder.Status.PAYMENT_CONFIRMED) {
            throw new InvalidStateTransitionException("SubscriptionOrder", order.getStatus().name(), "RELEASED");
        }
        if (order.getStatus() == SubscriptionOrder.Status.PAYMENT_CONFIRMED && order.getPaidAmount() != null) {
            order.setRefundDue(order.getPaidAmount());
        }
        order.setStatus(SubscriptionOrder.Status.RELEASED);
        order.setReleaseReason(reason);
        SubscriptionOrder saved = repository.save(order);
        events.publishEvent(new SubscriptionOrderReleasedEvent(orderId, actorId, actorRole, Map.of(
                "assetId", order.getAssetId(), "reason", reason, "investorEntityId", order.getInvestorEntityId())));
        return saved;
    }

    /**
     * Moves ALLOCATED orders that missed their payment window to LAPSED, releasing their capacity.
     *
     * @return the number of orders lapsed
     */
    public int lapseExpiredAllocations(Instant now, int batchSize) {
        int lapsed = 0;
        for (SubscriptionOrder candidate : repository.findExpiredAllocations(now,
                org.springframework.data.domain.PageRequest.of(0, batchSize))) {
            SubscriptionOrder order = repository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (order == null || order.getStatus() != SubscriptionOrder.Status.ALLOCATED) {
                continue; // paid or released meanwhile
            }
            order.setStatus(SubscriptionOrder.Status.LAPSED);
            order.setLapsedAt(now);
            repository.save(order);
            events.publishEvent(new SubscriptionOrderLapsedEvent(order.getId(), SYSTEM_ACTOR, "SYSTEM", Map.of(
                    "assetId", order.getAssetId(), "investorEntityId", order.getInvestorEntityId(),
                    "allocationExpiresAt", String.valueOf(order.getAllocationExpiresAt()))));
            lapsed++;
        }
        return lapsed;
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
