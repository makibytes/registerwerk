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
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.api.Erc3643MintPort;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.RegisterClock;
import org.mockito.ArgumentCaptor;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetBondTermsRepository bondTermsRepository;
    @Mock private PartyEligibilityGate partyGate;
    @Mock private OrgMemberWalletRepository memberWallets;
    @Mock private FinalityGate finalityGate;
    @Mock private TokenAdminPort tokenAdminPort;
    @Mock private Erc3643MintPort erc3643MintPort;
    @Mock private de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort vaultAdminPort;

    private SubscriptionOrderService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SubscriptionOrderService(
                repository, assetRepository, holderService, legalEntityRepository, suitabilityAssessmentRepository,
                assetHolderRepository, investorLimitService, events,
                new RegisterClock(Clock.fixed(Instant.parse("2026-03-02T10:00:00Z"), ZoneOffset.UTC), ZoneId.of("Europe/Berlin")),
                deploymentRepository, bondTermsRepository, partyGate, memberWallets, finalityGate,
                tokenAdminPort, erc3643MintPort, vaultAdminPort);
        org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OrgMemberWallet bound = new OrgMemberWallet();
        bound.setWalletAddress("0xabc");
        org.mockito.Mockito.lenient().when(memberWallets.findActiveByLegalEntityId(investorId)).thenReturn(List.of(bound));
        // Target-market gate (Track 5-1): an unclassified investor against an unrestricted test
        // asset (no target market configured) so the gate is a no-op unless a test overrides it.
        org.mockito.Mockito.lenient().when(legalEntityRepository.findById(investorId))
                .thenAnswer(inv -> Optional.of(unclassifiedInvestor()));
    }

    /** Stubs both lookups: allocate/settle lock the asset row (findByIdForUpdate), the rest read it plainly. */
    private void stubAsset(Asset asset) {
        asset.setId(assetId);
        org.mockito.Mockito.lenient().when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        org.mockito.Mockito.lenient().when(assetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
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
        stubAsset(restricted);
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
        stubAsset(restricted);
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
    @DisplayName("T1-07: a subscription asks the vault gate whether the asset's vault has a dealing cut-off "
            + "(production refuses an unconfigured vault)")
    void submit_consultsTheDealingCutoffGate() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(approvedAsset()));

        service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId, "INVESTOR");

        verify(vaultAdminPort).requireDealingCutoffConfigured(eq(assetId), any());
    }

    @Test
    @DisplayName("T1-07: when the vault gate refuses (no dealing cut-off in production) no order is created")
    void submit_refusedByTheDealingCutoffGate_createsNoOrder() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(approvedAsset()));
        org.mockito.Mockito.doThrow(new InvalidStateTransitionException("no dealing cut-off"))
                .when(vaultAdminPort).requireDealingCutoffConfigured(eq(assetId), any());

        assertThatThrownBy(() -> service.submit(assetId, investorId, "0xabc", new BigDecimal("1000"), actorId,
                "INVESTOR")).isInstanceOf(InvalidStateTransitionException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("submit() rejects a DRAFT asset — not yet open for subscription")
    void submit_draftAsset_rejected() {
        Asset draft = new Asset();
        draft.setStatus(AssetStatus.DRAFT);
        stubAsset(draft);

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
        stubAsset(asset);

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
        stubAsset(approvedAsset());

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
        stubAsset(approvedAsset());

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
        stubAsset(capped);
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
        stubAsset(capped);
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
        stubAsset(asset);
        when(investorLimitService.effectiveMaxHolding(any(), eq(investorId))).thenReturn(new BigDecimal("500"));
        when(assetHolderRepository.sumActiveNominalByInvestorIdAndAssetId(investorId, assetId)).thenReturn(new BigDecimal("200"));

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
        stubAsset(asset);
        when(investorLimitService.effectiveMaxHolding(any(), eq(investorId))).thenReturn(new BigDecimal("600"));
        when(assetHolderRepository.sumActiveNominalByInvestorIdAndAssetId(investorId, assetId)).thenReturn(new BigDecimal("200"));

        SubscriptionOrder result = service.allocate(orderId, new BigDecimal("400"), actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.ALLOCATED);
    }

    // ── parallel allocations / expiry (T3-08) ─────────────────────────────────

    @Test
    @DisplayName("parallelAllocationsRespectHoldingCap: the investor's other open allocations count against the cap")
    void parallelAllocationsRespectHoldingCap() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("400"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());
        when(investorLimitService.effectiveMaxHolding(any(), eq(investorId))).thenReturn(new BigDecimal("500"));
        // no holding yet, but 300 already allocated (unpaid) on another order of the same investor
        when(repository.sumOpenAllocatedForInvestor(assetId, investorId)).thenReturn(new BigDecimal("300"));

        assertThatThrownBy(() -> service.allocate(orderId, new BigDecimal("400"), actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
        // 200 fits: 0 + 300 + 200 = 500
        assertThat(service.allocate(orderId, new BigDecimal("200"), actorId, "REGISTRY_ADMIN").getStatus())
                .isEqualTo(SubscriptionOrder.Status.ALLOCATED);
    }

    @Test
    @DisplayName("allocate() sets a payment deadline of N TARGET business days (end of day, Berlin)")
    void allocate_setsPaymentDeadline() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("400"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());

        SubscriptionOrder result = service.allocate(orderId, new BigDecimal("400"), actorId, "REGISTRY_ADMIN");

        // clock: Mon 2026-03-02; +10 TARGET business days = Mon 2026-03-16; expires at the end of that day (Berlin, CET)
        assertThat(result.getAllocationExpiresAt()).isEqualTo(Instant.parse("2026-03-16T23:00:00Z"));
    }

    @Test
    @DisplayName("allocationLapsesAndReleasesCapacity: expired ALLOCATED orders become LAPSED")
    void allocationLapsesAndReleasesCapacity() {
        SubscriptionOrder order = allocatedOrder();
        order.setAllocationExpiresAt(Instant.parse("2026-03-01T00:00:00Z"));
        UUID id = UUID.randomUUID();
        setId(order, id);
        when(repository.findExpiredAllocations(any(), any())).thenReturn(List.of(order));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(order));

        int lapsed = service.lapseExpiredAllocations(Instant.parse("2026-03-02T10:00:00Z"), 100);

        assertThat(lapsed).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.LAPSED);
        assertThat(order.getLapsedAt()).isNotNull();
    }

    @Test
    @DisplayName("lapse skips an order that was paid in the meantime")
    void lapseSkipsPaidOrder() {
        SubscriptionOrder order = allocatedOrder();
        order.setStatus(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
        UUID id = UUID.randomUUID();
        setId(order, id);
        when(repository.findExpiredAllocations(any(), any())).thenReturn(List.of(order));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(order));

        assertThat(service.lapseExpiredAllocations(Instant.now(), 100)).isZero();
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
    }

    // ── accept ────────────────────────────────────────────────────────────────

    private SubscriptionOrder allocatedOrder() {
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        order.setStatus(SubscriptionOrder.Status.ALLOCATED);
        order.setAllocatedAmount(new BigDecimal("800"));
        order.setAllocationExpiresAt(Instant.now().plusSeconds(86_400));
        return order;
    }

    private static void setId(SubscriptionOrder order, UUID id) {
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", id);
    }

    @Test
    @DisplayName("confirmNoLongerCreditsRegister: accepting an allocation does not touch the register")
    void confirmNoLongerCreditsRegister() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = allocatedOrder();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());

        SubscriptionOrder result = service.accept(orderId, actorId, "INVESTOR");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.ALLOCATED);
        assertThat(result.getAcceptedAt()).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(holderService, tokenAdminPort, erc3643MintPort);
    }

    @Test
    @DisplayName("accept() rejects an order that hasn't been allocated yet")
    void accept_notAllocated_rejected() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());

        assertThatThrownBy(() -> service.accept(orderId, actorId, "INVESTOR"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("accept() refuses an allocation past its payment deadline")
    void accept_expired_rejected() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = allocatedOrder();
        order.setAllocationExpiresAt(Instant.now().minusSeconds(60));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());

        assertThatThrownBy(() -> service.accept(orderId, actorId, "INVESTOR"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("expired");
    }

    // ── confirmPayment ────────────────────────────────────────────────────────

    private void stubBondTerms(String faceValue, String issuePrice) {
        AssetBondTerms terms = new AssetBondTerms();
        terms.setFaceValue(new BigDecimal(faceValue));
        terms.setIssuePrice(new BigDecimal(issuePrice));
        terms.setCurrencyIso("EUR");
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));
    }

    @Test
    @DisplayName("confirmPayment(): requires the investor's acceptance first")
    void confirmPayment_requiresAcceptance() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = allocatedOrder();
        when(repository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());

        assertThatThrownBy(() -> service.confirmPayment(orderId, new BigDecimal("800"), "REF", null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("accepted");
    }

    @Test
    @DisplayName("confirmPayment(): bond underpayment refused, overpayment accepted with refundDue")
    void confirmPayment_bondAmountDue() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = allocatedOrder();
        order.setAcceptedAt(Instant.now());
        when(repository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());
        stubBondTerms("1000", "0.99"); // 800 x 1000 x 0.99 = 792000.00

        assertThatThrownBy(() -> service.confirmPayment(orderId, new BigDecimal("791999.99"), "REF", null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("underpayment");

        SubscriptionOrder result = service.confirmPayment(orderId, new BigDecimal("792010.00"), "REF-1",
                LocalDate.of(2026, 3, 2), actorId, "REGISTRY_ADMIN");
        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
        assertThat(result.getAmountDue()).isEqualByComparingTo("792000.00");
        assertThat(result.getRefundDue()).isEqualByComparingTo("10.00");
        assertThat(result.getPaymentConfirmedBy()).isEqualTo(actorId);
    }

    // ── settle ────────────────────────────────────────────────────────────────

    private SubscriptionOrder paidOrder(UUID orderId) {
        SubscriptionOrder order = allocatedOrder();
        order.setAcceptedAt(Instant.now());
        order.setStatus(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
        when(repository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        return order;
    }

    private LegalEntity investorWithKyc(KycStatus status) {
        LegalEntity e = new LegalEntity();
        e.setId(investorId);
        e.setKycStatus(status);
        when(legalEntityRepository.findById(investorId)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("settleRequiresPaymentAndCompliance: unpaid order cannot settle")
    void settle_requiresPayment() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = allocatedOrder(); // ALLOCATED, not paid
        when(repository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        stubAsset(approvedAsset());

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(holderService, never()).creditPosition(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("6-33: a party the PartyEligibilityGate refuses (suspended, BO hit, expired KYC, Sperrvermerk) cannot be settled; nothing is written")
    void settle_partyGateRefusal() {
        UUID orderId = UUID.randomUUID();
        paidOrder(orderId);
        stubAsset(approvedAsset());
        investorWithKyc(KycStatus.APPROVED);
        org.mockito.Mockito.doThrow(new ComplianceGateException("Entity is not eligible for subscription settlement: is not ACTIVE"))
                .when(partyGate).require(eq(investorId), any(), eq("subscription settlement"));

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("not eligible");

        verify(holderService, never()).creditPosition(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
        verify(tokenAdminPort, never()).mint(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("6-33: a settlement wallet that is neither bound to the entity nor an existing holder wallet is refused")
    void settle_unboundWalletRefused() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        order.setWalletAddress("0xthirdparty");
        stubAsset(approvedAsset());
        investorWithKyc(KycStatus.APPROVED);

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("bind the wallet first");

        verify(holderService, never()).creditPosition(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("6-33: an existing active holder wallet of the entity for this asset is accepted even without a member binding")
    void settle_existingHolderWalletAccepted() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        order.setWalletAddress("0xheld");
        stubAsset(approvedAsset());
        investorWithKyc(KycStatus.APPROVED);
        AssetHolder existing = new AssetHolder();
        existing.setAssetId(assetId);
        existing.setWalletAddress("0xheld");
        when(assetHolderRepository.findActiveByInvestorId(investorId)).thenReturn(List.of(existing));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of());
        AssetHolder credited = new AssetHolder();
        credited.setId(UUID.randomUUID());
        when(holderService.creditPosition(eq(assetId), eq(investorId), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any())).thenReturn(credited);

        assertThat(service.settle(orderId, actorId, "REGISTRY_ADMIN").getStatus())
                .isEqualTo(SubscriptionOrder.Status.SETTLED);
    }

    @Test
    @DisplayName("6-33: submit refuses an unbound wallet and consults the party gate")
    void submit_unboundWalletRefused() {
        stubAsset(approvedAsset());

        assertThatThrownBy(() -> service.submit(assetId, investorId, "0xnotmine", new BigDecimal("1000"), actorId, "INVESTOR"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("bind the wallet first");
        verify(partyGate).require(eq(investorId), any(), eq("subscription"));
    }

    @Test
    @DisplayName("settle() consults the finality gate")
    void settle_finalityGate() {
        UUID orderId = UUID.randomUUID();
        paidOrder(orderId);
        stubAsset(approvedAsset());
        org.mockito.Mockito.doThrow(new IllegalStateException("chain quarantined"))
                .when(finalityGate).require(any(), any(), any(), any());

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("quarantined");
    }

    @Test
    @DisplayName("settle() on an asset without deployment credits the register position")
    void settle_offchainCreditsPosition() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        stubAsset(approvedAsset());
        investorWithKyc(KycStatus.APPROVED);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of());
        UUID holderId = UUID.randomUUID();
        AssetHolder holder = new AssetHolder();
        holder.setId(holderId);
        when(holderService.creditPosition(eq(assetId), eq(investorId), any(), eq(new BigDecimal("800")),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any())).thenReturn(holder);

        SubscriptionOrder result = service.settle(orderId, actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLED);
        assertThat(result.getResultingHolderId()).isEqualTo(holderId);
        assertThat(result.getSettledAt()).isNotNull();
        verify(tokenAdminPort, never()).mint(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("settleOnDeployedAssetMintsNotInserts: a deployed ERC-20 gets a mapping row and a mint, no register credit; "
            + "the order is SETTLEMENT_PENDING, never SETTLED, on submission (C7)")
    void settleOnDeployedAssetMintsNotInserts() {
        UUID orderId = UUID.randomUUID();
        paidOrder(orderId);
        Asset asset = approvedAsset();
        asset.setTokenStandard(TokenStandard.ERC20);
        stubAsset(asset);
        investorWithKyc(KycStatus.APPROVED);
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setTokenDecimals(0);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));
        AssetHolder mapping = new AssetHolder();
        mapping.setId(UUID.randomUUID());
        when(holderService.ensureMappingRow(eq(assetId), eq(investorId), any(), any(), any(), any())).thenReturn(mapping);
        UUID txId = UUID.randomUUID();
        when(tokenAdminPort.mint(eq(dep.getId()), any(), eq(BigInteger.valueOf(800)), any(), any())).thenReturn(txId);

        SubscriptionOrder result = service.settle(orderId, actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        assertThat(result.getSettledAt()).isNull();
        assertThat(result.getSettlementTxId()).isEqualTo(txId);
        assertThat(result.getResultingHolderId()).isEqualTo(mapping.getId());
        verify(events).publishEvent(any(de.makibytes.registerwerk.asset.events.SubscriptionOrderSettlementSubmittedEvent.class));
        verify(events, never()).publishEvent(any(de.makibytes.registerwerk.asset.events.SubscriptionOrderSettledEvent.class));
        verify(holderService, never()).creditPosition(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("settle() on a deployed asset without an automated mint stays PAYMENT_CONFIRMED (honest refusal)")
    void settle_deployedUnsupportedStandard() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        Asset asset = approvedAsset();
        asset.setTokenStandard(TokenStandard.ERC1155);
        stubAsset(asset);
        investorWithKyc(KycStatus.APPROVED);
        AssetDeployment dep = new AssetDeployment();
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setTokenDecimals(0);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("No automated mint");
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
    }

    @Test
    @DisplayName("settle() refuses a frozen register (T3-07/K5 guard)")
    void settle_refusedWhileRegisterFrozen() {
        UUID orderId = UUID.randomUUID();
        paidOrder(orderId);
        Asset frozen = approvedAsset();
        frozen.setStatus(AssetStatus.TRANSFER_PENDING);
        stubAsset(frozen);

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── release ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("release() gives an allocation back; a paid order gets its payment marked for refund")
    void release_marksRefundForPaidOrder() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        order.setPaidAmount(new BigDecimal("792000"));

        SubscriptionOrder result = service.release(orderId, "KYC expired", actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.RELEASED);
        assertThat(result.getRefundDue()).isEqualByComparingTo("792000");
        assertThat(result.getReleaseReason()).isEqualTo("KYC expired");
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

    // ── Wave 0b C5: whole-unit register guard ─────────────────────────────────

    private AssetDeployment deploymentWithDecimals(Integer decimals) {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setTokenDecimals(decimals);
        return dep;
    }

    @Test
    @DisplayName("C5: allocate() on an asset deployed with decimals != 0 is refused (409-class), nothing is allocated")
    void allocate_refusedOnFractionalDeployment() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = submittedOrder(new BigDecimal("1000"));
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        Asset asset = approvedAsset();
        asset.setTokenStandard(TokenStandard.ERC20);
        stubAsset(asset);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deploymentWithDecimals(18)));

        assertThatThrownBy(() -> service.allocate(orderId, new BigDecimal("600"), actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.RegisterUnitsException.class)
                .hasMessageContaining("Subscription allocation")
                .hasMessageContaining("decimals=18");
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SUBMITTED);
        assertThat(order.getAllocatedAmount()).isNull();
    }

    @Test
    @DisplayName("C5: settle() on an asset deployed with decimals != 0 never mints (the order stays PAYMENT_CONFIRMED)")
    void settle_refusedOnFractionalDeployment() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        Asset asset = approvedAsset();
        asset.setTokenStandard(TokenStandard.ERC20);
        stubAsset(asset);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deploymentWithDecimals(18)));

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.RegisterUnitsException.class)
                .hasMessageContaining("Subscription settlement");

        verify(tokenAdminPort, never()).mint(any(), any(), any(), any(), any());
        verify(erc3643MintPort, never()).mint(any(), any(), any(), any(), any());
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
    }

    @Test
    @DisplayName("C5: an ERC-3643 deployment of unknown decimals is refused the same way (no mint)")
    void settle_refusedOnUnknownDecimals() {
        UUID orderId = UUID.randomUUID();
        paidOrder(orderId);
        Asset asset = approvedAsset();
        asset.setTokenStandard(TokenStandard.ERC3643);
        stubAsset(asset);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deploymentWithDecimals(null)));

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.RegisterUnitsException.class);
        verify(erc3643MintPort, never()).mint(any(), any(), any(), any(), any());
    }

    // ── Wave 0b C7: SETTLEMENT_PENDING / outbox / retry ────────────────────────

    private AssetDeployment deployedErc20() {
        Asset asset = approvedAsset();
        asset.setTokenStandard(TokenStandard.ERC20);
        stubAsset(asset);
        investorWithKyc(KycStatus.APPROVED);
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setTokenDecimals(0);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));
        AssetHolder mapping = new AssetHolder();
        mapping.setId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(holderService.ensureMappingRow(eq(assetId), eq(investorId), any(), any(), any(), any()))
                .thenReturn(mapping);
        return dep;
    }

    @Test
    @DisplayName("C7: a SETTLEMENT_PENDING order cannot be settled again - a lost response never produces a second mint")
    void settle_pendingOrderCannotBeSettledAgain() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        order.setStatus(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        order.setSettlementTxId(UUID.randomUUID());
        stubAsset(approvedAsset());

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(tokenAdminPort, never()).mint(any(), any(), any(), any(), any());
        verify(erc3643MintPort, never()).mint(any(), any(), any(), any(), any());
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
    }

    @Test
    @DisplayName("C7: after a definitive mint failure (SETTLEMENT_FAILED) the order can be settled again, and the retry mints once")
    void settle_failedOrderIsRetryable() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        order.setStatus(SubscriptionOrder.Status.SETTLEMENT_FAILED);
        order.setSettlementTxId(UUID.randomUUID());
        order.setSettlementFailureReason("mint 0xold FAILED: Transaction reverted on-chain");
        AssetDeployment dep = deployedErc20();
        UUID retryTx = UUID.randomUUID();
        when(tokenAdminPort.mint(eq(dep.getId()), any(), eq(BigInteger.valueOf(800)), any(), any())).thenReturn(retryTx);

        SubscriptionOrder result = service.settle(orderId, actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        assertThat(result.getSettlementTxId()).isEqualTo(retryTx);
        assertThat(result.getSettlementFailureReason()).isNull();
        verify(tokenAdminPort, org.mockito.Mockito.times(1)).mint(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("C7: a mint that cannot even be submitted leaves the order PAYMENT_CONFIRMED (nothing advanced), retryable")
    void settle_submissionFailureLeavesThePaidOrderUntouched() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId);
        AssetDeployment dep = deployedErc20();
        when(tokenAdminPort.mint(eq(dep.getId()), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("signer unavailable"));

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signer unavailable");

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
        assertThat(order.getSettlementTxId()).isNull();
        verify(events, never()).publishEvent(any(de.makibytes.registerwerk.asset.events.SubscriptionOrderSettlementSubmittedEvent.class));
    }

    @Test
    @DisplayName("C7: a commit failure after settle() does not double-mint - the mint is broadcast only after commit, so the retry mints exactly once")
    void settle_commitFailureDoesNotDoubleMint() {
        java.util.List<String> broadcasts = new java.util.ArrayList<>();
        AssetDeployment dep = deployedErc20();
        // Behaves like DurableEvmTransactionGateway: the signed bytes are persisted with the caller's transaction,
        // the broadcast happens only in afterCommit.
        when(tokenAdminPort.mint(eq(dep.getId()), any(), any(), any(), any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { broadcasts.add("mint"); }
                    });
            return UUID.randomUUID();
        });
        try {
            // attempt 1: the transaction fails to commit -> synchronizations are dropped without afterCommit
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            UUID firstId = UUID.randomUUID();
            paidOrder(firstId);
            service.settle(firstId, actorId, "REGISTRY_ADMIN");
            assertThat(broadcasts).as("nothing is broadcast inside the open transaction").isEmpty();
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            assertThat(broadcasts).as("a failed commit broadcasts nothing").isEmpty();

            // attempt 2 (retry on the order as it was before the failed commit): commits
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            UUID retryId = UUID.randomUUID();
            paidOrder(retryId);
            service.settle(retryId, actorId, "REGISTRY_ADMIN");
            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertThat(broadcasts).as("exactly one mint reaches the chain").containsExactly("mint");
        } finally {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Test
    @DisplayName("C7: the holding-cap check at settlement counts the investor's other mints still in flight")
    void settle_holdingCapCountsMintsInFlight() {
        UUID orderId = UUID.randomUUID();
        SubscriptionOrder order = paidOrder(orderId); // allocated 800
        AssetDeployment dep = deployedErc20();
        when(investorLimitService.effectiveMaxHolding(any(), eq(investorId))).thenReturn(new BigDecimal("1000"));
        when(assetHolderRepository.sumActiveNominalByInvestorIdAndAssetId(investorId, assetId)).thenReturn(new BigDecimal("100"));
        // another order of 300 units is SETTLEMENT_PENDING: 100 held + 300 in flight + 800 = 1200 > 1000
        when(repository.sumPendingSettlementForInvestor(assetId, investorId, orderId)).thenReturn(new BigDecimal("300"));

        assertThatThrownBy(() -> service.settle(orderId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("maximum of 1000").hasMessageContaining("300 more in flight");

        verify(tokenAdminPort, never()).mint(any(), any(), any(), any(), any());
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
    }

    @Test
    @DisplayName("C7: an order whose mint is in flight cannot be released (the mint may still execute); a failed one can, with a refund")
    void release_pendingRefused_failedAllowed() {
        UUID pendingId = UUID.randomUUID();
        SubscriptionOrder pending = paidOrder(pendingId);
        pending.setStatus(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        assertThatThrownBy(() -> service.release(pendingId, "wrong", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);

        UUID failedId = UUID.randomUUID();
        SubscriptionOrder failed = paidOrder(failedId);
        failed.setPaidAmount(new BigDecimal("792000"));
        failed.setStatus(SubscriptionOrder.Status.SETTLEMENT_FAILED);
        SubscriptionOrder released = service.release(failedId, "mint failed, refunding", actorId, "REGISTRY_ADMIN");

        assertThat(released.getStatus()).isEqualTo(SubscriptionOrder.Status.RELEASED);
        assertThat(released.getRefundDue()).isEqualByComparingTo("792000");
    }
}
