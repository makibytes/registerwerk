package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.internal.HolderService;
import de.makibytes.registerwerk.asset.internal.SubscriptionOrder;
import de.makibytes.registerwerk.asset.internal.SubscriptionOrderRepository;
import de.makibytes.registerwerk.asset.internal.SubscriptionOrderService;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import de.makibytes.registerwerk.asset.internal.InvestorLimitService;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionOrderServiceTest {

    @Mock private SubscriptionOrderRepository repository;
    @Mock private AssetRepository assetRepository;
    @Mock private HolderService holderService;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private InvestorLimitService investorLimitService;
    @Mock private ApplicationEventPublisher events;

    private SubscriptionOrderService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SubscriptionOrderService(
                repository, assetRepository, holderService, legalEntityRepository, suitabilityAssessmentRepository,
                assetHolderRepository, investorLimitService, events);
        org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Target-market gate (Track 5-1): an unclassified investor against an unrestricted test
        // asset (no target market configured) so the gate is a no-op unless a test overrides it.
        org.mockito.Mockito.lenient().when(legalEntityRepository.findById(investorId))
                .thenAnswer(inv -> Optional.of(unclassifiedInvestor()));
    }

    private LegalEntity unclassifiedInvestor() {
        LegalEntity entity = new LegalEntity();
        entity.setId(investorId);
        return entity;
    }

    private Asset approvedAsset() {
        Asset a = new Asset();
        a.setStatus(AssetStatus.APPROVED);
        return a;
    }

    private SubscriptionOrder submittedOrder(BigDecimal requested) {
        SubscriptionOrder o = new SubscriptionOrder();
        o.setAssetId(assetId);
        o.setInvestorEntityId(investorId);
        o.setWalletAddress("0xabc");
        o.setRequestedAmount(requested);
        o.setStatus(SubscriptionOrder.Status.SUBMITTED);
        return o;
    }

    // ── submit ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit() succeeds when the asset is APPROVED")
    void submit_approvedAsset_succeeds() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(approvedAsset()));

        SubscriptionOrder order = service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId, "INVESTOR");

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SUBMITTED);
        assertThat(order.getRequestedAmount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("submit() rejects an investor outside the asset's MiFID target market (Track 5-1)")
    void submit_rejectsInvestorOutsideTargetMarket() {
        Asset restricted = approvedAsset();
        restricted.setTargetMarketCategories(java.util.Set.of(de.makibytes.registerwerk.customer.api.ClientCategory.PROFESSIONAL));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(restricted));
        // The default stub (setUp) returns an investor with no clientCategory set at all.

        assertThatThrownBy(() -> service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId, "INVESTOR"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("target market");
    }

    @Test
    @DisplayName("submit() allows an investor whose classification is within the target market (Track 5-1)")
    void submit_allowsInvestorWithinTargetMarket() {
        Asset restricted = approvedAsset();
        restricted.setTargetMarketCategories(java.util.Set.of(de.makibytes.registerwerk.customer.api.ClientCategory.RETAIL));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(restricted));
        LegalEntity retailInvestor = new LegalEntity();
        retailInvestor.setId(investorId);
        retailInvestor.setClientCategory(de.makibytes.registerwerk.customer.api.ClientCategory.RETAIL);
        when(legalEntityRepository.findById(investorId)).thenReturn(Optional.of(retailInvestor));

        SubscriptionOrder order = service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId, "INVESTOR");

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SUBMITTED);
    }

    @Test
    @DisplayName("submit() rejects a requested amount below the effective minimum investment (Track 5-2)")
    void submit_rejectsBelowMinimumInvestment() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(approvedAsset()));
        when(investorLimitService.effectiveMinInvestment(any(), eq(investorId))).thenReturn(new BigDecimal("5000"));

        assertThatThrownBy(() -> service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId, "INVESTOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum investment");
    }

    @Test
    @DisplayName("submit() rejects a DRAFT asset — not yet open for subscription")
    void submit_draftAsset_rejected() {
        Asset draft = new Asset();
        draft.setStatus(AssetStatus.DRAFT);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId, "INVESTOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("submit() rejects a non-positive requested amount")
    void submit_nonPositiveAmount_rejected() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(approvedAsset()));

        assertThatThrownBy(() -> service.submit(assetId, investorId, "0xabc", BigDecimal.ZERO, actorId, "INVESTOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── allocate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("allocate() accepts a partial allocation (scaling) up to the requested amount")
    void allocate_partial_succeeds() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        Asset asset = approvedAsset(); // issueSize null -> no cap check
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        SubscriptionOrder result = service.allocate(orderId, new BigDecimal("600"), actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.ALLOCATED);
        assertThat(result.getAllocatedAmount()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("allocate() rejects an amount exceeding what was requested")
    void allocate_exceedsRequested_rejected() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.allocate(orderId, new BigDecimal("1001"), actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("allocate() rejects an order that isn't SUBMITTED")
    void allocate_notSubmitted_rejected() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        order.setStatus(SubscriptionOrder.Status.CANCELLED);
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.allocate(orderId, new BigDecimal("500"), actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("allocate() enforces Asset.issueSize as a total cap across all allocated orders")
    void allocate_respectsIssueSizeCap() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        Asset capped = approvedAsset();
        capped.setIssueSize(new BigDecimal("1000"));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(capped));
        when(repository.sumAllocated(assetId)).thenReturn(new BigDecimal("600")); // already allocated elsewhere

        assertThatThrownBy(() -> service.allocate(orderId, new BigDecimal("500"), actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issue size");
    }

    @Test
    @DisplayName("allocate() allows an allocation that exactly fills the remaining issue size")
    void allocate_exactlyFillsIssueSize_succeeds() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        Asset capped = approvedAsset();
        capped.setIssueSize(new BigDecimal("1000"));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(capped));
        when(repository.sumAllocated(assetId)).thenReturn(new BigDecimal("600"));

        SubscriptionOrder result = service.allocate(orderId, new BigDecimal("400"), actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.ALLOCATED);
    }

    @Test
    @DisplayName("allocate() rejects an allocation that would push the investor's holding above its maximum (Track 5-2)")
    void allocate_rejectsAboveMaxHolding() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        Asset asset = approvedAsset();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(investorLimitService.effectiveMaxHolding(any(), eq(investorId))).thenReturn(new BigDecimal("500"));
        AssetHolder existing = new AssetHolder();
        existing.setNominalAmount(new BigDecimal("200"));
        when(assetHolderRepository.findActiveByInvestorIdAndAssetId(investorId, assetId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.allocate(orderId, new BigDecimal("400"), actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    @DisplayName("allocate() allows an allocation that exactly reaches the investor's maximum holding (Track 5-2)")
    void allocate_allowsExactlyReachingMaxHolding() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        Asset asset = approvedAsset();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(investorLimitService.effectiveMaxHolding(any(), eq(investorId))).thenReturn(new BigDecimal("600"));
        AssetHolder existing = new AssetHolder();
        existing.setNominalAmount(new BigDecimal("200"));
        when(assetHolderRepository.findActiveByInvestorIdAndAssetId(investorId, assetId)).thenReturn(Optional.of(existing));

        SubscriptionOrder result = service.allocate(orderId, new BigDecimal("400"), actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.ALLOCATED);
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm() enters the position via HolderService and links the resulting holder")
    void confirm_allocatedOrder_createsHolder() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        order.setStatus(SubscriptionOrder.Status.ALLOCATED);
        order.setAllocatedAmount(new BigDecimal("800"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        UUID holderId = UUID.randomUUID();
        AssetHolder holder = new AssetHolder();
        holder.setId(holderId);
        when(holderService.addHolder(assetId, investorId, "0xabc", new BigDecimal("800"), actorId, "INVESTOR"))
                .thenReturn(holder);

        SubscriptionOrder result = service.confirm(orderId, actorId, "INVESTOR");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.CONFIRMED);
        assertThat(result.getResultingHolderId()).isEqualTo(holderId);
        assertThat(result.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("confirm() rejects an order that hasn't been allocated yet")
    void confirm_notAllocated_rejected() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirm(orderId, actorId, "INVESTOR"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── reject / cancel ───────────────────────────────────────────────────────

    @Test
    @DisplayName("reject() records the reason and moves to REJECTED")
    void reject_submittedOrder_succeeds() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        SubscriptionOrder result = service.reject(orderId, "Insufficient KYC documentation.", actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("Insufficient KYC documentation.");
    }

    @Test
    @DisplayName("cancel() only works before allocation")
    void cancel_afterAllocation_rejected() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        order.setStatus(SubscriptionOrder.Status.ALLOCATED);
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(orderId, actorId, "INVESTOR"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("get() on an unknown id throws EntityNotFoundException")
    void get_unknownId_throwsNotFound() {
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(orderId)).isInstanceOf(EntityNotFoundException.class);
    }
}
