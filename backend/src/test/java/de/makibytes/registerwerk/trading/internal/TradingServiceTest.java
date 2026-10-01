package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.HolderEnteredEvent;
import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCreatedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentConfirmedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDeclaredEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import de.makibytes.registerwerk.trading.events.TradePendingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentUnresolvedEvent;
import de.makibytes.registerwerk.trading.events.TradeRefundedEvent;
import de.makibytes.registerwerk.trading.web.dto.BuyTradingOfferRequest;
import de.makibytes.registerwerk.trading.web.dto.CreateTradeListingRequest;
import de.makibytes.registerwerk.trading.web.dto.TradeExecutionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the simulated-venue trading flow: listing creation/cancellation,
 * buy-side validation, and settlement (the register mutation that credits/debits
 * AssetHolder positions). This is the highest-risk, least-tested part of the
 * codebase — a bug here directly means an incorrect securities register.
 */
@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock private CompanyTraderSettingsRepository settingsRepository;
    @Mock private CompanyTraderWalletDefaultRepository walletDefaultRepository;
    @Mock private TradeListingRepository tradeListingRepository;
    @Mock private TradeExecutionRepository tradeExecutionRepository;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private AddressEndpointRepository endpointRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TradingVenueAdapter venueAdapter;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    @Mock private de.makibytes.registerwerk.asset.api.InvestorLimitGate investorLimitGate;
    @Mock private PartyEligibilityGate partyEligibilityGate;
    @Mock private HolderEncumbranceRegistry encumbrance;
    @Mock private OrgMemberWalletRepository orgMemberWalletRepository;
    @Mock private de.makibytes.registerwerk.finality.api.FinalityGate finalityGate;
    @Mock private de.makibytes.registerwerk.payment.api.PaymentRailRepository paymentRailRepository;
    @Mock private RelatedPartyCheck relatedPartyCheck;
    @Mock private RelatedPartyAlerts relatedPartyAlerts;

    private TradingProperties tradingProperties;
    private TradingAssetTypeResolver tradingAssetTypeResolver;
    private TradingService service;
    private TradeTransitions transitions;

    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final UUID HOLDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tradingProperties = new TradingProperties();
        tradingProperties.setEnabled(true);
        tradingAssetTypeResolver = new TradingAssetTypeResolver();
        transitions = new TradeTransitions(tradeExecutionRepository, tradeListingRepository, eventPublisher, tradingProperties);
        service = new TradingService(
                tradingProperties, settingsRepository, walletDefaultRepository,
                tradeListingRepository, tradeExecutionRepository, assetHolderRepository,
                assetRepository, assetDeploymentRepository, endpointRepository,
                List.of(venueAdapter), tradingAssetTypeResolver, eventPublisher,
                legalEntityRepository, suitabilityAssessmentRepository, investorLimitGate, partyEligibilityGate,
                finalityGate, encumbrance, transitions, orgMemberWalletRepository,
                new TradeCurrencyPolicy(paymentRailRepository, tradingProperties), relatedPartyCheck, relatedPartyAlerts);
        lenient().when(tradeListingRepository.save(any(TradeListing.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tradeExecutionRepository.save(any(TradeExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(assetHolderRepository.save(any(AssetHolder.class))).thenAnswer(inv -> {
            AssetHolder h = inv.getArgument(0);
            if (h.getId() == null) h.setId(UUID.randomUUID());
            return h;
        });
        // Compliant-by-default: settlement's KYC/screening/Sperrvermerk gate passes for any
        // entity unless a test explicitly overrides one of these stubs to exercise the gate itself.
        lenient().when(legalEntityRepository.findById(any())).thenAnswer(inv -> Optional.of(approvedEntity(inv.getArgument(0))));
        // Target-market gate (Track 5-1): an unrestricted test asset (no target market
        // configured) so the gate is a no-op unless a test explicitly restricts it.
        lenient().when(assetRepository.findById(ASSET_ID)).thenAnswer(inv -> Optional.of(asset()));
        lenient().when(encumbrance.encumbered(any(), any())).thenReturn(BigDecimal.ZERO);
        // The seller's register entry: active and well funded unless a test says otherwise.
        lenient().when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenAnswer(inv -> {
            AssetHolder h = sellerHolder(BigDecimal.valueOf(1000));
            h.setId(HOLDER_ID);
            return Optional.of(h);
        });
        lenient().when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any())).thenReturn(BigDecimal.ZERO);
        // 5C-03: only wallets bound to the entity may receive registered securities. The addresses the
        // tests below use are declared as bound member wallets of every entity.
        lenient().when(orgMemberWalletRepository.findActiveByLegalEntityId(any())).thenAnswer(inv ->
                java.util.stream.Stream.of("11", "22", "33", "44", "55", "66", "77", "aa", "bb", "cc", "dd", "ee", "ff")
                        .map(b -> {
                            var w = new de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet();
                            w.setWalletAddress("0x" + b.repeat(20));
                            return w;
                        }).toList());
    }

    private static LegalEntity approvedEntity(UUID id) {
        LegalEntity entity = new LegalEntity();
        entity.setId(id);
        entity.setKycStatus(KycStatus.APPROVED);
        return entity;
    }

    private static AssetHolder sellerHolder(BigDecimal nominal) {
        AssetHolder h = new AssetHolder();
        h.setInvestorId(SELLER);
        h.setAssetId(ASSET_ID);
        h.setNominalAmount(nominal);
        h.setWalletAddress("0x" + "aa".repeat(20));
        return h;
    }

    private static Asset asset() {
        Asset a = new Asset();
        a.setId(ASSET_ID);
        a.setAssetNumber("AN-1");
        a.setName("Test Bond");
        a.setStatus(AssetStatus.ISSUED);
        a.setCurrency("EUR");
        return a;
    }

    private static CompanyTraderSettings settings(boolean immediateSettlement, PaymentOption defaultOption) {
        CompanyTraderSettings s = new CompanyTraderSettings();
        s.setDefaultPaymentOption(defaultOption);
        s.setImmediateSettlementEnabled(immediateSettlement);
        return s;
    }

    // ── ensureTradingEnabled guard ────────────────────────────────────────────

    @Test
    void everyOperation_rejectsWhenTradingDisabled() {
        tradingProperties.setEnabled(false);

        assertThatThrownBy(() -> service.listSellableHoldings(SELLER))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── createListing ─────────────────────────────────────────────────────────

    @Test
    void createListing_rejectsAHolderThatDoesNotBelongToTheCaller() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        holder.setInvestorId(UUID.randomUUID()); // someone else
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, true, null);

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createListing_rejectsQuantityExceedingAvailableHoldings() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.valueOf(11), BigDecimal.TEN, true, null);

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available for listing");
    }

    @Test
    void createListing_rejectsZeroOrNegativePrice() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.ZERO, true, null);

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createListing_rejectsWhenNoPaymentOptionChosenAndNotUsingCompanyDefault() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false, List.of());

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment option");
    }

    @Test
    void createListing_succeeds_usesSimulatedVenueAndPublishesEvent() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA));

        var response = service.createListing(SELLER, UUID.randomUUID(), req);

        assertThat(response.venueCode()).isEqualTo(TradingVenueCode.SIMULATED);
        assertThat(response.quantityAvailable()).isEqualByComparingTo("5");
        verify(eventPublisher).publishEvent(any(TradeListingCreatedEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("createListing refuses to list a holding under an active lockup (Track 5-2)")
    void createListing_refusesWhenSellerIsLockedUp() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(investorLimitGate.isLockedUp(ASSET_ID, SELLER)).thenReturn(true);
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA));

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("lockup");
        verify(tradeListingRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("createListing surfaces the most recent settled trade price as a reference")
    void createListing_populatesLastTradePrice_whenASettledExecutionExistsForTheAsset() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        TradeExecution settled = new TradeExecution();
        settled.setUnitPrice(BigDecimal.valueOf(12.5));
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.of(settled));
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA));

        var response = service.createListing(SELLER, UUID.randomUUID(), req);

        assertThat(response.lastTradePrice()).isEqualByComparingTo("12.5");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("createListing leaves lastTradePrice null when the asset has never settled a trade")
    void createListing_leavesLastTradePriceNull_whenNoSettledExecutionExists() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.empty());
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA));

        var response = service.createListing(SELLER, UUID.randomUUID(), req);

        assertThat(response.lastTradePrice()).isNull();
    }

    // ── cancelListing ─────────────────────────────────────────────────────────

    @Test
    void cancelListing_rejectsCancellingSomeoneElsesListing() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = new TradeListing();
        listing.setSellerEntityId(UUID.randomUUID());
        when(tradeListingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service.cancelListing(SELLER, UUID.randomUUID(), listingId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancelListing_isANoOpForAnAlreadyTerminalListing() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = new TradeListing();
        listing.setSellerEntityId(SELLER);
        listing.setStatus(ListingStatus.FILLED);
        when(tradeListingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        service.cancelListing(SELLER, UUID.randomUUID(), listingId);

        verify(tradeListingRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelListing_cancelsAnOpenListingAndPublishesEvent() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = new TradeListing();
        listing.setSellerEntityId(SELLER);
        listing.setStatus(ListingStatus.OPEN);
        when(tradeListingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        service.cancelListing(SELLER, UUID.randomUUID(), listingId);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(TradeListingCancelledEvent.class));
    }

    // ── Phase 5 K2: currency, rounding, related party, perimeter ──────────────

    private BuyTradingOfferRequest sepaBuy(BigDecimal qty) {
        return new BuyTradingOfferRequest(qty, OrderType.MARKET, null, PaymentOption.OFFCHAIN_SEPA,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "ff".repeat(20));
    }

    @Test
    void buy_roundsTotalToTheCurrencyMinorUnitAndStoresTheRounding() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, new BigDecimal("33.333333333333333333"), Set.of(PaymentOption.OFFCHAIN_SEPA));
        listing.setCurrency("EUR");
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, sepaBuy(BigDecimal.TEN));

        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.totalPrice()).isEqualByComparingTo("333.33");
        assertThat(response.totalPrice().scale()).isEqualTo(2);
        assertThat(response.totalPriceUnrounded()).isEqualByComparingTo("333.33333333333333333");
        assertThat(response.priceRoundingScale()).isEqualTo((short) 2);
        assertThat(response.priceRoundingMode()).isEqualTo("HALF_EVEN");
    }

    @Test
    void buy_legacyListingWithoutCurrency_keepsCurrencyNull() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.OFFCHAIN_SEPA));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, sepaBuy(BigDecimal.ONE));

        assertThat(response.currency()).isNull();
        assertThat(response.priceRoundingScale()).isNull();
    }

    @Test
    void createListing_stablecoinTakesCurrencyFromTheEnabledRail_andIso20022ShowsItNotEur() {
        var rail = new de.makibytes.registerwerk.payment.api.PaymentRail();
        rail.setCode("USDC");
        rail.setRailType(de.makibytes.registerwerk.payment.api.PaymentRailType.STABLECOIN);
        rail.setCurrency("USD");
        rail.setDecimals(6);
        rail.setEnabled(true);
        when(paymentRailRepository.findByCode("USDC")).thenReturn(Optional.of(rail));
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false,
                List.of(PaymentOption.STABLECOIN), false, null, "USDC", null);

        service.createListing(SELLER, UUID.randomUUID(), req);

        var captor = ArgumentCaptor.forClass(TradeListing.class);
        verify(tradeListingRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().getPaymentRailCode()).isEqualTo("USDC");

        TradeListing listing = captor.getValue();
        listing.setStatus(ListingStatus.OPEN);
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(paymentRailRepository.findByCode("USDC")).thenReturn(Optional.of(rail));
        var exec = service.buy(BUYER, UUID.randomUUID(), listingId, new BuyTradingOfferRequest(BigDecimal.ONE,
                OrderType.MARKET, null, PaymentOption.STABLECOIN, WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "ff".repeat(20)));
        assertThat(exec.paymentRailCode()).isEqualTo("USDC");
        assertThat(exec.priceRoundingScale()).isEqualTo((short) 6);

        var saved = ArgumentCaptor.forClass(TradeExecution.class);
        verify(tradeExecutionRepository).save(saved.capture());
        String xml = new String(Iso20022SettlementConfirmationRenderer.render(UUID.randomUUID(), saved.getValue(), null, null));
        assertThat(xml).contains("Ccy=\"USD\"").doesNotContain("Ccy=\"EUR\"").contains("payment rail USDC");
    }

    @Test
    void iso20022_legacyTradeWithoutCurrency_neverClaimsEur() {
        TradeExecution e = new TradeExecution();
        e.setSellerEntityId(SELLER);
        e.setBuyerEntityId(BUYER);
        e.setUnitPrice(BigDecimal.ONE);
        e.setTotalPrice(BigDecimal.ONE);
        String xml = new String(Iso20022SettlementConfirmationRenderer.render(UUID.randomUUID(), e, null, null));
        assertThat(xml).doesNotContain("Ccy=").contains("Currency not recorded");
    }

    @Test
    void createListing_rejectsNativeChainCurrencyAndCurrencyOutsideTheFiatSet() {
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        CreateTradeListingRequest nativeReq = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false,
                List.of(PaymentOption.NATIVE_CHAIN_CURRENCY));
        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), nativeReq))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Native chain currency");
        CreateTradeListingRequest usd = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false,
                List.of(PaymentOption.OFFCHAIN_SEPA), false, "USD", null, null);
        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), usd))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not accepted");
    }

    @Test
    void buy_relatedParties_areBlockedAndAlerted() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.OFFCHAIN_SEPA));
        listing.setCurrency("EUR");
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(relatedPartyCheck.check(eq(BUYER), eq(SELLER), any(), any())).thenReturn(List.of("SHARED_BENEFICIAL_OWNER"));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, sepaBuy(BigDecimal.ONE)))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("related parties");
        verify(relatedPartyAlerts).blocked(any(), any(), eq(BUYER), eq(SELLER), eq(List.of("SHARED_BENEFICIAL_OWNER")));
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    void buy_relatedParties_whenAllowed_areFlaggedAndExcludedFromReferencePrice() {
        tradingProperties.setAllowRelatedPartyTrades(true);
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.OFFCHAIN_SEPA));
        listing.setCurrency("EUR");
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(relatedPartyCheck.check(eq(BUYER), eq(SELLER), any(), any())).thenReturn(List.of("SHARED_WALLET"));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, sepaBuy(BigDecimal.ONE));

        assertThat(response.relatedParty()).isTrue();
        assertThat(response.relatedPartyReasons()).isEqualTo("SHARED_WALLET");
        verify(tradeExecutionRepository, never()).findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(any(), any());
    }

    @Test
    void buy_targetedListing_isInvisibleToOtherBuyers() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.OFFCHAIN_SEPA));
        listing.setTargetEntityId(UUID.randomUUID());
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, sepaBuy(BigDecimal.ONE)))
                .isInstanceOf(RuntimeException.class);
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    void bilateralOnly_requiresATargetOnNewListings() {
        tradingProperties.setVenueClassification(TradingProperties.VenueClassification.BILATERAL_ONLY);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false,
                List.of(PaymentOption.OFFCHAIN_SEPA));
        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targetEntityId");
    }

    @Test
    void demoOnlyClassification_refusesListingAndBuyInProductionMode() {
        tradingProperties.setProductionMode(true);
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false,
                List.of(PaymentOption.OFFCHAIN_SEPA));
        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("not an authorised trading venue");
        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), UUID.randomUUID(), sepaBuy(BigDecimal.ONE)))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class);
    }

    @Test
    void priceCollar_rejectsListingFarFromLastUnrelatedPrice() {
        tradingProperties.setMaxPriceDeviationBps(500);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        TradeExecution last = new TradeExecution();
        last.setUnitPrice(new BigDecimal("100"));
        last.setCurrency("EUR");
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.of(last));
        CreateTradeListingRequest far = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, new BigDecimal("120"), false,
                List.of(PaymentOption.OFFCHAIN_SEPA));
        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), far))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bps");
        CreateTradeListingRequest near = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, new BigDecimal("103"), false,
                List.of(PaymentOption.OFFCHAIN_SEPA));
        assertThat(service.createListing(SELLER, UUID.randomUUID(), near).currency()).isEqualTo("EUR");
    }

    // ── buy — validation ──────────────────────────────────────────────────────

    private TradeListing openListing(BigDecimal quantityAvailable, BigDecimal price, Set<PaymentOption> options) {
        TradeListing listing = new TradeListing();
        listing.setSellerEntityId(SELLER);
        listing.setSellerHolderId(HOLDER_ID);
        listing.setAssetId(ASSET_ID);
        listing.setVenueCode(TradingVenueCode.SIMULATED);
        listing.setStatus(ListingStatus.OPEN);
        listing.setQuantityAvailable(quantityAvailable);
        listing.setPricePerUnit(price);
        listing.setAllowedPaymentOptions(options);
        return listing;
    }

    @Test
    void buy_rejectsBuyingYourOwnListing() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN, null, null, null);

        assertThatThrownBy(() -> service.buy(SELLER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own listing");
    }

    @Test
    void buy_rejectsACancelledListing() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setStatus(ListingStatus.CANCELLED);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN, null, null, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer available");
    }

    @Test
    void buy_rejectsQuantityAboveAvailable() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.ONE, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(BigDecimal.TEN, OrderType.MARKET, null, PaymentOption.STABLECOIN, null, null, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("units are available");
    }

    @Test
    void buy_limitOrder_rejectsWhenListingPriceExceedsLimit() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.valueOf(100), Set.of(PaymentOption.STABLECOIN))));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.LIMIT, BigDecimal.valueOf(50), PaymentOption.STABLECOIN, null, null, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds your limit price");
    }

    @Test
    void buy_rejectsUnsupportedOrderTypesOnTheSimulatedVenue() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(BigDecimal.ONE, OrderType.FOK, null, PaymentOption.STABLECOIN, null, null, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MARKET and LIMIT");
    }

    @Test
    void buy_rejectsAPaymentOptionNotAcceptedByTheSeller() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.OFFCHAIN_SEPA, null, null, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not accepted");
    }

    @Test
    void buy_defaultsToTheFirstAllowedPaymentOptionWhenOmitted() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.CBMT))));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, null, WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "cc".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.paymentOption()).isEqualTo(PaymentOption.CBMT);
    }


    private void givenConfirmedDeployment() {
        de.makibytes.registerwerk.deployment.api.AssetDeployment d = new de.makibytes.registerwerk.deployment.api.AssetDeployment();
        d.setDeploymentStatus(de.makibytes.registerwerk.deployment.api.AssetDeployment.DeploymentStatus.CONFIRMED);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(d));
    }

    @Test
    void settleRefusedForDeployedAsset() {
        givenConfirmedDeployment();
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.valueOf(2), Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.valueOf(4), OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "dd".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class)
                .hasMessageContaining("On-chain settlement required");
        verify(assetHolderRepository, never()).save(any());
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    void createListingRefusedForDeployedAssetUnlessFlagEnabled() {
        givenConfirmedDeployment();
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA));

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);

        // demo flag on: the listing goes through
        tradingProperties.setOffchainSettlementOnDeployedAssets(true);
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        assertThat(service.createListing(SELLER, UUID.randomUUID(), req).venueCode()).isEqualTo(TradingVenueCode.SIMULATED);
    }

    // ── buy — SIMULATED venue settlement ──────────────────────────────────────

    @Test
    void buy_simulatedVenue_sellerOptedInAndDemoOn_settlesAndCreditsNewBuyerHolder() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.valueOf(2), Set.of(PaymentOption.STABLECOIN));
        listing.setAllowInstantSettlement(true); // the SELLER opted in (5A-01)
        tradingProperties.setDemoInstantSettlement(true);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(100));
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.valueOf(4), OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "dd".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(response.instantSettlement()).isTrue();
        assertThat(response.side()).isEqualTo("BUY");
        assertThat(seller.getNominalAmount()).isEqualByComparingTo("96"); // 100 - 4
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("6"); // 10 - 4
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.PARTIALLY_FILLED);
        verify(eventPublisher).publishEvent(any(HolderEnteredEvent.class));
        verify(eventPublisher).publishEvent(any(TradeExecutedEvent.class));
    }

    @Test
    void buy_simulatedVenue_sellerOptedInAndDemoOn_creditsExistingBuyerHolder() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setAllowInstantSettlement(true); // the SELLER opted in (5A-01)
        tradingProperties.setDemoInstantSettlement(true);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(100));
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));

        AssetHolder existingBuyerHolder = new AssetHolder();
        existingBuyerHolder.setId(UUID.randomUUID());
        existingBuyerHolder.setInvestorId(BUYER);
        existingBuyerHolder.setAssetId(ASSET_ID);
        existingBuyerHolder.setNominalAmount(BigDecimal.valueOf(10));
        String walletAddress = "0x" + "ee".repeat(20);
        existingBuyerHolder.setWalletAddress(walletAddress);
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(ASSET_ID, walletAddress))
                .thenReturn(Optional.of(existingBuyerHolder));

        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.valueOf(3), OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, walletAddress);

        service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(existingBuyerHolder.getNominalAmount()).isEqualByComparingTo("13"); // 10 + 3
        // Both sides of the trade published a HolderRegisterChangedEvent: the seller's
        // holder shrank and the buyer's pre-existing holder grew (not a new holder).
        verify(eventPublisher, times(2)).publishEvent(any(HolderRegisterChangedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(HolderEnteredEvent.class));
    }

    @Test
    void buy_simulatedVenue_deferredSettlement_leavesExecutionPending() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "ff".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-01: the buyer's legacy company flag never moves the seller's register - default buy stays PENDING")
    void buy_ignoresTheBuyersLegacyImmediateSettlementFlag() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "ff".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(response.instantSettlement()).isFalse();
        verifyNoInteractions(settingsRepository);
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-01: seller opt-in alone is not enough - the demo property must be on")
    void buy_sellerOptInWithoutDemoProperty_staysPending() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setAllowInstantSettlement(true);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "ff".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-01: a listing cannot offer instant settlement while the demo property is off")
    void createListing_refusesInstantSettlementWithoutDemoProperty() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN), true);

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("demonstration");
    }

    @Test
    void buy_simulatedVenue_sellerNoLongerHoldingEnoughUnits_throws() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(2)); // less than the 10 units the listing still offers
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any())).thenReturn(BigDecimal.TEN);
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.valueOf(5), OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "11".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer holds enough unencumbered units");
    }

    // ── buy — non-SIMULATED venue dispatch ────────────────────────────────────

    @Test
    void buy_nonSimulatedVenue_rejectedByAdapter_persistsFailedExecutionInsteadOfThrowing() {
        // Previously this threw and rolled back the whole transaction — the rejected attempt
        // left no record anywhere. Now it persists a FAILED TradeExecution (with a reason) and
        // does NOT decrement the listing's available quantity, since nothing was actually filled.
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setVenueCode(TradingVenueCode.ASSETERA);
        BigDecimal availableBefore = listing.getQuantityAvailable();
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(venueAdapter.venueCode()).thenReturn(TradingVenueCode.ASSETERA);
        when(venueAdapter.execute(any())).thenReturn(TradingVenueExecutionResult.rejected("insufficient liquidity"));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "22".repeat(20));

        TradeExecutionResponse response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo(availableBefore);
        verify(tradeExecutionRepository).save(argThat(e -> e.getFailureReason() != null
                && e.getFailureReason().contains("insufficient liquidity")));
    }

    @Test
    void buy_nonSimulatedVenue_noAdapterConfigured_throws() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setVenueCode(TradingVenueCode.TALOS);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(venueAdapter.venueCode()).thenReturn(TradingVenueCode.ASSETERA);
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "33".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No active adapter");
    }

    @Test
    void buy_nonSimulatedVenue_acceptedByAdapter_leavesExecutionPending() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setVenueCode(TradingVenueCode.ASSETERA);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(venueAdapter.venueCode()).thenReturn(TradingVenueCode.ASSETERA);
        when(venueAdapter.execute(any())).thenReturn(TradingVenueExecutionResult.pending("EXT-1"));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "44".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("buy fails clearly (not a bare 404) when the listingId belongs to a live external-venue offer")
    void buy_unknownListingId_thatMatchesALiveExternalOffer_failsWithClearError() {
        UUID externalListingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(externalListingId)).thenReturn(Optional.empty());
        when(venueAdapter.venueCode()).thenReturn(TradingVenueCode.TALOS);
        TradingVenueOffer liveOffer = new TradingVenueOffer(
                externalListingId, TradingVenueCode.TALOS, "Talos", ASSET_ID, "AN-1", "Test Bond", null,
                TradingAssetType.BOND, de.makibytes.registerwerk.deployment.api.TokenStandard.ERC20, null,
                BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN), List.of(OrderType.MARKET),
                java.time.Instant.now());
        when(venueAdapter.searchOffers(any())).thenReturn(List.of(liveOffer));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "55".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), externalListingId, req))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TALOS");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("buy still throws a plain not-found for a listingId that matches nothing anywhere")
    void buy_trulyUnknownListingId_throwsEntityNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());
        when(venueAdapter.venueCode()).thenReturn(TradingVenueCode.TALOS);
        when(venueAdapter.searchOffers(any())).thenReturn(List.of());
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "66".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), unknownId, req))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── listMarketplaceOffers ─────────────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.DisplayName("listMarketplaceOffers surfaces the most recent settled trade price per asset")
    void listMarketplaceOffers_populatesLastTradePrice_whenASettledExecutionExistsForTheAsset() {
        UUID listingId = UUID.randomUUID();
        TradingVenueOffer offer = new TradingVenueOffer(
                listingId, TradingVenueCode.TALOS, "Talos", ASSET_ID, "AN-1", "Test Bond", null,
                TradingAssetType.BOND, de.makibytes.registerwerk.deployment.api.TokenStandard.ERC20, null,
                BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN), List.of(OrderType.MARKET),
                java.time.Instant.now());
        when(venueAdapter.searchOffers(any())).thenReturn(List.of(offer));
        TradeListing underlyingListing = new TradeListing();
        underlyingListing.setSellerEntityId(SELLER);
        when(tradeListingRepository.findById(listingId)).thenReturn(Optional.of(underlyingListing));
        TradeExecution settled = new TradeExecution();
        settled.setUnitPrice(BigDecimal.valueOf(9.75));
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.of(settled));

        var offers = service.listMarketplaceOffers(BUYER, null, null, null, null, null, null, null);

        assertThat(offers).hasSize(1);
        assertThat(offers.getFirst().lastTradePrice()).isEqualByComparingTo("9.75");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("listMarketplaceOffers leaves lastTradePrice null when the asset has never settled a trade")
    void listMarketplaceOffers_leavesLastTradePriceNull_whenNoSettledExecutionExists() {
        UUID listingId = UUID.randomUUID();
        TradingVenueOffer offer = new TradingVenueOffer(
                listingId, TradingVenueCode.TALOS, "Talos", ASSET_ID, "AN-1", "Test Bond", null,
                TradingAssetType.BOND, de.makibytes.registerwerk.deployment.api.TokenStandard.ERC20, null,
                BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN), List.of(OrderType.MARKET),
                java.time.Instant.now());
        when(venueAdapter.searchOffers(any())).thenReturn(List.of(offer));
        TradeListing underlyingListing = new TradeListing();
        underlyingListing.setSellerEntityId(SELLER);
        when(tradeListingRepository.findById(listingId)).thenReturn(Optional.of(underlyingListing));
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.empty());

        var offers = service.listMarketplaceOffers(BUYER, null, null, null, null, null, null, null);

        assertThat(offers).hasSize(1);
        assertThat(offers.getFirst().lastTradePrice()).isNull();
    }

    // ── resolveWallet (via buy) ───────────────────────────────────────────────

    @Test
    void buy_walletPreference_endpoint_validatesOwnershipAndUsesItsAddress() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        UUID endpointId = UUID.randomUUID();
        AddressEndpoint endpoint = new AddressEndpoint();
        endpoint.setOwnerType(AddressEndpoint.OwnerType.ENTITY);
        endpoint.setOwnerId(BUYER);
        endpoint.setAddress("0x" + "55".repeat(20));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.ENDPOINT, endpointId, null);

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.walletAddress()).isEqualTo(endpoint.getAddress());
    }

    @Test
    void buy_walletPreference_endpoint_rejectsAnEndpointOwnedByAnotherEntity() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        UUID endpointId = UUID.randomUUID();
        AddressEndpoint endpoint = new AddressEndpoint();
        endpoint.setOwnerType(AddressEndpoint.OwnerType.ENTITY);
        endpoint.setOwnerId(UUID.randomUUID()); // not the buyer
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.ENDPOINT, endpointId, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void buy_walletPreference_customAddress_requiresNonBlankAddress() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "  ");

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wallet address is required");
    }

    @Test
    void buy_walletPreference_assetTypeDefault_fallsBackToGlobalDefaultWhenNoTypeSpecificOneExists() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setAssetType(TradingAssetType.EQUITY);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        CompanyTraderWalletDefault globalDefault = new CompanyTraderWalletDefault();
        globalDefault.setTargetType(WalletTargetType.CUSTOM_ADDRESS);
        globalDefault.setWalletAddress("0x" + "66".repeat(20));
        when(walletDefaultRepository.findByLegalEntityIdAndAssetType(BUYER, TradingAssetType.EQUITY))
                .thenReturn(Optional.empty());
        when(walletDefaultRepository.findByLegalEntityIdAndAssetType(BUYER, null))
                .thenReturn(Optional.of(globalDefault));

        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.ASSET_TYPE_DEFAULT, null, null);

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.walletAddress()).isEqualTo(globalDefault.getWalletAddress());
    }

    @Test
    void buy_walletPreference_noDefaultConfigured_throws() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(walletDefaultRepository.findByLegalEntityIdAndAssetType(eq(BUYER), any())).thenReturn(Optional.empty());

        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.GLOBAL_DEFAULT, null, null);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No company wallet default");
    }

    // ── settlePendingTrade ────────────────────────────────────────────────────

    @Test
    void settlePendingTrade_rejectsANonBuyerTryingToSettle() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.settlePendingTrade(SELLER, UUID.randomUUID(), executionId, "tx-ref"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void settlePendingTrade_isIdempotentWhenAlreadySettled() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "tx-ref");

        verify(tradeExecutionRepository, never()).save(any());
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("settlePendingTrade declares payment but does NOT credit the register — the seller must confirm (Track 4-2)")
    void settlePendingTrade_declaresPaymentAndAwaitsSellerConfirmation() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSellerHolderId(HOLDER_ID);
        execution.setAssetId(ASSET_ID);
        execution.setExecutedQuantity(BigDecimal.valueOf(3));
        execution.setWalletAddress("0x" + "77".repeat(20));
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "0xtxhash123");

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.AWAITING_SELLER_CONFIRMATION);
        assertThat(execution.getSettledAt()).isNull();
        assertThat(execution.getBuyerHolderId()).isNull();
        assertThat(execution.getPaymentReference()).isEqualTo("0xtxhash123");
        assertThat(execution.getPaymentDeclaredAt()).isNotNull();
        verify(assetHolderRepository, never()).save(any());
        verify(eventPublisher).publishEvent(any(TradePaymentDeclaredEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("settlePendingTrade requires a payment reference ")
    void settlePendingTrade_rejectsBlankPaymentReference() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment reference");
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    void settlePendingTrade_isIdempotentWhenAlreadyAwaitingConfirmation() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSettlementStatus(SettlementStatus.AWAITING_SELLER_CONFIRMATION);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "tx-ref");

        verify(tradeExecutionRepository, never()).save(any());
        verifyNoInteractions(assetHolderRepository, eventPublisher);
    }

    // ── confirmPaymentReceived / disputePayment (seller-side, Track 4-2) ─────────

    private TradeExecution awaitingConfirmationExecution() {
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSellerHolderId(HOLDER_ID);
        execution.setAssetId(ASSET_ID);
        execution.setExecutedQuantity(BigDecimal.valueOf(3));
        execution.setWalletAddress("0x" + "77".repeat(20));
        execution.setSettlementStatus(SettlementStatus.AWAITING_SELLER_CONFIRMATION);
        execution.setPaymentReference("tx-ref");
        return execution;
    }

    @Test
    void confirmPaymentReceived_rejectsNonSeller() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.confirmPaymentReceived(BUYER, UUID.randomUUID(), executionId))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    void confirmPaymentReceived_rejectsWhenNotAwaitingConfirmation() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirmPaymentReceived_isIdempotentWhenAlreadySettled() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        verify(tradeExecutionRepository, never()).save(any());
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirmPaymentReceived is the only path that credits the register (Track 4-2)")
    void confirmPaymentReceived_settlesAndCreditsBuyer() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(50));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(execution.getSettledAt()).isNotNull();
        assertThat(execution.getBuyerHolderId()).isNotNull();
        assertThat(seller.getNominalAmount()).isEqualByComparingTo("47"); // 50 - 3
        verify(finalityGate).require(
                eq(de.makibytes.registerwerk.finality.api.GatedOperation.TRADE_SETTLEMENT_CONFIRM),
                eq(ASSET_ID), any(), eq(de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED));
        verify(eventPublisher).publishEvent(any(TradePaymentConfirmedEvent.class));
    }

    @Test
    void confirmPaymentReceived_finalityFreezePreventsEitherBalanceMutation() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(50));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));
        doThrow(new IllegalStateException("unresolved compensation"))
                .when(finalityGate).require(any(), any(), any(), any());

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .hasMessageContaining("unresolved compensation");

        assertThat(seller.getNominalAmount()).isEqualByComparingTo("50");
        verify(assetHolderRepository, never()).save(any());
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    void disputePayment_rejectsNonSeller() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.disputePayment(BUYER, UUID.randomUUID(), executionId, "never received"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disputePayment_requiresReason() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.disputePayment(SELLER, UUID.randomUUID(), executionId, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disputePayment_rejectsWhenNotAwaitingConfirmation() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.disputePayment(SELLER, UUID.randomUUID(), executionId, "never received"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** B1: the persisted/visible reason is fixed text; the detailed gate output only travels in the (audited) event. */
    private void assertUnresolvedDetailOnlyInAudit(TradeExecution execution, String detailFragment) {
        assertThat(execution.getUnresolvedReason()).contains("GATE_FAILED_AT_CONFIRM").doesNotContain(detailFragment);
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(TradePaymentUnresolvedEvent.class::isInstance)
                .map(TradePaymentUnresolvedEvent.class::cast)
                .anySatisfy(e -> assertThat(e.reason()).contains(detailFragment));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("B1: the counterparty's gate failure is generic for the caller (buy), the caller's own keeps detail")
    void buy_counterpartyGateFailureIsGeneric_ownFailureKeepsDetail() {
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.PENDING);
        UUID executionId = UUID.randomUUID();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        // declare by the buyer; the SELLER fails screening + Sperrvermerk
        org.mockito.Mockito.lenient().doThrow(new de.makibytes.registerwerk.shared.ComplianceGateException(
                "Entity " + SELLER + " is not eligible for trade settlement: has an unresolved sanctions-screening result"))
                .when(partyEligibilityGate).require(eq(SELLER), any(), any());
        assertThatThrownBy(() -> service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "REF-1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessage("The counterparty is currently not eligible for this trade.");

        // the buyer's own failure is shown to the buyer in full
        org.mockito.Mockito.reset(partyEligibilityGate);
        org.mockito.Mockito.lenient().doThrow(new de.makibytes.registerwerk.shared.ComplianceGateException("Entity is not eligible: has KYC status IN_PROGRESS"))
                .when(partyEligibilityGate).require(eq(BUYER), any(), any());
        assertThatThrownBy(() -> service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "REF-1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("KYC status IN_PROGRESS");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("B1: seller confirms while the BUYER fails the gate - trade goes unresolved, no buyer detail in unresolved_reason or responses")
    void confirm_buyerGateFailureDetailStaysOutOfPartyVisibleFields() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        doThrow(new de.makibytes.registerwerk.shared.ComplianceGateException("Entity is not eligible: has KYC status REJECTED; Sperrvermerk"))
                .when(partyEligibilityGate).require(eq(BUYER), any(), any());

        TradeExecutionResponse response = service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(response.unresolvedReason()).isNull(); // viewers never see the reason text
        assertUnresolvedDetailOnlyInAudit(execution, "REJECTED");
        assertThat(execution.getUnresolvedReason()).doesNotContain("Sperrvermerk");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("disputePayment parks the trade as PAYMENT_UNRESOLVED and keeps the units reserved (5A-03)")
    void disputePayment_movesTradeToUnresolvedAndKeepsReservation() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setListingId(UUID.randomUUID());
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        service.disputePayment(SELLER, UUID.randomUUID(), executionId, "never received");

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertThat(execution.getDisputeReason()).isEqualTo("never received");
        assertThat(execution.getUnresolvedAt()).isNotNull();
        assertThat(execution.getUnresolvedReason()).contains("SELLER_DISPUTE").doesNotContain("never received");
        // the listing is NOT re-offered and the register is untouched
        verify(tradeListingRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(assetHolderRepository);
        verify(eventPublisher).publishEvent(any(TradePaymentDisputedEvent.class));
        verify(eventPublisher).publishEvent(any(TradePaymentUnresolvedEvent.class));
    }

    // ── settlement compliance gate (KYC / screening / Sperrvermerk) — now exercised
    //    via confirmPaymentReceived, the only path that reaches settleExecution() ────

    @Test
    @org.junit.jupiter.api.DisplayName("confirm: a party gate failing at confirm time hands the PAID trade to the operator instead of throwing (5A-03)")
    void confirmPaymentReceived_partyGateFailureMovesTradeToUnresolved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        doThrow(new de.makibytes.registerwerk.shared.ComplianceGateException("Entity is not eligible: has KYC status IN_PROGRESS"))
                .when(partyEligibilityGate).require(eq(BUYER), any(), any());

        TradeExecutionResponse response = service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertUnresolvedDetailOnlyInAudit(execution, "KYC");
        verify(assetHolderRepository, never()).save(any());
        verify(eventPublisher).publishEvent(any(TradePaymentUnresolvedEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirm consults the shared party gate for buyer (with settlement wallet) and seller")
    void confirmPaymentReceived_consultsPartyGateForBothSides() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(50));
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        verify(partyEligibilityGate).require(eq(BUYER), eq(execution.getWalletAddress()), any());
        verify(partyEligibilityGate).require(eq(SELLER), eq(seller.getWalletAddress()), any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirmPaymentReceived: a buyer outside the asset's MiFID target market makes the trade unresolved (Track 5-1, 5A-03)")
    void confirmPaymentReceived_buyerOutsideTargetMarketBecomesUnresolved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        Asset restricted = asset();
        restricted.setTargetMarketCategories(java.util.Set.of(de.makibytes.registerwerk.customer.api.ClientCategory.PROFESSIONAL));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(restricted));

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertUnresolvedDetailOnlyInAudit(execution, "target market");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirmPaymentReceived: a buyer above the maximum holding makes the trade unresolved (Track 5-2, 5A-03)")
    void confirmPaymentReceived_buyerAboveMaxHoldingBecomesUnresolved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        when(investorLimitGate.effectiveMaxHolding(any(), eq(BUYER))).thenReturn(BigDecimal.TEN);
        AssetHolder existingBuyerHolder = new AssetHolder();
        existingBuyerHolder.setInvestorId(BUYER);
        existingBuyerHolder.setNominalAmount(BigDecimal.valueOf(9)); // + 3 (executedQuantity) > 10
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(ASSET_ID, execution.getWalletAddress()))
                .thenReturn(Optional.of(existingBuyerHolder));

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertUnresolvedDetailOnlyInAudit(execution, "maximum");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirm on a suspended asset hands the paid trade to the operator (5A-05)")
    void confirmPaymentReceived_suspendedAssetBecomesUnresolved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        Asset suspended = asset();
        suspended.setStatus(AssetStatus.SUSPENDED);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(suspended));

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertUnresolvedDetailOnlyInAudit(execution, "SUSPENDED");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirm against a removed seller register entry never debits it (5A-04)")
    void confirmPaymentReceived_removedSellerEntryBecomesUnresolvedAndIsNotDebited() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.empty());

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertUnresolvedDetailOnlyInAudit(execution, "removed");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirm refuses units the seller has pledged elsewhere (encumbrance SPI, 5A-09)")
    void confirmPaymentReceived_encumberedUnitsBecomeUnresolved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(5))));
        when(encumbrance.encumbered(SELLER, ASSET_ID)).thenReturn(BigDecimal.valueOf(4)); // 5 - 4 = 1 < 3

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertUnresolvedDetailOnlyInAudit(execution, "unencumbered");
    }

    // ── cancelPendingTrade / refundSettledTrade / timeoutStuckPendingTrades ──────

    @Test
    void cancelPendingTrade_cancelsAndRestoresListingQuantity() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setListingId(UUID.randomUUID());
        execution.setExecutedQuantity(BigDecimal.valueOf(3));
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        TradeListing listing = openListing(BigDecimal.valueOf(2), BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setStatus(ListingStatus.PARTIALLY_FILLED);
        when(tradeListingRepository.findByIdForUpdate(execution.getListingId())).thenReturn(Optional.of(listing));

        TradeExecutionResponse response = service.cancelPendingTrade(BUYER, UUID.randomUUID(), executionId, "buyer changed their mind");

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(execution.getFailureReason()).isEqualTo("buyer changed their mind");
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("5"); // 2 + 3
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.OPEN);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("cancelPendingTrade publishes an audit event ")
    void cancelPendingTrade_publishesAuditEvent() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        org.springframework.test.util.ReflectionTestUtils.setField(execution, "id", executionId);
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setExecutedQuantity(BigDecimal.valueOf(3));
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        UUID actorId = UUID.randomUUID();
        service.cancelPendingTrade(BUYER, actorId, executionId, "buyer changed their mind");

        ArgumentCaptor<TradePendingCancelledEvent> captor = ArgumentCaptor.forClass(TradePendingCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().executionId()).isEqualTo(executionId);
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().reason()).isEqualTo("buyer changed their mind");
    }

    @Test
    void cancelPendingTrade_rejectsWhenNotPending() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.cancelPendingTrade(BUYER, UUID.randomUUID(), executionId, "too late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelPendingTrade_rejectsNonParty() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.cancelPendingTrade(UUID.randomUUID(), UUID.randomUUID(), executionId, "not my trade"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void refundSettledTrade_marksRefundedOnlyWhenSettled() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        UUID approverId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TradeExecutionResponse response = service.refundSettledTrade(actorId, executionId, "compliance clawback", approverId);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.REFUNDED);
        assertThat(execution.getFailureReason()).isEqualTo("compliance clawback");

        ArgumentCaptor<TradeRefundedEvent> captor = ArgumentCaptor.forClass(TradeRefundedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().executionId()).isEqualTo(executionId);
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().reason()).isEqualTo("compliance clawback");
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approverId);
    }

    @Test
    void refundSettledTrade_rejectsWhenNotSettled() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.refundSettledTrade(UUID.randomUUID(), executionId, "too early", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── renderConfirmation (Track 4-3) ───────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.DisplayName("renderConfirmation returns a non-empty PDF for a SETTLED trade the caller is party to")
    void renderConfirmation_settledTrade_returnsPdf() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setAssetName("Test Bond");
        execution.setTokenStandard(de.makibytes.registerwerk.deployment.api.TokenStandard.ERC20);
        execution.setOrderType(OrderType.MARKET);
        execution.setExecutedQuantity(BigDecimal.TEN);
        execution.setUnitPrice(BigDecimal.ONE);
        execution.setTotalPrice(BigDecimal.TEN);
        execution.setPaymentOption(PaymentOption.STABLECOIN);
        execution.setVenueCode(TradingVenueCode.SIMULATED);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(legalEntityRepository.findById(BUYER)).thenReturn(Optional.of(approvedEntity(BUYER)));
        when(legalEntityRepository.findById(SELLER)).thenReturn(Optional.of(approvedEntity(SELLER)));

        var pdf = service.renderConfirmation(BUYER, executionId);

        assertThat(pdf).isPresent();
        assertThat(pdf.get()).isNotEmpty();
        // %PDF is the standard PDF file magic-byte signature.
        assertThat(new String(pdf.get(), 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void renderConfirmation_notSettled_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThat(service.renderConfirmation(BUYER, executionId)).isEmpty();
    }

    @Test
    void renderConfirmation_callerNotAParty_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThat(service.renderConfirmation(UUID.randomUUID(), executionId)).isEmpty();
    }

    @Test
    void renderConfirmation_unknownExecution_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThat(service.renderConfirmation(BUYER, executionId)).isEmpty();
    }

    // ── renderIso20022Confirmation (Track 6-3) ───────────────────────────────────

    @Test
    @org.junit.jupiter.api.DisplayName("renderIso20022Confirmation returns a well-formed ISO 20022-shaped XML for a SETTLED trade")
    void renderIso20022Confirmation_settledTrade_returnsXml() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setAssetName("Test Bond");
        execution.setIsin("DE000TESTBD1");
        execution.setTokenStandard(de.makibytes.registerwerk.deployment.api.TokenStandard.ERC20);
        execution.setOrderType(OrderType.MARKET);
        execution.setExecutedQuantity(BigDecimal.TEN);
        execution.setUnitPrice(BigDecimal.ONE);
        execution.setTotalPrice(BigDecimal.TEN);
        execution.setPaymentOption(PaymentOption.STABLECOIN);
        execution.setVenueCode(TradingVenueCode.SIMULATED);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(legalEntityRepository.findById(BUYER)).thenReturn(Optional.of(approvedEntity(BUYER)));
        when(legalEntityRepository.findById(SELLER)).thenReturn(Optional.of(approvedEntity(SELLER)));

        var xml = service.renderIso20022Confirmation(BUYER, executionId);

        assertThat(xml).isPresent();
        String content = new String(xml.get(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("sese.032.001.13", "SctiesSttlmTxConf", "DE000TESTBD1");
    }

    @Test
    void renderIso20022Confirmation_notSettled_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThat(service.renderIso20022Confirmation(BUYER, executionId)).isEmpty();
    }

    @Test
    void renderIso20022Confirmation_callerNotAParty_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThat(service.renderIso20022Confirmation(UUID.randomUUID(), executionId)).isEmpty();
    }

    @Test
    void renderIso20022Confirmation_unknownExecution_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThat(service.renderIso20022Confirmation(BUYER, executionId)).isEmpty();
    }

    // ── Phase 5 / K1: gates before reservation, caps, seller-only cancel, operator resolution ─────

    private BuyTradingOfferRequest simpleBuy(String walletByte) {
        return new BuyTradingOfferRequest(BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + walletByte.repeat(20));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-06: an ineligible buyer is refused at buy() and NOTHING is reserved (gates before reservation)")
    void buy_ineligibleBuyer_isRefusedBeforeAnyReservation() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        doThrow(new de.makibytes.registerwerk.shared.ComplianceGateException("KYC not approved"))
                .when(partyEligibilityGate).require(eq(BUYER), any(), any());

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("ff")))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class);

        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("10");
        verify(tradeExecutionRepository, never()).save(any());
        verify(tradeListingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(TradeExecutedEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-03: buyer outside the target market cannot even reserve")
    void buy_buyerOutsideTargetMarket_isRefusedAtBuy() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        Asset restricted = asset();
        restricted.setTargetMarketCategories(java.util.Set.of(de.makibytes.registerwerk.customer.api.ClientCategory.PROFESSIONAL));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(restricted));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("ff")))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("target market");
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-05: buy on a SUSPENDED or REDEEMED asset is refused")
    void buy_suspendedOrRedeemedAsset_isRefused() {
        for (AssetStatus status : List.of(AssetStatus.SUSPENDED, AssetStatus.REDEEMED)) {
            UUID listingId = UUID.randomUUID();
            when(tradeListingRepository.findByIdForUpdate(listingId))
                    .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));
            Asset a = asset();
            a.setStatus(status);
            when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("ff")))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining(status.name());
        }
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-05: createListing on a SUSPENDED asset is refused")
    void createListing_suspendedAsset_isRefused() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        Asset a = asset();
        a.setStatus(AssetStatus.SUSPENDED);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(a));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(tradeListingRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-04: a removed register entry cannot be listed")
    void createListing_removedHolder_isRefused() {
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.empty());
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-05: bond quantities must be whole multiples of the recorded denomination")
    void createListing_bondQuantityMustBeWholeDenomination() {
        AssetHolder holder = sellerHolder(BigDecimal.valueOf(10000));
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(holder));
        Asset bond = asset();
        bond.setDenomination(BigDecimal.valueOf(1000));
        bond.setPublicData(java.util.Map.of("assetType", "Bond"));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(bond));
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.valueOf(1500), BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denomination");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-06: the 4th open reservation of a buyer is rejected")
    void buy_rejectsWhenBuyerHasTooManyOpenReservations() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(tradeExecutionRepository.countByBuyerEntityIdAndSettlementStatusIn(eq(BUYER), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("ff")))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("at most 3");
        verify(tradeExecutionRepository, never()).save(any());
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("10");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-06: one open reservation per buyer and listing")
    void buy_rejectsSecondReservationOnSameListing() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(tradeExecutionRepository.countByBuyerEntityIdAndListingIdAndSettlementStatusIn(eq(BUYER), any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("ff")))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already hold an open reservation");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-06: cool-down after a buyer cancel / lapse on the same listing")
    void buy_rejectsDuringCooldown() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(tradeExecutionRepository.existsByBuyerEntityIdAndListingIdAndBuyerCooldownUntilAfter(eq(BUYER), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("ff")))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("wait");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5C-03: a free-text CUSTOM_ADDRESS that is not bound to the entity is rejected with guidance")
    void buy_customAddressNotBoundToEntity_isRejected() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered for your company");
        verify(tradeExecutionRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5C-03: a CUSTOM_ADDRESS equal to one of the entity's own endpoints is accepted")
    void buy_customAddressMatchingOwnEndpoint_isAccepted() {
        UUID listingId = UUID.randomUUID();
        when(tradeListingRepository.findByIdForUpdate(listingId))
                .thenReturn(Optional.of(openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN))));
        AddressEndpoint endpoint = new AddressEndpoint();
        endpoint.setAddress("0x" + "99".repeat(20));
        when(endpointRepository.findByOwnerTypeAndOwnerId(AddressEndpoint.OwnerType.ENTITY, BUYER)).thenReturn(List.of(endpoint));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, simpleBuy("99"));

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-03 step 5: the seller can no longer cancel a PENDING trade; the buyer's cancel starts the cool-down")
    void cancelPendingTrade_sellerRefused_buyerStartsCooldown() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSellerEntityId(SELLER);
        execution.setExecutedQuantity(BigDecimal.ONE);
        execution.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.cancelPendingTrade(SELLER, UUID.randomUUID(), executionId, "no"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);

        service.cancelPendingTrade(BUYER, UUID.randomUUID(), executionId, "changed my mind");
        assertThat(execution.getBuyerCooldownUntil()).isAfter(java.time.Instant.now().plusSeconds(23 * 3600));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-03: declaring payment re-runs the gates - a buyer the gates now refuse is not invited to pay")
    void settlePendingTrade_declarationRunsTheGates() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.PENDING);
        execution.setPaymentReference(null);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        Asset suspended = asset();
        suspended.setStatus(AssetStatus.SUSPENDED);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "tx"))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("5A-01: saveSettings ignores the retired buyer flag and reports it as off")
    void saveSettings_ignoresImmediateSettlementFlag() {
        var request = new de.makibytes.registerwerk.trading.web.dto.UpdateCompanyTraderSettingsRequest(
                PaymentOption.OFFCHAIN_SEPA, true, List.of());
        var response = service.saveSettings(BUYER, UUID.randomUUID(), request);

        assertThat(response.immediateSettlementEnabled()).isFalse();
        ArgumentCaptor<CompanyTraderSettings> captor = ArgumentCaptor.forClass(CompanyTraderSettings.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().isImmediateSettlementEnabled()).isFalse();
    }

    // ── operator resolution of PAYMENT_UNRESOLVED ──

    private TradeExecution unresolvedExecution(UUID executionId) {
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.PAYMENT_UNRESOLVED);
        execution.setListingId(UUID.randomUUID());
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        return execution;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("operator RELEASE: FAILED, quantity restored to the listing, resolution event recorded")
    void releaseUnresolved_failsAndRestoresQuantity() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = unresolvedExecution(executionId);
        TradeListing listing = openListing(BigDecimal.valueOf(2), BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setStatus(ListingStatus.PARTIALLY_FILLED);
        when(tradeListingRepository.findByIdForUpdate(execution.getListingId())).thenReturn(Optional.of(listing));

        service.releaseUnresolved(UUID.randomUUID(), executionId, "seller bank statement", "no credit", UUID.randomUUID());

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("5");
        ArgumentCaptor<de.makibytes.registerwerk.trading.events.TradeUnresolvedResolvedEvent> captor =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.trading.events.TradeUnresolvedResolvedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("RELEASE");
        assertThat(captor.getValue().legalBasis()).isEqualTo("seller bank statement");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("operator RECORD_RETURN_OF_FUNDS: FAILED + quantity restored")
    void recordReturnOfFunds_failsAndRestoresQuantity() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = unresolvedExecution(executionId);

        service.recordReturnOfFunds(UUID.randomUUID(), executionId, "refund proof", null, UUID.randomUUID());

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(execution.getFailureReason()).contains("Return of funds");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("operator FORCE_SETTLE re-runs every gate first; a failing gate changes nothing")
    void forceSettleUnresolved_failingGateChangesNothing() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = unresolvedExecution(executionId);
        lenient().doThrow(new de.makibytes.registerwerk.shared.ComplianceGateException("blocked"))
                .when(partyEligibilityGate).require(eq(SELLER), any(), any());

        assertThatThrownBy(() -> service.forceSettleUnresolved(UUID.randomUUID(), executionId, "basis", null, UUID.randomUUID()))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("operator FORCE_SETTLE moves the register and settles when all gates pass")
    void forceSettleUnresolved_settlesWhenGatesPass() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = unresolvedExecution(executionId);
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(50));
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findActiveByIdForUpdate(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());

        var response = service.forceSettleUnresolved(UUID.randomUUID(), executionId, "basis", "evidence ok", UUID.randomUUID());

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(seller.getNominalAmount()).isEqualByComparingTo("47");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("operator actions only apply to PAYMENT_UNRESOLVED trades")
    void resolveActions_requireUnresolvedStatus() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.releaseUnresolved(UUID.randomUUID(), executionId, "b", null, null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a repeated declaration on an unresolved trade is idempotent")
    void settlePendingTrade_isIdempotentWhenUnresolved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setSettlementStatus(SettlementStatus.PAYMENT_UNRESOLVED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        var response = service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId, "again");

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
    }

    @Test
    void config_exposesDemoAndReservationSettings() {
        tradingProperties.setDemoInstantSettlement(true);
        var config = service.config();
        assertThat(config.demoInstantSettlementAvailable()).isTrue();
        assertThat(config.maxOpenReservationsPerBuyer()).isEqualTo(3);
        assertThat(config.reservationCooldownHours()).isEqualTo(24);
    }
}
