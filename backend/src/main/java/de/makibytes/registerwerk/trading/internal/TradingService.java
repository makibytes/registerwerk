package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.events.TradeListingCreatedEvent;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TraderSettingsUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetHolder;
import de.makibytes.registerwerk.asset.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.trading.web.dto.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class TradingService {

    private final TradingProperties tradingProperties;
    private final CompanyTraderSettingsRepository settingsRepository;
    private final CompanyTraderWalletDefaultRepository walletDefaultRepository;
    private final TradeListingRepository tradeListingRepository;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AddressEndpointRepository endpointRepository;
    private final SimulatedTradingVenueAdapter simulatedTradingVenueAdapter;
    private final PlaceholderTradingVenueAdapter placeholderTradingVenueAdapter;
    private final TradingAssetTypeResolver tradingAssetTypeResolver;
    private final ApplicationEventPublisher eventPublisher;

    public TradingService(
            TradingProperties tradingProperties,
            CompanyTraderSettingsRepository settingsRepository,
            CompanyTraderWalletDefaultRepository walletDefaultRepository,
            TradeListingRepository tradeListingRepository,
            TradeExecutionRepository tradeExecutionRepository,
            AssetHolderRepository assetHolderRepository,
            AssetRepository assetRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            AddressEndpointRepository endpointRepository,
            SimulatedTradingVenueAdapter simulatedTradingVenueAdapter,
            PlaceholderTradingVenueAdapter placeholderTradingVenueAdapter,
            TradingAssetTypeResolver tradingAssetTypeResolver,
            ApplicationEventPublisher eventPublisher) {
        this.tradingProperties = tradingProperties;
        this.settingsRepository = settingsRepository;
        this.walletDefaultRepository = walletDefaultRepository;
        this.tradeListingRepository = tradeListingRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.assetRepository = assetRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.endpointRepository = endpointRepository;
        this.simulatedTradingVenueAdapter = simulatedTradingVenueAdapter;
        this.placeholderTradingVenueAdapter = placeholderTradingVenueAdapter;
        this.tradingAssetTypeResolver = tradingAssetTypeResolver;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<TradingVenueResponse> listVenues() {
        ensureTradingEnabled();
        List<TradingVenueMetadata> metadata = new ArrayList<>();
        metadata.add(simulatedTradingVenueAdapter.metadata());
        metadata.addAll(placeholderTradingVenueAdapter.metadataEntries());
        return metadata.stream()
                .filter(TradingVenueMetadata::enabled)
                .map(meta -> new TradingVenueResponse(
                        meta.code(),
                        meta.displayName(),
                        meta.connected(),
                        meta.executable(),
                        meta.supportedOrderTypes(),
                        meta.summary()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyTraderSettingsResponse getSettings(UUID entityId) {
        ensureTradingEnabled();
        CompanyTraderSettings settings = settingsRepository.findById(entityId)
                .orElseGet(() -> defaultSettings(entityId));
        List<CompanyTraderWalletDefaultResponse> walletDefaults = walletDefaultRepository.findByLegalEntityId(entityId).stream()
                .map(this::toWalletDefaultResponse)
                .sorted(Comparator.comparing(response -> response.assetType() == null ? "" : response.assetType().name()))
                .toList();
        return new CompanyTraderSettingsResponse(
                settings.getDefaultPaymentOption(),
                settings.isImmediateSettlementEnabled(),
                walletDefaults
        );
    }

    public CompanyTraderSettingsResponse saveSettings(UUID entityId, UUID actorId, UpdateCompanyTraderSettingsRequest request) {
        ensureTradingEnabled();
        CompanyTraderSettings settings = settingsRepository.findById(entityId).orElseGet(() -> defaultSettings(entityId));
        settings.setDefaultPaymentOption(request.defaultPaymentOption());
        settings.setImmediateSettlementEnabled(request.immediateSettlementEnabled());
        settings.setUpdatedBy(actorId);
        settingsRepository.save(settings);

        List<CompanyTraderWalletDefault> existing = walletDefaultRepository.findByLegalEntityId(entityId);
        walletDefaultRepository.deleteAll(existing);
        if (request.walletDefaults() != null) {
            for (CompanyTraderWalletDefaultRequest walletDefault : request.walletDefaults()) {
                CompanyTraderWalletDefault entity = new CompanyTraderWalletDefault();
                entity.setLegalEntityId(entityId);
                entity.setAssetType(walletDefault.assetType());
                entity.setTargetType(walletDefault.targetType());
                if (walletDefault.targetType() == WalletTargetType.ENDPOINT) {
                    UUID endpointId = require(walletDefault.endpointId(), "Endpoint is required for endpoint wallet defaults");
                    validateEndpointOwnership(entityId, endpointId);
                    entity.setEndpointId(endpointId);
                } else {
                    entity.setWalletAddress(requireNotBlank(walletDefault.walletAddress(), "Wallet address is required"));
                }
                walletDefaultRepository.save(entity);
            }
        }

        eventPublisher.publishEvent(new TraderSettingsUpdatedEvent(entityId, actorId, "TRADER"));
        return getSettings(entityId);
    }

    @Transactional(readOnly = true)
    public List<SellableHoldingResponse> listSellableHoldings(UUID entityId) {
        ensureTradingEnabled();
        return assetHolderRepository.findByInvestorId(entityId).stream()
                .map(holder -> toSellableHolding(entityId, holder))
                .filter(response -> response.availableQuantity().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(SellableHoldingResponse::assetName))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TradeListingResponse> listCompanyListings(UUID entityId) {
        ensureTradingEnabled();
        return tradeListingRepository.findBySellerEntityIdOrderByCreatedAtDesc(entityId).stream()
                .map(this::toTradeListingResponse)
                .toList();
    }

    public TradeListingResponse createListing(UUID entityId, UUID actorId, CreateTradeListingRequest request) {
        ensureTradingEnabled();
        AssetHolder holder = assetHolderRepository.findById(request.holderId())
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", request.holderId()));
        if (!entityId.equals(holder.getInvestorId())) {
            throw new AccessDeniedException("This holding does not belong to your company");
        }
        Asset asset = assetRepository.findById(holder.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", holder.getAssetId()));
        BigDecimal available = computeAvailableQuantity(holder.getId(), holder.getNominalAmount());
        BigDecimal quantity = positive(request.quantity(), "Quantity must be greater than zero");
        if (quantity.compareTo(available) > 0) {
            throw new IllegalArgumentException("Only " + available + " units are available for listing");
        }

        Set<PaymentOption> paymentOptions = resolveListingPaymentOptions(entityId, request);
        TradeListing listing = new TradeListing();
        listing.setVenueCode(TradingVenueCode.SIMULATED);
        listing.setSellerEntityId(entityId);
        listing.setSellerHolderId(holder.getId());
        listing.setAssetId(asset.getId());
        listing.setAssetNumber(asset.getAssetNumber());
        listing.setAssetName(asset.getName());
        listing.setIsin(asset.getIsin());
        listing.setAssetType(tradingAssetTypeResolver.resolve(asset));
        listing.setTokenStandard(asset.getTokenStandard());
        listing.setChain(resolvePrimaryChain(asset.getId()));
        listing.setQuantityTotal(quantity);
        listing.setQuantityAvailable(quantity);
        listing.setPricePerUnit(positive(request.pricePerUnit(), "Price must be greater than zero"));
        listing.setAllowedPaymentOptions(paymentOptions);
        TradeListing saved = tradeListingRepository.save(listing);

        eventPublisher.publishEvent(new TradeListingCreatedEvent(saved.getId(), actorId, "TRADER", saved.getAssetId()));
        return toTradeListingResponse(saved);
    }

    public void cancelListing(UUID entityId, UUID actorId, UUID listingId) {
        ensureTradingEnabled();
        TradeListing listing = tradeListingRepository.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("TradeListing", listingId));
        if (!entityId.equals(listing.getSellerEntityId())) {
            throw new AccessDeniedException("Cannot cancel a listing owned by another company");
        }
        if (listing.getStatus() == ListingStatus.FILLED || listing.getStatus() == ListingStatus.CANCELLED) {
            return;
        }
        listing.setStatus(ListingStatus.CANCELLED);
        tradeListingRepository.save(listing);
        eventPublisher.publishEvent(new TradeListingCancelledEvent(listing.getId(), actorId, "TRADER"));
    }

    @Transactional(readOnly = true)
    public List<TradingVenueOfferResponse> listMarketplaceOffers(
            UUID entityId,
            String search,
            TradingAssetType assetType,
            de.makibytes.registerwerk.asset.api.TokenStandard tokenStandard,
            TradingVenueCode venueCode,
            PaymentOption paymentOption,
            BigDecimal minPrice,
            BigDecimal maxPrice) {
        ensureTradingEnabled();
        TradingOfferFilter filter = new TradingOfferFilter(search, assetType, tokenStandard, venueCode, paymentOption, minPrice, maxPrice);
        List<TradingVenueOffer> offers = new ArrayList<>(simulatedTradingVenueAdapter.searchOffers(filter));
        // External adapters are scaffolded but non-executable for now, so they return no offers.
        return offers.stream()
                .filter(offer -> !entityId.equals(findListing(offer.listingId()).getSellerEntityId()))
                .map(this::toOfferResponse)
                .toList();
    }

    public TradeExecutionResponse buy(UUID entityId, UUID actorId, UUID listingId, BuyTradingOfferRequest request) {
        ensureTradingEnabled();
        TradeListing listing = findListing(listingId);
        if (listing.getVenueCode() != TradingVenueCode.SIMULATED) {
            throw new IllegalArgumentException("Only simulated-venue execution is available in this build");
        }
        if (entityId.equals(listing.getSellerEntityId())) {
            throw new IllegalArgumentException("A company cannot buy its own listing");
        }
        if (listing.getStatus() == ListingStatus.CANCELLED || listing.getStatus() == ListingStatus.FILLED) {
            throw new IllegalArgumentException("This offer is no longer available");
        }

        BigDecimal quantity = positive(request.quantity(), "Quantity must be greater than zero");
        if (quantity.compareTo(listing.getQuantityAvailable()) > 0) {
            throw new IllegalArgumentException("Only " + listing.getQuantityAvailable() + " units are available");
        }
        OrderType orderType = require(request.orderType(), "Order type is required");
        if (orderType == OrderType.LIMIT) {
            BigDecimal limitPrice = positive(request.limitPrice(), "Limit price is required for limit orders");
            if (listing.getPricePerUnit().compareTo(limitPrice) > 0) {
                throw new IllegalArgumentException("Listing price exceeds your limit price");
            }
        }
        if (orderType != OrderType.MARKET && orderType != OrderType.LIMIT) {
            throw new IllegalArgumentException("The simulated venue supports only MARKET and LIMIT orders");
        }

        PaymentOption paymentOption = request.paymentOption();
        if (paymentOption == null) {
            paymentOption = listing.getAllowedPaymentOptions().stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Listing has no payment options"));
        }
        if (!listing.getAllowedPaymentOptions().contains(paymentOption)) {
            throw new IllegalArgumentException("Selected payment option is not accepted by the seller");
        }

        CompanyTraderSettings settings = settingsRepository.findById(entityId).orElseGet(() -> defaultSettings(entityId));
        ResolvedWallet resolvedWallet = resolveWallet(entityId, listing.getAssetType(), request.walletPreferenceMode(), request.endpointId(), request.walletAddress());

        TradeExecution execution = new TradeExecution();
        execution.setListingId(listing.getId());
        execution.setVenueCode(listing.getVenueCode());
        execution.setBuyerEntityId(entityId);
        execution.setSellerEntityId(listing.getSellerEntityId());
        execution.setSellerHolderId(listing.getSellerHolderId());
        execution.setAssetId(listing.getAssetId());
        execution.setAssetNumber(listing.getAssetNumber());
        execution.setAssetName(listing.getAssetName());
        execution.setIsin(listing.getIsin());
        execution.setAssetType(listing.getAssetType());
        execution.setTokenStandard(listing.getTokenStandard());
        execution.setChain(listing.getChain());
        execution.setOrderType(orderType);
        execution.setRequestedQuantity(quantity);
        execution.setExecutedQuantity(quantity);
        execution.setUnitPrice(listing.getPricePerUnit());
        execution.setTotalPrice(listing.getPricePerUnit().multiply(quantity));
        execution.setPaymentOption(paymentOption);
        execution.setWalletPreferenceMode(resolvedWallet.preferenceMode());
        execution.setWalletEndpointId(resolvedWallet.endpointId());
        execution.setWalletAddress(resolvedWallet.address());

        if (settings.isImmediateSettlementEnabled()) {
            AssetHolder buyerHolder = settleExecution(execution);
            execution.setBuyerHolderId(buyerHolder.getId());
            execution.setSettlementStatus(SettlementStatus.SETTLED);
            execution.setSettledAt(Instant.now());
        } else {
            execution.setSettlementStatus(SettlementStatus.PENDING);
        }

        TradeExecution saved = tradeExecutionRepository.save(execution);
        updateListingAfterExecution(listing, quantity);

        eventPublisher.publishEvent(new TradeExecutedEvent(saved.getId(), actorId, "TRADER", listing.getId()));
        return toTradeExecutionResponse(entityId, saved);
    }

    public TradeExecutionResponse settlePendingTrade(UUID entityId, UUID actorId, UUID executionId) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findById(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (!entityId.equals(execution.getBuyerEntityId())) {
            throw new AccessDeniedException("Only the buying company can settle this trade");
        }
        if (execution.getSettlementStatus() == SettlementStatus.SETTLED) {
            return toTradeExecutionResponse(entityId, execution);
        }
        AssetHolder buyerHolder = settleExecution(execution);
        execution.setBuyerHolderId(buyerHolder.getId());
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        execution.setSettledAt(Instant.now());
        TradeExecution saved = tradeExecutionRepository.save(execution);
        // Trade settlement is part of the TRADE_EXECUTED flow; no separate event needed
        return toTradeExecutionResponse(entityId, saved);
    }

    @Transactional(readOnly = true)
    public List<TradeExecutionResponse> listHistory(UUID entityId) {
        ensureTradingEnabled();
        return tradeExecutionRepository.findByBuyerEntityIdOrSellerEntityIdOrderByCreatedAtDesc(entityId, entityId).stream()
                .map(execution -> toTradeExecutionResponse(entityId, execution))
                .toList();
    }

    private Set<PaymentOption> resolveListingPaymentOptions(UUID entityId, CreateTradeListingRequest request) {
        if (request.useCompanyDefaultPaymentOption()) {
            return EnumSet.of(settingsRepository.findById(entityId).orElseGet(() -> defaultSettings(entityId)).getDefaultPaymentOption());
        }
        if (request.allowedPaymentOptions() == null || request.allowedPaymentOptions().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one payment option or use the company default");
        }
        return EnumSet.copyOf(request.allowedPaymentOptions());
    }

    private SellableHoldingResponse toSellableHolding(UUID entityId, AssetHolder holder) {
        Asset asset = assetRepository.findById(holder.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", holder.getAssetId()));
        BigDecimal available = computeAvailableQuantity(holder.getId(), holder.getNominalAmount());
        return new SellableHoldingResponse(
                holder.getId(),
                holder.getAssetId(),
                asset.getAssetNumber(),
                asset.getName(),
                asset.getIsin(),
                tradingAssetTypeResolver.resolve(asset),
                asset.getTokenStandard(),
                resolvePrimaryChain(asset.getId()),
                holder.getNominalAmount(),
                available,
                holder.getWalletAddress()
        );
    }

    private BigDecimal computeAvailableQuantity(UUID holderId, BigDecimal nominalAmount) {
        BigDecimal openListed = tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(
                holderId, List.of(ListingStatus.OPEN, ListingStatus.PARTIALLY_FILLED));
        BigDecimal pendingSettlement = tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatus(
                holderId, SettlementStatus.PENDING);
        return nominalAmount.subtract(openListed).subtract(pendingSettlement).max(BigDecimal.ZERO);
    }

    private Chain resolvePrimaryChain(UUID assetId) {
        return assetDeploymentRepository.findByAssetId(assetId).stream()
                .map(AssetDeployment::getChain)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private TradeListing findListing(UUID listingId) {
        return tradeListingRepository.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("TradeListing", listingId));
    }

    private AssetHolder settleExecution(TradeExecution execution) {
        AssetHolder sellerHolder = assetHolderRepository.findById(execution.getSellerHolderId())
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", execution.getSellerHolderId()));
        if (sellerHolder.getNominalAmount().compareTo(execution.getExecutedQuantity()) < 0) {
            throw new IllegalArgumentException("Seller no longer holds enough units to settle this trade");
        }
        sellerHolder.setNominalAmount(sellerHolder.getNominalAmount().subtract(execution.getExecutedQuantity()));
        assetHolderRepository.save(sellerHolder);

        AssetHolder buyerHolder = assetHolderRepository.findByAssetIdAndWalletAddress(execution.getAssetId(), execution.getWalletAddress())
                .filter(holder -> holder.getInvestorId().equals(execution.getBuyerEntityId()))
                .orElseGet(() -> {
                    AssetHolder holder = new AssetHolder();
                    holder.setAssetId(execution.getAssetId());
                    holder.setInvestorId(execution.getBuyerEntityId());
                    holder.setWalletAddress(execution.getWalletAddress());
                    holder.setNominalAmount(BigDecimal.ZERO);
                    holder.setAcquisitionDate(LocalDate.now());
                    holder.setWhitelisted(false);
                    return holder;
                });
        buyerHolder.setNominalAmount(buyerHolder.getNominalAmount().add(execution.getExecutedQuantity()));
        buyerHolder.setAcquisitionDate(LocalDate.now());
        return assetHolderRepository.save(buyerHolder);
    }

    private void updateListingAfterExecution(TradeListing listing, BigDecimal executedQuantity) {
        BigDecimal remaining = listing.getQuantityAvailable().subtract(executedQuantity);
        listing.setQuantityAvailable(remaining);
        if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            listing.setStatus(ListingStatus.FILLED);
        } else {
            listing.setStatus(ListingStatus.PARTIALLY_FILLED);
        }
        tradeListingRepository.save(listing);
    }

    private ResolvedWallet resolveWallet(
            UUID entityId,
            TradingAssetType assetType,
            WalletPreferenceMode requestedMode,
            UUID endpointId,
            String walletAddress) {
        WalletPreferenceMode mode = requestedMode != null ? requestedMode : WalletPreferenceMode.GLOBAL_DEFAULT;
        return switch (mode) {
            case GLOBAL_DEFAULT -> fromDefault(entityId, null, WalletPreferenceMode.GLOBAL_DEFAULT);
            case ASSET_TYPE_DEFAULT -> fromDefault(entityId, assetType, WalletPreferenceMode.ASSET_TYPE_DEFAULT);
            case ENDPOINT -> {
                UUID resolvedEndpointId = require(endpointId, "Endpoint is required");
                AddressEndpoint endpoint = validateEndpointOwnership(entityId, resolvedEndpointId);
                yield new ResolvedWallet(WalletPreferenceMode.ENDPOINT, resolvedEndpointId, endpoint.getAddress());
            }
            case CUSTOM_ADDRESS -> new ResolvedWallet(
                    WalletPreferenceMode.CUSTOM_ADDRESS,
                    null,
                    requireNotBlank(walletAddress, "Wallet address is required"));
        };
    }

    private ResolvedWallet fromDefault(UUID entityId, TradingAssetType assetType, WalletPreferenceMode mode) {
        CompanyTraderWalletDefault walletDefault = walletDefaultRepository.findByLegalEntityIdAndAssetType(entityId, assetType)
                .or(() -> walletDefaultRepository.findByLegalEntityIdAndAssetType(entityId, null))
                .orElseThrow(() -> new IllegalArgumentException("No company wallet default is configured"));
        if (walletDefault.getTargetType() == WalletTargetType.ENDPOINT) {
            AddressEndpoint endpoint = validateEndpointOwnership(entityId, walletDefault.getEndpointId());
            return new ResolvedWallet(mode, endpoint.getId(), endpoint.getAddress());
        }
        return new ResolvedWallet(mode, null, walletDefault.getWalletAddress());
    }

    private AddressEndpoint validateEndpointOwnership(UUID entityId, UUID endpointId) {
        AddressEndpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new EntityNotFoundException("AddressEndpoint", endpointId));
        boolean owned = endpoint.getOwnerType() == AddressEndpoint.OwnerType.ENTITY
                && Objects.equals(endpoint.getOwnerId(), entityId);
        if (!owned) {
            throw new AccessDeniedException("The selected endpoint does not belong to your company");
        }
        return endpoint;
    }

    private CompanyTraderSettings defaultSettings(UUID entityId) {
        CompanyTraderSettings settings = new CompanyTraderSettings();
        settings.setLegalEntityId(entityId);
        return settings;
    }

    private CompanyTraderWalletDefaultResponse toWalletDefaultResponse(CompanyTraderWalletDefault walletDefault) {
        return new CompanyTraderWalletDefaultResponse(
                walletDefault.getId(),
                walletDefault.getAssetType(),
                walletDefault.getTargetType(),
                walletDefault.getEndpointId(),
                walletDefault.getWalletAddress()
        );
    }

    private TradeListingResponse toTradeListingResponse(TradeListing listing) {
        return new TradeListingResponse(
                listing.getId(),
                listing.getVenueCode(),
                listing.getAssetId(),
                listing.getAssetNumber(),
                listing.getAssetName(),
                listing.getIsin(),
                listing.getAssetType(),
                listing.getTokenStandard(),
                listing.getChain(),
                listing.getStatus(),
                listing.getQuantityTotal(),
                listing.getQuantityAvailable(),
                listing.getPricePerUnit(),
                new ArrayList<>(listing.getAllowedPaymentOptions()),
                listing.getCreatedAt()
        );
    }

    private TradingVenueOfferResponse toOfferResponse(TradingVenueOffer offer) {
        return new TradingVenueOfferResponse(
                offer.listingId(),
                offer.venueCode(),
                offer.venueDisplayName(),
                offer.assetId(),
                offer.assetNumber(),
                offer.assetName(),
                offer.isin(),
                offer.assetType(),
                offer.tokenStandard(),
                offer.chain(),
                offer.quantityAvailable(),
                offer.pricePerUnit(),
                new ArrayList<>(offer.allowedPaymentOptions()),
                offer.supportedOrderTypes(),
                offer.createdAt()
        );
    }

    private TradeExecutionResponse toTradeExecutionResponse(UUID viewerEntityId, TradeExecution execution) {
        String side = viewerEntityId.equals(execution.getBuyerEntityId()) ? "BUY" : "SELL";
        return new TradeExecutionResponse(
                execution.getId(),
                side,
                execution.getListingId(),
                execution.getVenueCode(),
                execution.getAssetId(),
                execution.getAssetNumber(),
                execution.getAssetName(),
                execution.getIsin(),
                execution.getAssetType(),
                execution.getTokenStandard(),
                execution.getChain(),
                execution.getOrderType(),
                execution.getExecutedQuantity(),
                execution.getUnitPrice(),
                execution.getTotalPrice(),
                execution.getPaymentOption(),
                execution.getSettlementStatus(),
                execution.getWalletPreferenceMode(),
                execution.getWalletEndpointId(),
                execution.getWalletAddress(),
                execution.getCreatedAt(),
                execution.getSettledAt()
        );
    }

    private void ensureTradingEnabled() {
        if (!tradingProperties.isEnabled()) {
            throw new UnsupportedOperationException("Trading is disabled");
        }
    }

    private BigDecimal positive(BigDecimal value, String message) {
        BigDecimal resolved = require(value, message);
        if (resolved.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(message);
        }
        return resolved;
    }

    private String requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private record ResolvedWallet(WalletPreferenceMode preferenceMode, UUID endpointId, String address) {
    }
}
