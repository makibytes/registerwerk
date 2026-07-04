package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.HolderEnteredEvent;
import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCreatedEvent;
import de.makibytes.registerwerk.trading.web.dto.BuyTradingOfferRequest;
import de.makibytes.registerwerk.trading.web.dto.CreateTradeListingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
                List.of(venueAdapter), tradingAssetTypeResolver, eventPublisher);
        lenient().when(tradeListingRepository.save(any(TradeListing.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tradeExecutionRepository.save(any(TradeExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(assetHolderRepository.save(any(AssetHolder.class))).thenAnswer(inv -> {
            AssetHolder h = inv.getArgument(0);
            if (h.getId() == null) h.setId(UUID.randomUUID());
            return h;
        });
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
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatus(any(), any()))
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
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatus(any(), any()))
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
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatus(any(), any()))
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
        when(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatus(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        CreateTradeListingRequest req = new CreateTradeListingRequest(
                HOLDER_ID, BigDecimal.valueOf(5), BigDecimal.TEN, false, List.of(PaymentOption.STABLECOIN));

        var response = service.createListing(SELLER, UUID.randomUUID(), req);

        assertThat(response.venueCode()).isEqualTo(TradingVenueCode.SIMULATED);
        assertThat(response.quantityAvailable()).isEqualByComparingTo("5");
        verify(eventPublisher).publishEvent(any(TradeListingCreatedEvent.class));
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
        when(assetHolderRepository.findByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());
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
        when(assetHolderRepository.findByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());
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
        when(assetHolderRepository.findByAssetIdAndWalletAddress(ASSET_ID, walletAddress))
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
    void buy_nonSimulatedVenue_rejectedByAdapter_throws() {
        UUID listingId = UUID.randomUUID();
        TradeListing listing = openListing(BigDecimal.TEN, BigDecimal.ONE, Set.of(PaymentOption.STABLECOIN));
        listing.setVenueCode(TradingVenueCode.ASSETERA);
        when(tradeListingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(settingsRepository.findById(BUYER)).thenReturn(Optional.of(settings(true, PaymentOption.STABLECOIN)));
        when(venueAdapter.venueCode()).thenReturn(TradingVenueCode.ASSETERA);
        when(venueAdapter.execute(any())).thenReturn(TradingVenueExecutionResult.rejected("insufficient liquidity"));
        BuyTradingOfferRequest req = new BuyTradingOfferRequest(
                BigDecimal.ONE, OrderType.MARKET, null, PaymentOption.STABLECOIN,
                WalletPreferenceMode.CUSTOM_ADDRESS, null, "0x" + "22".repeat(20));

        assertThatThrownBy(() -> service.buy(BUYER, UUID.randomUUID(), listingId, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient liquidity");
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

        assertThatThrownBy(() -> service.settlePendingTrade(SELLER, UUID.randomUUID(), executionId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void settlePendingTrade_isIdempotentWhenAlreadySettled() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(BUYER);
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        when(tradeExecutionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));

        service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId);

        verify(tradeExecutionRepository, never()).save(any());
        verifyNoInteractions(assetHolderRepository);
    }

    @Test
    void settlePendingTrade_settlesAPendingExecution() {
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

        AssetHolder seller = sellerHolder(BigDecimal.valueOf(50));
        when(assetHolderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(seller));
        when(assetHolderRepository.findByAssetIdAndWalletAddress(eq(ASSET_ID), any())).thenReturn(Optional.empty());

        service.settlePendingTrade(BUYER, UUID.randomUUID(), executionId);

        assertThat(execution.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(execution.getSettledAt()).isNotNull();
        assertThat(execution.getBuyerHolderId()).isNotNull();
        assertThat(seller.getNominalAmount()).isEqualByComparingTo("47"); // 50 - 3
    }
}
