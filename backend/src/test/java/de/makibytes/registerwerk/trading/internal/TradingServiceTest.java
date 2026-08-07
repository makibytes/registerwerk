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
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCreatedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentConfirmedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDeclaredEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import de.makibytes.registerwerk.trading.events.TradePendingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePendingTimedOutEvent;
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
    @Mock private ScreeningGate screeningGate;
    @Mock private HolderBlockGate holderBlockGate;

    private TradingProperties tradingProperties;
    private TradingAssetTypeResolver tradingAssetTypeResolver;
    private TradingService service;

    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final UUID HOLDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tradingProperties = new TradingProperties();
        tradingProperties.setEnabled(true);
        tradingAssetTypeResolver = new TradingAssetTypeResolver();
        service = new TradingService(
                tradingProperties, settingsRepository, walletDefaultRepository,
                tradeListingRepository, tradeExecutionRepository, assetHolderRepository,
                assetRepository, assetDeploymentRepository, endpointRepository,
                List.of(venueAdapter), tradingAssetTypeResolver, eventPublisher,
                legalEntityRepository, suitabilityAssessmentRepository, investorLimitGate, screeningGate, holderBlockGate);
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
        lenient().when(screeningGate.hasUnresolvedHit(any())).thenReturn(false);
        lenient().when(holderBlockGate.isBlocked(any(), any())).thenReturn(false);
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
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
        CreateTradeListingRequest req = new CreateTradeListingRequest(HOLDER_ID, BigDecimal.ONE, BigDecimal.TEN, true, null);

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createListing_rejectsQuantityExceedingAvailableHoldings() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
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
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
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
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
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
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        var response = service.createListing(SELLER, UUID.randomUUID(), req);

        assertThat(response.venueCode()).isEqualTo(TradingVenueCode.SIMULATED);
        assertThat(response.quantityAvailable()).isEqualByComparingTo("5");
        verify(eventPublisher).publishEvent(any(TradeListingCreatedEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("createListing refuses to list a holding under an active lockup (Track 5-2)")
    void createListing_refusesWhenSellerIsLockedUp() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(investorLimitGate.isLockedUp(ASSET_ID, SELLER)).thenReturn(true);
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        assertThatThrownBy(() -> service.createListing(SELLER, UUID.randomUUID(), req))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("lockup");
        verify(tradeListingRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("createListing surfaces the most recent settled trade price as a reference (finding #15)")
    void createListing_populatesLastTradePrice_whenASettledExecutionExistsForTheAsset() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        TradeExecution settled = new TradeExecution();
        settled.setUnitPrice(BigDecimal.valueOf(12.5));
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.of(settled));
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        var response = service.createListing(SELLER, UUID.randomUUID(), req);

        assertThat(response.lastTradePrice()).isEqualByComparingTo("12.5");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("createListing leaves lastTradePrice null when the asset has never settled a trade (finding #15)")
    void createListing_leavesLastTradePriceNull_whenNoSettledExecutionExists() {
        AssetHolder holder = sellerHolder(BigDecimal.TEN);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.empty());
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

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
        // No settings row -> defaultSettings() is used, which defaults immediateSettlementEnabled=true,
        // so buy() will run settleExecution() and needs the seller holder to be resolvable.
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.empty());
        AssetHolder seller = sellerHolder(BigDecimal.TEN);
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, null, WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "cc".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.paymentOption()).isEqualTo(PaymentOption.CBMT);
    }

    // ── buy — SIMULATED venue settlement ──────────────────────────────────────

    @Test
    void buy_simulatedVenue_immediateSettlement_settlesAndCreditsNewBuyerHolder() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.valueOf(2), Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(100));
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.valueOf(4), OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "dd".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(response.side()).isEqualTo("BUY");
        assertThat(seller.getNominalAmount()).isEqualByComparingTo("96"); // 100 - 4
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("6"); // 10 - 4
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.PARTIALLY_FILLED);
        verify(eventPublisher).publishEvent(any(HolderEnteredEvent.class));
        verify(eventPublisher).publishEvent(any(TradeExecutedEvent.class));
    }

    @Test
    void buy_simulatedVenue_immediateSettlement_creditsExistingBuyerHolder() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(100));
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(seller));

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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(false, PaymentOption.STABLECOIN)));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "ff".repeat(20));

        var response = service.buy(BUYER, UUID.randomUUID(), listingId, req);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    void buy_simulatedVenue_sellerNoLongerHoldingEnoughUnits_throws() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
        AssetHolder seller = sellerHolder(BigDecimal.valueOf(2)); // less than the 5 units being bought
        seller.setId(HOLDER_ID);
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(seller));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.valueOf(5), OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "11".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer holds enough units");
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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
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
    @org.junit.jupiter.api.DisplayName("buy fails clearly (not a bare 404) when the listingId belongs to a live external-venue offer (finding #10)")
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
    @org.junit.jupiter.api.DisplayName("buy still throws a plain not-found for a listingId that matches nothing anywhere (finding #10)")
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
    @org.junit.jupiter.api.DisplayName("listMarketplaceOffers surfaces the most recent settled trade price per asset (finding #15)")
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
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
                .thenReturn(Optional.of(settled));

        var offers = service.listMarketplaceOffers(BUYER, null, null, null, null, null, null, null);

        assertThat(offers).hasSize(1);
        assertThat(offers.getFirst().lastTradePrice()).isEqualByComparingTo("9.75");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("listMarketplaceOffers leaves lastTradePrice null when the asset has never settled a trade (finding #15)")
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
        when(tradeExecutionRepository.findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(ASSET_ID, SettlementStatus.SETTLED))
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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(false, PaymentOption.STABLECOIN)));

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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(false, PaymentOption.STABLECOIN)));

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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(false, PaymentOption.STABLECOIN)));

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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(false, PaymentOption.STABLECOIN)));

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
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(false, PaymentOption.STABLECOIN)));
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
        verifyNoInteractions(assetHolderRepository);
        verify(eventPublisher).publishEvent(any(TradePaymentDeclaredEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("settlePendingTrade requires a payment reference (finding #2, Phase 7)")
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
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());

        service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(execution.getSettledAt()).isNotNull();
        assertThat(execution.getBuyerHolderId()).isNotNull();
        assertThat(seller.getNominalAmount()).isEqualByComparingTo("47"); // 50 - 3
        verify(eventPublisher).publishEvent(any(TradePaymentConfirmedEvent.class));
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

    @Test
    @org.junit.jupiter.api.DisplayName("disputePayment fails the trade and releases the listing's reserved quantity (Track 4-2)")
    void disputePayment_failsTradeAndRestoresListingQuantity() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        execution.setListingId(UUID.randomUUID());
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        TradeListing listing = openListing(BigDecimal.valueOf(2), BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setStatus(ListingStatus.PARTIALLY_FILLED);
        when(tradeListingRepository.findByIdForUpdate(execution.getListingId())).thenReturn(Optional.of(listing));

        service.disputePayment(SELLER, UUID.randomUUID(), executionId, "never received");

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(execution.getFailureReason()).contains("never received");
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("5"); // 2 + 3
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.OPEN);
        verifyNoInteractions(assetHolderRepository);
        verify(eventPublisher).publishEvent(any(TradePaymentDisputedEvent.class));
    }

    // ── settlement compliance gate (KYC / screening / Sperrvermerk) — now exercised
    //    via confirmPaymentReceived, the only path that reaches settleExecution() ────

    @Test
    void confirmPaymentReceived_refusesWhenBuyerKycIsNotApproved() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        LegalEntity buyer = approvedEntity(BUYER);
        buyer.setKycStatus(KycStatus.IN_PROGRESS);
        when(legalEntityRepository.findById(BUYER)).thenReturn(Optional.of(buyer));

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KYC");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    void confirmPaymentReceived_refusesWhenSellerHasAnUnresolvedScreeningHit() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        when(screeningGate.hasUnresolvedHit(SELLER)).thenReturn(true);

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("screening");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    void confirmPaymentReceived_refusesWhenBuyerWalletHasAnActiveSperrvermerk() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        when(holderBlockGate.isBlocked(BUYER, execution.getWalletAddress())).thenReturn(true);

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sperrvermerk");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirmPaymentReceived refuses a buyer outside the asset's MiFID target market (Track 5-1)")
    void confirmPaymentReceived_refusesBuyerOutsideTargetMarket() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        Asset restricted = asset();
        restricted.setTargetMarketCategories(java.util.Set.of(de.makibytes.registerwerk.customer.api.ClientCategory.PROFESSIONAL));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(restricted));
        // Default legalEntityRepository stub returns an unclassified LegalEntity for BUYER.

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("target market");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("confirmPaymentReceived refuses a buyer whose resulting holding would exceed its maximum (Track 5-2)")
    void confirmPaymentReceived_refusesBuyerAboveMaxHolding() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = awaitingConfirmationExecution();
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(sellerHolder(BigDecimal.valueOf(50))));
        when(investorLimitGate.effectiveMaxHolding(any(), eq(BUYER))).thenReturn(BigDecimal.TEN);
        AssetHolder existingBuyerHolder = new AssetHolder();
        existingBuyerHolder.setInvestorId(BUYER);
        existingBuyerHolder.setNominalAmount(BigDecimal.valueOf(9)); // + 3 (executedQuantity) > 10
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(ASSET_ID, execution.getWalletAddress()))
                .thenReturn(Optional.of(existingBuyerHolder));

        assertThatThrownBy(() -> service.confirmPaymentReceived(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("maximum");
        verify(assetHolderRepository, never()).save(any());
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
    @org.junit.jupiter.api.DisplayName("cancelPendingTrade publishes an audit event (finding #6, Phase 7)")
    void cancelPendingTrade_publishesAuditEvent() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
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

    @Test
    void timeoutStuckPendingTrades_marksOverduePendingTradesFailed() {
        tradingProperties.setPendingTimeoutHours(72);
        TradeExecution stuck = new TradeExecution();
        stuck.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(stuck));

        service.timeoutStuckPendingTrades();

        assertThat(stuck.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(stuck.getFailureReason()).contains("Timed out");
        verify(tradeExecutionRepository).save(stuck);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("timeoutStuckPendingTrades publishes an audit event per timed-out trade (finding #6, Phase 7)")
    void timeoutStuckPendingTrades_publishesEventPerTimedOutTrade() {
        tradingProperties.setPendingTimeoutHours(72);
        TradeExecution stuck = new TradeExecution();
        stuck.setSettlementStatus(SettlementStatus.PENDING);
        when(tradeExecutionRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(stuck));

        service.timeoutStuckPendingTrades();

        ArgumentCaptor<TradePendingTimedOutEvent> captor = ArgumentCaptor.forClass(TradePendingTimedOutEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().actorId()).isNull();
        assertThat(captor.getValue().pendingTimeoutHours()).isEqualTo(72);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("timeoutStuckPendingTrades releases the listing's reserved quantity — previously the units were stranded forever (Track 4-2)")
    void timeoutStuckPendingTrades_restoresListingAvailability() {
        tradingProperties.setPendingTimeoutHours(72);
        UUID listingId = UUID.randomUUID();
        TradeExecution stuck = new TradeExecution();
        stuck.setSettlementStatus(SettlementStatus.PENDING);
        stuck.setListingId(listingId);
        stuck.setExecutedQuantity(BigDecimal.valueOf(4));
        when(tradeExecutionRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(stuck));
        TradeListing listing = openListing(BigDecimal.valueOf(1), BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setStatus(ListingStatus.PARTIALLY_FILLED);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        service.timeoutStuckPendingTrades();

        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("5"); // 1 + 4
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.OPEN);
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

    @Test
    @org.junit.jupiter.api.DisplayName("timeoutStuckPendingTrades also fails trades stuck awaiting seller confirmation (Track 4-2)")
    void timeoutStuckPendingTrades_alsoTimesOutStuckAwaitingConfirmationTrades() {
        tradingProperties.setPendingTimeoutHours(72);
        TradeExecution stuck = new TradeExecution();
        stuck.setSettlementStatus(SettlementStatus.AWAITING_SELLER_CONFIRMATION);
        when(tradeExecutionRepository.findBySettlementStatusAndPaymentDeclaredAtBefore(
                eq(SettlementStatus.AWAITING_SELLER_CONFIRMATION), any()))
                .thenReturn(List.of(stuck));

        service.timeoutStuckPendingTrades();

        assertThat(stuck.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(stuck.getFailureReason()).contains("seller confirmation");
        verify(tradeExecutionRepository).save(stuck);
        verify(eventPublisher).publishEvent(any(TradePendingTimedOutEvent.class));
    }
}
