package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.shared.RegisterClock;
import de.makibytes.registerwerk.trading.events.TradeListingCreatedEvent;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentConfirmedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDeclaredEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import de.makibytes.registerwerk.trading.events.TradeRefundedEvent;
import de.makibytes.registerwerk.trading.events.TradeUnresolvedResolvedEvent;
import de.makibytes.registerwerk.trading.events.TraderSettingsUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.Money;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.InvestorLimitGate;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.trading.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final TradingProperties tradingProperties;
    private final RegisterClock registerClock;
    private final CompanyTraderSettingsRepository settingsRepository;
    private final CompanyTraderWalletDefaultRepository walletDefaultRepository;
    private final TradeListingRepository tradeListingRepository;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AddressEndpointRepository endpointRepository;
    private final List<TradingVenueAdapter> venueAdapters;
    private final TradingAssetTypeResolver tradingAssetTypeResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final LegalEntityRepository legalEntityRepository;
    private final SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    private final InvestorLimitGate investorLimitGate;
    private final PartyEligibilityGate partyEligibilityGate;
    private final FinalityGate finalityGate;
    private final HolderEncumbranceRegistry encumbrance;
    private final TradeTransitions transitions;
    private final OrgMemberWalletRepository orgMemberWalletRepository;
    private final TradeCurrencyPolicy currencyPolicy;
    private final RelatedPartyCheck relatedPartyCheck;
    private final RelatedPartyAlerts relatedPartyAlerts;

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
            List<TradingVenueAdapter> venueAdapters,
            TradingAssetTypeResolver tradingAssetTypeResolver,
            ApplicationEventPublisher eventPublisher,
            LegalEntityRepository legalEntityRepository,
            SuitabilityAssessmentRepository suitabilityAssessmentRepository,
            InvestorLimitGate investorLimitGate,
            PartyEligibilityGate partyEligibilityGate,
            FinalityGate finalityGate,
            HolderEncumbranceRegistry encumbrance,
            TradeTransitions transitions,
            OrgMemberWalletRepository orgMemberWalletRepository,
            TradeCurrencyPolicy currencyPolicy,
            RelatedPartyCheck relatedPartyCheck,
            RelatedPartyAlerts relatedPartyAlerts,
            RegisterClock registerClock) {
        this.tradingProperties = tradingProperties;
        this.registerClock = registerClock;
        this.settingsRepository = settingsRepository;
        this.walletDefaultRepository = walletDefaultRepository;
        this.tradeListingRepository = tradeListingRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.assetRepository = assetRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.endpointRepository = endpointRepository;
        this.venueAdapters = venueAdapters;
        this.tradingAssetTypeResolver = tradingAssetTypeResolver;
        this.eventPublisher = eventPublisher;
        this.legalEntityRepository = legalEntityRepository;
        this.suitabilityAssessmentRepository = suitabilityAssessmentRepository;
        this.investorLimitGate = investorLimitGate;
        this.partyEligibilityGate = partyEligibilityGate;
        this.finalityGate = finalityGate;
        this.encumbrance = encumbrance;
        this.transitions = transitions;
        this.orgMemberWalletRepository = orgMemberWalletRepository;
        this.currencyPolicy = currencyPolicy;
        this.relatedPartyCheck = relatedPartyCheck;
        this.relatedPartyAlerts = relatedPartyAlerts;
    }

    @Transactional(readOnly = true)
    public List<TradingVenueResponse> listVenues() {
        ensureTradingEnabled();
        return venueAdapters.stream()
                .map(TradingVenueAdapter::metadata)
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
                // 5A-01: the buyer-owned instant-settlement flag is retired; always reported as off.
                false,
                walletDefaults
        );
    }

    public CompanyTraderSettingsResponse saveSettings(UUID entityId, UUID actorId, UpdateCompanyTraderSettingsRequest request) {
        ensureTradingEnabled();
        CompanyTraderSettings settings = settingsRepository.findById(entityId).orElseGet(() -> defaultSettings(entityId));
        settings.setDefaultPaymentOption(request.defaultPaymentOption());
        if (request.immediateSettlementEnabled()) {
            log.warn("Deprecated: 'immediateSettlementEnabled' in trader settings is ignored (entity={}); the seller "
                    + "chooses per listing (allowInstantSettlement, demo only).", entityId);
        }
        settings.setImmediateSettlementEnabled(false);
        settings.setUpdatedBy(actorId);
        settingsRepository.save(settings);

        List<CompanyTraderWalletDefault> existing = walletDefaultRepository.findByLegalEntityId(entityId);
        walletDefaultRepository.deleteAll(existing);
        if (request.walletDefaults() != null) {
            java.util.Set<TradingAssetType> assetTypes = new java.util.HashSet<>();
            boolean globalDefaultSeen = false;
            for (CompanyTraderWalletDefaultRequest walletDefault : request.walletDefaults()) {
                if (walletDefault.assetType() == null) {
                    if (globalDefaultSeen) {
                        throw new IllegalArgumentException("Only one global wallet default is allowed");
                    }
                    globalDefaultSeen = true;
                } else if (!assetTypes.add(walletDefault.assetType())) {
                    throw new IllegalArgumentException(
                            "Duplicate wallet default for asset type " + walletDefault.assetType());
                }
                CompanyTraderWalletDefault entity = new CompanyTraderWalletDefault();
                entity.setLegalEntityId(entityId);
                entity.setAssetType(walletDefault.assetType());
                entity.setTargetType(walletDefault.targetType());
                if (walletDefault.targetType() == WalletTargetType.ENDPOINT) {
                    UUID endpointId = require(walletDefault.endpointId(), "Endpoint is required for endpoint wallet defaults");
                    validateEndpointOwnership(entityId, endpointId);
                    entity.setEndpointId(endpointId);
                } else {
                    String address = requireNotBlank(walletDefault.walletAddress(), "Wallet address is required");
                    // 5C-03: no free-text address can become a register wallet.
                    requireEntityBoundAddress(entityId, address);
                    entity.setWalletAddress(address);
                }
                walletDefaultRepository.save(entity);
            }
        }

        eventPublisher.publishEvent(new TraderSettingsUpdatedEvent(
                entityId, actorId, "TRADER", settings.getDefaultPaymentOption(), false));
        return getSettings(entityId);
    }

    @Transactional(readOnly = true)
    public List<SellableHoldingResponse> listSellableHoldings(UUID entityId) {
        ensureTradingEnabled();
        // A removed holding is no longer part of the register and cannot be listed for sale.
        return assetHolderRepository.findActiveByInvestorId(entityId).stream()
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
        requireVenueClassified();
        // Active-only and row-locked (5A-04): a removed register entry cannot be listed, and two
        // concurrent listings of one holding cannot both pass the availability check.
        AssetHolder holder = assetHolderRepository.findActiveByIdForUpdate(request.holderId())
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", request.holderId()));
        if (!entityId.equals(holder.getInvestorId())) {
            throw new AccessDeniedException("This holding does not belong to your company");
        }
        Asset asset = assetRepository.findById(holder.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", holder.getAssetId()));
        de.makibytes.registerwerk.asset.api.RegisterFreezeGuard.requireOpen(asset, "Listing creation");
        requireIssued(asset, "Listing creation");
        requireOffchainSettlementAllowed(asset.getId());
        if (investorLimitGate.isLockedUp(asset.getId(), entityId)) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + entityId + "'s holding in this asset is under a lockup — cannot list for sale.");
        }
        // H11: same (entity, asset) lock as the repo desk's pledge check; availability is read after it
        tradeExecutionRepository.lockHolding(holder.getInvestorId(), asset.getId());
        BigDecimal available = computeAvailableQuantity(holder, asset.getId());
        BigDecimal quantity = positive(request.quantity(), "Quantity must be greater than zero");
        if (quantity.compareTo(available) > 0) {
            throw new IllegalArgumentException("Only " + available + " units are available for listing");
        }
        requireWholeDenomination(asset, quantity);
        if (request.allowInstantSettlement() && !tradingProperties.isDemoInstantSettlement()) {
            throw new IllegalArgumentException("Instant settlement is only available in demonstration mode and is not "
                    + "enabled here - the trade settles when you confirm receipt of the buyer's payment.");
        }

        Set<PaymentOption> paymentOptions = resolveListingPaymentOptions(entityId, request);
        TradeCurrencyPolicy.Resolved settlement = currencyPolicy.resolve(
                asset, request.currency(), request.paymentRailCode(), paymentOptions);
        UUID targetEntityId = resolveTarget(entityId, request.targetEntityId());
        BigDecimal pricePerUnit = positive(request.pricePerUnit(), "Price must be greater than zero");
        requireWithinPriceCollar(asset.getId(), pricePerUnit, settlement.currency());
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
        listing.setPricePerUnit(pricePerUnit);
        listing.setCurrency(settlement.currency());
        listing.setPaymentRailCode(settlement.paymentRailCode());
        listing.setTargetEntityId(targetEntityId);
        listing.setCreatedByActorId(actorId);
        listing.setVenueClassification(tradingProperties.getVenueClassification().name());
        listing.setAllowedPaymentOptions(paymentOptions);
        listing.setAllowInstantSettlement(request.allowInstantSettlement());
        TradeListing saved = tradeListingRepository.save(listing);

        eventPublisher.publishEvent(new TradeListingCreatedEvent(
                saved.getId(), actorId, "TRADER", saved.getAssetId(), saved.getSellerHolderId(),
                saved.getQuantityTotal(), saved.getPricePerUnit()));
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
        eventPublisher.publishEvent(new TradeListingCancelledEvent(listing.getId(), actorId, "TRADER", listing.getSellerEntityId()));
    }

    @Transactional(readOnly = true)
    public List<TradingVenueOfferResponse> listMarketplaceOffers(
            UUID entityId,
            String search,
            TradingAssetType assetType,
            de.makibytes.registerwerk.deployment.api.TokenStandard tokenStandard,
            TradingVenueCode venueCode,
            PaymentOption paymentOption,
            BigDecimal minPrice,
            BigDecimal maxPrice) {
        ensureTradingEnabled();
        TradingOfferFilter filter = new TradingOfferFilter(search, assetType, tokenStandard, venueCode, paymentOption, minPrice, maxPrice);
        List<TradingVenueOffer> offers = new ArrayList<>();
        for (TradingVenueAdapter adapter : venueAdapters) {
            if (venueCode == null || venueCode == adapter.venueCode()) {
                offers.addAll(adapter.searchOffers(filter));
            }
        }
        List<TradingVenueOfferResponse> visible = new ArrayList<>();
        for (TradingVenueOffer offer : offers) {
            TradeListing listing = offer.listingId() == null ? null : findListing(offer.listingId());
            if (listing != null) {
                if (entityId.equals(listing.getSellerEntityId())) {
                    continue;
                }
                // Bilateral listings are visible to the addressed counterparty only (5C-06).
                if (listing.getTargetEntityId() != null && !listing.getTargetEntityId().equals(entityId)) {
                    continue;
                }
            }
            visible.add(toOfferResponse(offer, listing, entityId));
        }
        return visible;
    }

    public TradeExecutionResponse buy(UUID entityId, UUID actorId, UUID listingId, BuyTradingOfferRequest request) {
        ensureTradingEnabled();
        requireVenueClassified();
        // Row-level lock: the availability check and the quantity decrement must be
        // atomic, or two concurrent buyers of the same units would both be filled.
        TradeListing listing = tradeListingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> unknownListingException(listingId));
        de.makibytes.registerwerk.asset.api.RegisterFreezeGuard.requireOpen(assetRepository, listing.getAssetId(), "Trade");
        if (entityId.equals(listing.getSellerEntityId())) {
            throw new IllegalArgumentException("A company cannot buy its own listing");
        }
        if (listing.getTargetEntityId() != null && !listing.getTargetEntityId().equals(entityId)) {
            // Bilateral listing (5C-06): indistinguishable from an unknown listing for everyone else.
            throw unknownListingException(listingId);
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
        execution.setPaymentOption(paymentOption);
        applyPricing(execution, listing, quantity, paymentOption);
        execution.setCreatedByActorId(actorId);
        if (relatedPartyCheck.sameActor(actorId, listing.getCreatedByActorId())) {
            throw new ComplianceGateException("The same user cannot act for both the buyer and the seller of a trade.");
        }
        execution.setVenueClassification(tradingProperties.getVenueClassification().name());
        execution.setWalletPreferenceMode(resolvedWallet.preferenceMode());
        execution.setWalletEndpointId(resolvedWallet.endpointId());
        // Normalized here (not just in HolderService) so a settlement-created AssetHolder row
        // matches an existing one for the same wallet regardless of checksummed/lowercase input.
        execution.setWalletAddress(de.makibytes.registerwerk.blockchain.api.EvmUtils.normalizeAddress(resolvedWallet.address()));

        requireWithinPriceCollar(listing.getAssetId(), listing.getPricePerUnit(), listing.getCurrency());

        boolean rejected = false;
        if (listing.getVenueCode() == TradingVenueCode.SIMULATED) {
            markRelatedParty(execution, listing, actorId); // peer trades only: external venues' counterparties are unknown
            // 5A-03/5A-06: every gate runs BEFORE any unit is reserved, so an ineligible buyer or
            // seller, a suspended asset or a removed register entry can no longer park a listing
            // for 72h - and a paid trade can no longer discover at confirm time that it never
            // could have settled.
            requireWholeDenomination(assetRepository.findById(listing.getAssetId())
                    .orElseThrow(() -> new EntityNotFoundException("Asset", listing.getAssetId())), quantity);
            assertTradable(execution, TradeCheck.RESERVE, entityId);
            enforceReservationLimits(entityId, listing);
            // 5A-01: the SELLER's per-listing opt-in (plus the demo property, never for chain-deployed
            // assets) is the only way the register moves inside this request. The buyer's company
            // setting is no longer read - it let the wrong party decide about the seller's register.
            boolean instant = listing.isAllowInstantSettlement()
                    && tradingProperties.isDemoInstantSettlement()
                    && !hasConfirmedDeployment(listing.getAssetId());
            if (instant) {
                AssetHolder buyerHolder = settleExecution(execution, actorId);
                execution.setBuyerHolderId(buyerHolder.getId());
                execution.setSettlementStatus(SettlementStatus.SETTLED);
                execution.setSettledAt(Instant.now());
                execution.setInstantSettlement(true);
            } else {
                execution.setSettlementStatus(SettlementStatus.PENDING);
            }
        } else {
            TradingVenueAdapter adapter = venueAdapters.stream()
                    .filter(a -> a.venueCode() == listing.getVenueCode())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No active adapter for venue " + listing.getVenueCode()));
            ExecuteOrderRequest orderRequest = new ExecuteOrderRequest(
                    listingId, quantity, orderType, request.limitPrice(), paymentOption,
                    resolvedWallet.address());
            TradingVenueExecutionResult result = adapter.execute(orderRequest);
            if (result.status() == TradingVenueExecutionResult.Status.REJECTED) {
                // Previously threw here, rolling back the whole transaction — the attempt left
                // no record anywhere, nothing to reconcile against. Persist it as FAILED instead;
                // the listing's available quantity must NOT be decremented for a rejected order.
                rejected = true;
                execution.setSettlementStatus(SettlementStatus.FAILED);
                execution.setFailureReason("Venue rejected order: " + result.errorMessage());
            } else {
                execution.setSettlementStatus(SettlementStatus.PENDING);
            }
        }

        TradeExecution saved = tradeExecutionRepository.save(execution);
        if (rejected) {
            return toTradeExecutionResponse(entityId, saved);
        }

        updateListingAfterExecution(listing, quantity);

        eventPublisher.publishEvent(new TradeExecutedEvent(
                saved.getId(), actorId, "TRADER", listing.getId(), saved.getAssetId(),
                saved.getExecutedQuantity(), saved.getUnitPrice(), saved.getTotalPrice(),
                saved.getBuyerEntityId(), saved.getSellerEntityId()));
        return toTradeExecutionResponse(entityId, saved);
    }

    /** Cancels a still-PENDING trade - nothing has been paid or settled yet, so cancelling is a
     *  status flip plus restoring the listing's available quantity. Only the BUYER may cancel
     *  (5A-03): a seller who cancels after the buyer already sent money (declaration not yet
     *  recorded) would hide a paid trade; the seller's tools are the timeout and, once payment is
     *  declared, confirm / dispute. A buyer cancel starts the re-reservation cool-down (5A-06).
     *  Settled trades are NOT reversible here (see {@link #refundSettledTrade}). */
    public TradeExecutionResponse cancelPendingTrade(UUID entityId, UUID actorId, UUID executionId, String reason) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (!entityId.equals(execution.getBuyerEntityId())) {
            throw new AccessDeniedException("Only the buyer of this trade may cancel it before payment is declared");
        }
        if (execution.getSettlementStatus() != SettlementStatus.PENDING) {
            throw new IllegalStateException(
                    "Trade " + executionId + " is not PENDING (status=" + execution.getSettlementStatus() + ") — cannot cancel");
        }
        transitions.cancelPending(execution, reason, actorId, "TRADER", true);
        log.info("Trade execution cancelled: id={} by={} reason={}", executionId, actorId, reason);
        return toTradeExecutionResponse(entityId, execution);
    }

    /**
     * Records that a SETTLED trade was reversed after the fact — a REGISTRY_ADMIN, step-up +
     * dual-control action for exceptional cases (e.g. a compliance clawback). Deliberately does
     * NOT attempt to automatically reverse the underlying on-chain transfer or cash leg itself —
     * that compensating action (a new forcedTransfer back, or an off-chain payment reversal) is
     * a separate, standard-specific operator action the operator must also perform; this method
     * only records that a refund/reversal decision was made and why, for the audit trail and so
     * reconciliation reports don't count a reversed trade as still-settled.
     */
    public TradeExecutionResponse refundSettledTrade(UUID actorId, UUID executionId, String reason, UUID dualControlApproverId) {
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (execution.getSettlementStatus() != SettlementStatus.SETTLED) {
            throw new IllegalStateException(
                    "Trade " + executionId + " is not SETTLED (status=" + execution.getSettlementStatus() + ") — cannot refund");
        }
        execution.setSettlementStatus(SettlementStatus.REFUNDED);
        execution.setFailureReason(reason);
        TradeExecution saved = tradeExecutionRepository.save(execution);
        eventPublisher.publishEvent(new TradeRefundedEvent(executionId, actorId, "REGISTRY_ADMIN", reason, dualControlApproverId));
        log.warn("Trade execution refunded/reversed: id={} by={} reason={}", executionId, actorId, reason);
        return toTradeExecutionResponse(execution.getBuyerEntityId(), saved);
    }

    /**
     * Buyer declares payment on a PENDING trade. {@code paymentReference} is evidence of the
     * payment the buyer asserts was made (a stablecoin tx hash, a SEPA transfer reference, etc.).
     * This deliberately does NOT credit the register yet — it moves the trade to
     * AWAITING_SELLER_CONFIRMATION and waits for the selling company to independently confirm
     * receipt via {@link #confirmPaymentReceived}, closing the prior gap where the buyer's
     * unverified claim alone moved the register. Idempotent: re-declaring on an
     * already-declared or already-settled trade returns the current state rather than erroring,
     * since a network retry of this call must be safe.
     * <p>
     * This is still not a delivery-vs-payment guarantee in the atomic-on-chain sense —
     * {@code contracts/src/settlement/DvpSettlement.sol} implements that pattern (escrow +
     * atomic settle) but is deliberately NOT wired in here. Doing so would require: a seller-side
     * wallet-signing flow that does not exist anywhere in either portal today; registering the
     * escrow contract in the asset's ERC-3643 identity registry before it could hold tokens;
     * reconciling this method's synchronous contract with the async submit-and-poll pattern every
     * other on-chain write in this codebase uses ({@code BlockchainTransactionService}); and doing
     * all of that with no live-chain test coverage of an escrow flow moving real registrant funds.
     * Building that blind risks a stuck-escrow state, which is worse than the honest off-chain gap
     * this method narrows instead — the same reasoning already applied to gas-bump resubmit (see
     * {@code BlockchainTransactionService.review}).
     */
    public TradeExecutionResponse settlePendingTrade(UUID entityId, UUID actorId, UUID executionId, String paymentReference) {
        ensureTradingEnabled();
        // Row-level lock: prevents a concurrent double-declaration racing this check.
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (!entityId.equals(execution.getBuyerEntityId())) {
            throw new AccessDeniedException("Only the buying company can declare payment on this trade");
        }
        if (execution.getSettlementStatus() == SettlementStatus.SETTLED
                || execution.getSettlementStatus() == SettlementStatus.AWAITING_SELLER_CONFIRMATION
                || execution.getSettlementStatus() == SettlementStatus.PAYMENT_UNRESOLVED) {
            return toTradeExecutionResponse(entityId, execution);
        }
        if (execution.getSettlementStatus() != SettlementStatus.PENDING) {
            throw new IllegalStateException(
                    "Trade " + executionId + " is not PENDING (status=" + execution.getSettlementStatus() + ") — cannot declare payment");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new IllegalArgumentException("A payment reference is required to declare payment");
        }
        // 5A-03: a payment declaration is only accepted for a trade that could still settle, so the
        // buyer is not invited to pay for something the gates would refuse at confirm time.
        assertTradable(execution, TradeCheck.SETTLE, entityId);
        execution.setPaymentReference(paymentReference);
        execution.setPaymentDeclaredAt(Instant.now());
        execution.setSettlementStatus(SettlementStatus.AWAITING_SELLER_CONFIRMATION);
        TradeExecution saved = tradeExecutionRepository.save(execution);
        eventPublisher.publishEvent(new TradePaymentDeclaredEvent(executionId, actorId, "TRADER", paymentReference));
        log.info("Trade payment declared: id={} by={}", executionId, actorId);
        return toTradeExecutionResponse(entityId, saved);
    }

    /**
     * Selling company confirms receipt of the buyer's declared payment — this is the point the
     * register is actually credited (via {@link #settleExecution}). Only the seller may call
     * this: the whole point of splitting declare/confirm is that the party actually receiving
     * payment, not the party sending it, is the one whose word moves the register.
     */
    public TradeExecutionResponse confirmPaymentReceived(UUID entityId, UUID actorId, UUID executionId) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (!entityId.equals(execution.getSellerEntityId())) {
            throw new AccessDeniedException("Only the selling company can confirm receipt of payment");
        }
        if (execution.getSettlementStatus() == SettlementStatus.SETTLED) {
            return toTradeExecutionResponse(entityId, execution);
        }
        if (relatedPartyCheck.sameActor(execution.getCreatedByActorId(), actorId)) {
            throw new ComplianceGateException("The same user cannot act for both the buyer and the seller of a trade.");
        }
        if (execution.getSettlementStatus() != SettlementStatus.AWAITING_SELLER_CONFIRMATION) {
            throw new IllegalStateException("Trade " + executionId + " is not awaiting seller confirmation (status="
                    + execution.getSettlementStatus() + ")");
        }
        try {
            assertTradable(execution, TradeCheck.SETTLE, entityId);
        } catch (IllegalStateException | IllegalArgumentException | InvalidStateTransitionException
                 | EntityNotFoundException e) {
            // The seller says the money arrived but a gate now refuses the transfer. Throwing would
            // leave a paid trade as an HTTP error nobody owns; hand it to the operator queue instead
            // (reservation kept). The gates run before any register row is touched, so nothing to undo.
            String detail = e.getCause() instanceof ComplianceGateException c ? c.getMessage() : e.getMessage();
            transitions.markUnresolved(execution, "GATE_FAILED_AT_CONFIRM", detail, actorId, "TRADER");
            return toTradeExecutionResponse(entityId, execution);
        }
        AssetHolder buyerHolder = settleExecution(execution, actorId);
        execution.setBuyerHolderId(buyerHolder.getId());
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        execution.setSettledAt(Instant.now());
        TradeExecution saved = tradeExecutionRepository.save(execution);
        eventPublisher.publishEvent(new TradePaymentConfirmedEvent(executionId, actorId, "TRADER"));
        log.info("Trade payment confirmed by seller: id={} by={}", executionId, actorId);
        return toTradeExecutionResponse(entityId, saved);
    }

    /**
     * Selling company disputes a buyer's declared payment (asserts it was never received). The
     * trade does NOT fail (5A-03): the buyer may have paid, and re-offering the units while the
     * money is in flight would let the seller sell twice. It moves to PAYMENT_UNRESOLVED with the
     * reservation kept; an operator resolves it (release, return of funds, or force-settle).
     * The register is never touched here.
     */
    public TradeExecutionResponse disputePayment(UUID entityId, UUID actorId, UUID executionId, String reason) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (!entityId.equals(execution.getSellerEntityId())) {
            throw new AccessDeniedException("Only the selling company can dispute payment on this trade");
        }
        if (execution.getSettlementStatus() != SettlementStatus.AWAITING_SELLER_CONFIRMATION) {
            throw new IllegalStateException("Trade " + executionId + " is not awaiting seller confirmation (status="
                    + execution.getSettlementStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to dispute payment");
        }
        execution.setDisputeReason(reason);
        transitions.markUnresolved(execution, "SELLER_DISPUTE", "Seller disputed payment: " + reason, actorId, "TRADER");
        eventPublisher.publishEvent(new TradePaymentDisputedEvent(executionId, actorId, "TRADER", reason));
        log.warn("Trade payment disputed by seller: id={} by={} reason={}", executionId, actorId, reason);
        return toTradeExecutionResponse(entityId, execution);
    }

    // ── Operator resolution of PAYMENT_UNRESOLVED (5A-03) ───────────────────────────────────────

    /**
     * FORCE_SETTLE: the operator (4-eyes, legal basis) decides the payment did arrive. Every gate
     * re-runs first; a failing gate throws to the operator and changes nothing.
     */
    public TradeExecutionResponse forceSettleUnresolved(UUID actorId, UUID executionId, String legalBasis, String note,
                                                        UUID dualControlApproverId) {
        TradeExecution execution = lockUnresolved(executionId);
        assertTradable(execution, TradeCheck.SETTLE, null);
        AssetHolder buyerHolder = settleExecution(execution, actorId);
        execution.setBuyerHolderId(buyerHolder.getId());
        execution.setSettlementStatus(SettlementStatus.SETTLED);
        execution.setSettledAt(Instant.now());
        TradeExecution saved = tradeExecutionRepository.save(execution);
        eventPublisher.publishEvent(new TradeUnresolvedResolvedEvent(
                executionId, actorId, "REGISTRY_ADMIN", "FORCE_SETTLE", legalBasis, note, dualControlApproverId));
        eventPublisher.publishEvent(new TradePaymentConfirmedEvent(executionId, actorId, "REGISTRY_ADMIN"));
        log.warn("Unresolved trade force-settled: id={} by={} basis={}", executionId, actorId, legalBasis);
        return toTradeExecutionResponse(null, saved);
    }

    /** RECORD_RETURN_OF_FUNDS: the buyer's money was returned (evidenced); FAILED, quantity restored. */
    public TradeExecutionResponse recordReturnOfFunds(UUID actorId, UUID executionId, String legalBasis, String note,
                                                      UUID dualControlApproverId) {
        return closeUnresolved(actorId, executionId, "RECORD_RETURN_OF_FUNDS", "Return of funds recorded by operator",
                legalBasis, note, dualControlApproverId);
    }

    /** RELEASE: the seller proved non-receipt; FAILED, quantity restored. */
    public TradeExecutionResponse releaseUnresolved(UUID actorId, UUID executionId, String legalBasis, String note,
                                                    UUID dualControlApproverId) {
        return closeUnresolved(actorId, executionId, "RELEASE", "Released by operator: non-receipt of payment established",
                legalBasis, note, dualControlApproverId);
    }

    private TradeExecutionResponse closeUnresolved(UUID actorId, UUID executionId, String action, String failureReason,
                                                   String legalBasis, String note, UUID dualControlApproverId) {
        TradeExecution execution = lockUnresolved(executionId);
        transitions.failAndRestore(execution, failureReason + (note == null || note.isBlank() ? "" : ": " + note));
        eventPublisher.publishEvent(new TradeUnresolvedResolvedEvent(
                executionId, actorId, "REGISTRY_ADMIN", action, legalBasis, note, dualControlApproverId));
        log.warn("Unresolved trade closed: id={} action={} by={} basis={}", executionId, action, actorId, legalBasis);
        return toTradeExecutionResponse(null, execution);
    }

    private TradeExecution lockUnresolved(UUID executionId) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (execution.getSettlementStatus() != SettlementStatus.PAYMENT_UNRESOLVED) {
            throw new InvalidStateTransitionException("Trade " + executionId + " is not PAYMENT_UNRESOLVED (status="
                    + execution.getSettlementStatus() + ")");
        }
        return execution;
    }

    @Transactional(readOnly = true)
    public TradeConfigResponse config() {
        return new TradeConfigResponse(
                tradingProperties.isDemoInstantSettlement(),
                tradingProperties.getMaxOpenReservationsPerBuyer(),
                tradingProperties.getReservationCooldownHours(),
                tradingProperties.getPendingTimeoutHours());
    }

    @Transactional(readOnly = true)
    public List<TradeExecutionResponse> listHistory(UUID entityId) {
        ensureTradingEnabled();
        return tradeExecutionRepository.findByBuyerEntityIdOrSellerEntityIdOrderByCreatedAtDesc(entityId, entityId).stream()
                .map(execution -> toTradeExecutionResponse(entityId, execution))
                .toList();
    }

    /**
     * Renders a trade confirmation (Wertpapierabrechnung) PDF for a SETTLED trade — empty if the
     * trade doesn't exist, isn't SETTLED yet (nothing final to confirm), or the caller is neither
     * its buyer nor its seller. Generated fresh per call, same as the register-document downloads.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<byte[]> renderConfirmation(UUID entityId, UUID executionId) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getSettlementStatus() != SettlementStatus.SETTLED) {
            return java.util.Optional.empty();
        }
        if (!entityId.equals(execution.getBuyerEntityId()) && !entityId.equals(execution.getSellerEntityId())) {
            return java.util.Optional.empty();
        }
        LegalEntity buyer = legalEntityRepository.findById(execution.getBuyerEntityId()).orElse(null);
        LegalEntity seller = legalEntityRepository.findById(execution.getSellerEntityId()).orElse(null);
        return java.util.Optional.of(TradeConfirmationPdfRenderer.render(execution, buyer, seller));
    }

    /**
     * Same gating as {@link #renderConfirmation(UUID, UUID)}, but renders an ISO 20022-shaped
     * settlement confirmation XML instead of the human-readable PDF — see
     * {@link Iso20022SettlementConfirmationRenderer} for the scoping caveat.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<byte[]> renderIso20022Confirmation(UUID entityId, UUID executionId) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getSettlementStatus() != SettlementStatus.SETTLED) {
            return java.util.Optional.empty();
        }
        if (!entityId.equals(execution.getBuyerEntityId()) && !entityId.equals(execution.getSellerEntityId())) {
            return java.util.Optional.empty();
        }
        LegalEntity buyer = legalEntityRepository.findById(execution.getBuyerEntityId()).orElse(null);
        LegalEntity seller = legalEntityRepository.findById(execution.getSellerEntityId()).orElse(null);
        return java.util.Optional.of(Iso20022SettlementConfirmationRenderer.render(executionId, execution, buyer, seller));
    }

    /** 5C-06: production needs an explicit venue classification backed by a legal opinion. */
    private void requireVenueClassified() {
        if (tradingProperties.isProductionMode()
                && tradingProperties.getVenueClassification() == TradingProperties.VenueClassification.DEMO_ONLY) {
            throw new ComplianceGateException("Peer listings are a demonstration secondary-market workflow, not an "
                    + "authorised trading venue. They are disabled in production until the operator sets "
                    + "registerwerk.trading.venue-classification (BILATERAL_ONLY or LICENSED_VENUE) with a legal-opinion-ref.");
        }
    }

    private UUID resolveTarget(UUID sellerEntityId, UUID targetEntityId) {
        if (targetEntityId == null) {
            if (tradingProperties.getVenueClassification() == TradingProperties.VenueClassification.BILATERAL_ONLY) {
                throw new IllegalArgumentException("Listings must be addressed to one named counterparty "
                        + "(targetEntityId) under the BILATERAL_ONLY venue classification");
            }
            return null;
        }
        if (targetEntityId.equals(sellerEntityId)) {
            throw new IllegalArgumentException("A listing cannot be addressed to your own company");
        }
        if (!legalEntityRepository.existsById(targetEntityId)) {
            throw new EntityNotFoundException("LegalEntity", targetEntityId);
        }
        return targetEntityId;
    }

    /** Copies currency / rail and sets the rounded total with the stored rounding (5A-02). */
    private void applyPricing(TradeExecution execution, TradeListing listing, BigDecimal quantity, PaymentOption option) {
        BigDecimal exact = listing.getPricePerUnit().multiply(quantity);
        execution.setCurrency(listing.getCurrency());
        if (listing.getCurrency() == null) {
            // Legacy listing without a recorded currency: the minor unit is unknown, keep the exact product.
            execution.setTotalPrice(exact);
            return;
        }
        String railCode = option == PaymentOption.STABLECOIN ? listing.getPaymentRailCode() : null;
        int scale = currencyPolicy.scaleFor(option, listing.getCurrency(), railCode);
        execution.setPaymentRailCode(railCode);
        execution.setTotalPriceUnrounded(exact);
        execution.setPriceRoundingScale((short) scale);
        execution.setPriceRoundingMode(Money.MODE.name());
        execution.setTotalPrice(Money.round(exact, scale));
    }

    /** 5A-06: wash-trade / self-dealing hook - refuse linked parties unless explicitly allowed, and flag them. */
    private void markRelatedParty(TradeExecution execution, TradeListing listing, UUID actorId) {
        String sellerWallet = assetHolderRepository.findById(listing.getSellerHolderId())
                .map(AssetHolder::getWalletAddress).orElse(null);
        List<String> reasons = relatedPartyCheck.check(
                execution.getBuyerEntityId(), execution.getSellerEntityId(), execution.getWalletAddress(), sellerWallet);
        if (reasons.isEmpty()) {
            return;
        }
        if (!tradingProperties.isAllowRelatedPartyTrades()) {
            relatedPartyAlerts.blocked(listing.getId(), actorId, execution.getBuyerEntityId(),
                    execution.getSellerEntityId(), reasons);
            throw new ComplianceGateException("This trade is between related parties (" + String.join(", ", reasons)
                    + ") and is not permitted. Contact your registry operator.");
        }
        execution.setRelatedParty(true);
        execution.setRelatedPartyReasons(String.join(",", reasons));
    }

    /** T5-04 price collar (off by default): reject prices far from the last unrelated settled price. */
    private void requireWithinPriceCollar(UUID assetId, BigDecimal price, String currency) {
        int bps = tradingProperties.getMaxPriceDeviationBps();
        if (bps <= 0) {
            return;
        }
        TradeExecution last = tradeExecutionRepository
                .findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(assetId, SettlementStatus.SETTLED)
                .orElse(null);
        if (last == null || last.getUnitPrice().signum() <= 0
                || (last.getCurrency() != null && currency != null && !last.getCurrency().equals(currency))) {
            return; // nothing comparable to measure against
        }
        BigDecimal reference = last.getUnitPrice();
        BigDecimal deviationBps = price.subtract(reference).abs()
                .multiply(BigDecimal.valueOf(10_000)).divide(reference, 4, java.math.RoundingMode.HALF_EVEN);
        if (deviationBps.compareTo(BigDecimal.valueOf(bps)) > 0) {
            throw new IllegalArgumentException("Price " + price.toPlainString() + " deviates more than "
                    + bps + " bps from the last unrelated trade price " + reference.toPlainString());
        }
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
        BigDecimal available = computeAvailableQuantity(holder, holder.getAssetId());
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
                holder.getWalletAddress(),
                asset.getJurisdiction(),
                !offchainSettlementBlocked(asset.getId()),
                tradingProperties.isOffchainSettlementOnDeployedAssets() && hasConfirmedDeployment(asset.getId())
        );
    }

    private BigDecimal computeAvailableQuantity(AssetHolder holder, UUID assetId) {
        BigDecimal openListed = tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(
                holder.getId(), List.of(ListingStatus.OPEN, ListingStatus.PARTIALLY_FILLED));
        // Units reserved by trades still in flight - PENDING, AWAITING_SELLER_CONFIRMATION and
        // PAYMENT_UNRESOLVED (the money may have moved, the units stay put until an operator decides).
        BigDecimal reservedByTrades = tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(
                holder.getId(), SettlementStatus.RESERVING);
        // Units pledged elsewhere (repo desk) are not sellable (5A-09 SPI; nothing until it is implemented).
        BigDecimal pledged = encumbrance.encumbered(holder.getInvestorId(), assetId);
        return holder.getNominalAmount().subtract(openListed).subtract(reservedByTrades).subtract(pledged)
                .max(BigDecimal.ZERO);
    }

    /**
     * T3-09 interim guard. The simulated venue settles by rewriting register rows only; on an
     * asset with a CONFIRMED chain deployment the holder sync then resets the seller to its
     * on-chain balance (the buyer's new row is not chain-derived and stays), so the register
     * exceeds the supply and the seller can sell the same units again. Until the on-chain
     * settlement leg exists (Phase 5) such assets cannot be listed or settled here unless
     * {@code registerwerk.trading.offchain-settlement-on-deployed-assets} is on (demo only).
     */
    private void requireOffchainSettlementAllowed(UUID assetId) {
        // C5: quantities and prices are per WHOLE unit, the register holds raw base units - same guard as every
        // other register-unit flow, whether or not off-chain settlement on deployed assets is switched on.
        de.makibytes.registerwerk.deployment.api.RegisterUnits.requireWholeUnits(
                assetDeploymentRepository, assetId, "Trading");
        if (!offchainSettlementBlocked(assetId)) {
            return;
        }
        throw new de.makibytes.registerwerk.shared.InvalidStateTransitionException(
                "On-chain settlement required for chain-deployed assets: asset " + assetId
                        + " has a confirmed deployment, and off-chain settlement would make the register "
                        + "diverge from the chain.");
    }

    private boolean offchainSettlementBlocked(UUID assetId) {
        return !tradingProperties.isOffchainSettlementOnDeployedAssets() && hasConfirmedDeployment(assetId);
    }

    private boolean hasConfirmedDeployment(UUID assetId) {
        return assetDeploymentRepository.findByAssetId(assetId).stream()
                .anyMatch(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED);
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

    /**
     * Builds the exception for a {@code listingId} not found in the local {@code trade_listing}
     * table. Previously this was always a generic "not found," even when
     * the ID actually belongs to a live external-venue offer (Talos/Archax/AsseTera) — those are
     * aggregated for display in {@link #listMarketplaceOffers} but never persisted locally, so
     * {@code buy()} always 404s on them before the venue-adapter execution branch further down
     * this method could ever run, with no indication to the caller of why. This probes the live
     * external venues (best-effort; a probe failure never masks the original 404) so the error at
     * least distinguishes "this ID is nothing at all" from "this is a real offer, direct execution
     * through this endpoint just isn't wired up for that venue yet."
     */
    private RuntimeException unknownListingException(UUID listingId) {
        for (TradingVenueAdapter adapter : venueAdapters) {
            if (adapter.venueCode() == TradingVenueCode.SIMULATED) {
                continue;
            }
            try {
                boolean offeredThere = adapter.searchOffers(new TradingOfferFilter(null, null, null, null, null, null, null))
                        .stream().anyMatch(offer -> listingId.equals(offer.listingId()));
                if (offeredThere) {
                    return new UnsupportedOperationException(
                            "This offer is listed on " + adapter.venueCode() + " — direct execution through this "
                            + "endpoint is not yet supported for that venue; trade on the venue directly.");
                }
            } catch (Exception e) {
                log.warn("Probe of venue {} while diagnosing unknown listingId={} failed: {}",
                        adapter.venueCode(), listingId, e.getMessage());
            }
        }
        return new EntityNotFoundException("TradeListing", listingId);
    }

    /**
     * Settles a trade by moving units from the seller's holding to the buyer's. {@code actorId}
     * is the entity acting (buyer, whether settling their own immediate buy or a previously
     * PENDING trade) — {@code actorRole} is hardcoded to {@code "TRADER"} to match this class's
     * existing convention for its other audited events ({@link TradeExecutedEvent},
     * {@link TraderSettingsUpdatedEvent}). Both register-change events carry the
     * {@code TradeExecution} id as {@code correlationId()} so the buyer and seller audit rows
     * can be traced back to the trade that produced them.
     */
    private AssetHolder settleExecution(TradeExecution execution, UUID actorId) {
        // Active-only + locked (5A-04): a removed / handed-over register entry keeps its nominal as
        // retained evidence but must never be debited (that inflated the supply) and cannot be
        // settled against.
        AssetHolder sellerHolder = assetHolderRepository.findActiveByIdForUpdate(execution.getSellerHolderId())
                .orElseThrow(() -> new InvalidStateTransitionException("The seller's register entry "
                        + execution.getSellerHolderId() + " was removed - the trade cannot settle."));
        Asset settlementAsset = assetRepository.findById(execution.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", execution.getAssetId()));
        // T3-07: the register is frozen from export until completion (and closed afterwards).
        de.makibytes.registerwerk.asset.api.RegisterFreezeGuard.requireOpen(settlementAsset, "Trade settlement");
        requireOffchainSettlementAllowed(execution.getAssetId());
        // The holder aggregate counts FINALIZED transfers only. Passing FINALIZED reflects that
        // data contract while still enforcing the gate's chain-quarantine and unresolved-
        // compensation freezes immediately before either legal register balance is mutated.
        finalityGate.require(GatedOperation.TRADE_SETTLEMENT_CONFIRM, execution.getAssetId(),
                settlementAsset.getTokenStandard(), FinalityLevel.FINALIZED);
        // Compliance gates — checked before either side of the register is touched. A
        // settlement is a real transfer of registered securities and must be subject to
        // exactly the same KYC/sanctions/Sperrvermerk controls as an operator-initiated
        // forcedTransfer; nothing about "the two parties agreed on a price" exempts it.
        // (The party / target-market / holding-limit / status gates ran in assertTradable() before
        // this point; the caller is responsible for that, see confirm / force-settle / buy.)
        if (sellerHolder.getNominalAmount().compareTo(execution.getExecutedQuantity()) < 0) {
            throw new IllegalArgumentException("Seller no longer holds enough units to settle this trade");
        }
        sellerHolder.setNominalAmount(sellerHolder.getNominalAmount().subtract(execution.getExecutedQuantity()));
        assetHolderRepository.save(sellerHolder);
        // §19(2) no. 2: the seller's register content changed.
        eventPublisher.publishEvent(new de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent(
                sellerHolder.getId(), actorId, "TRADER", execution.getId()));

        // Active-only lookup: a wallet whose only prior AssetHolder row for this asset was
        // removed (soft-deleted) must NOT be silently resurrected by crediting that closed-out
        // row — settlement creates a fresh holder record instead, same as a genuinely new wallet.
        AssetHolder buyerHolder = assetHolderRepository.findActiveByAssetIdAndWalletAddress(execution.getAssetId(), execution.getWalletAddress())
                .filter(holder -> holder.getInvestorId().equals(execution.getBuyerEntityId()))
                .orElseGet(() -> {
                    AssetHolder holder = new AssetHolder();
                    holder.setAssetId(execution.getAssetId());
                    holder.setInvestorId(execution.getBuyerEntityId());
                    holder.setWalletAddress(execution.getWalletAddress());
                    holder.setNominalAmount(BigDecimal.ZERO);
                    holder.setAcquisitionDate(registerClock.today());
                    holder.setWhitelisted(false);
                    return holder;
                });
        boolean newHolder = buyerHolder.getId() == null;
        buyerHolder.setNominalAmount(buyerHolder.getNominalAmount().add(execution.getExecutedQuantity()));
        buyerHolder.setAcquisitionDate(registerClock.today());
        AssetHolder savedBuyer = assetHolderRepository.save(buyerHolder);
        // A brand-new buyer position is an initial entry (§19(2) no. 1); an
        // existing one that grew is a register change (§19(2) no. 2).
        eventPublisher.publishEvent(newHolder
                ? new de.makibytes.registerwerk.asset.events.HolderEnteredEvent(savedBuyer.getId(), actorId, "TRADER", execution.getId())
                : new de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent(savedBuyer.getId(), actorId, "TRADER", execution.getId()));
        return savedBuyer;
    }

    /**
     * Fail-closed compliance gate consulted for both sides of a settlement: KYC approval
     * (GwG §10), unresolved sanctions screening hits (GwG §10 Abs. 1 Nr. 5), and an active
     * §16 eWpG Sperrvermerk on either the entity or its settlement wallet. Previously
     * settlement bypassed all three — an entity could trade regardless of KYC/sanctions
     * status or a legal block on its holding.
     */
    /**
     * MiFID II product-governance gate (F-BLOCKER-11): a buyer whose client category or
     * knowledge/experience falls outside the asset's declared target market cannot acquire it
     * on the secondary market either — distribution restrictions don't stop at issuance. The
     * seller is not checked here: they already hold the position; disposing of it isn't
     * "being sold the product." An asset with no target market configured is unrestricted (see
     * {@link Asset#isEligibleForTargetMarket}), so this never blocks legacy/demo assets.
     */
    private void requireBuyerWithinTargetMarket(TradeExecution execution, Asset asset) {
        LegalEntity buyer = legalEntityRepository.findById(execution.getBuyerEntityId())
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", execution.getBuyerEntityId()));
        SuitabilityAssessment latest = suitabilityAssessmentRepository
                .findFirstByEntityIdOrderByAssessedAtDesc(execution.getBuyerEntityId()).orElse(null);
        boolean eligible = asset.isEligibleForTargetMarket(
                buyer.getClientCategory(), latest != null ? latest.getKnowledgeExperience() : null);
        if (!eligible) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + execution.getBuyerEntityId() + " is outside this asset's MiFID target market — trade cannot settle.");
        }
    }

    /**
     * Per-investor holding limit gate (F-BLOCKER-12): a buyer whose resulting position would
     * exceed their effective maximum holding (their own {@code InvestorLimit} override, or the
     * asset's default) cannot acquire the units on the secondary market. Existing holdings are
     * summed via {@link AssetHolderRepository#findActiveByAssetIdAndWalletAddress} on the
     * settlement wallet — the same row {@link #settleExecution} is about to credit — so this
     * check and the actual credit always agree on which position it's evaluating.
     */
    private void requireBuyerWithinHoldingLimit(TradeExecution execution, Asset asset) {
        BigDecimal maxHolding = investorLimitGate.effectiveMaxHolding(asset, execution.getBuyerEntityId());
        if (maxHolding == null) {
            return;
        }
        BigDecimal existingHolding = assetHolderRepository
                .findActiveByAssetIdAndWalletAddress(execution.getAssetId(), execution.getWalletAddress())
                .filter(holder -> holder.getInvestorId().equals(execution.getBuyerEntityId()))
                .map(AssetHolder::getNominalAmount)
                .orElse(BigDecimal.ZERO);
        if (existingHolding.add(execution.getExecutedQuantity()).compareTo(maxHolding) > 0) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + execution.getBuyerEntityId() + "'s resulting holding would exceed its maximum of "
                    + maxHolding + " — trade cannot settle.");
        }
    }

    /** When the shared gate runs: at reservation time (the units are still in the listing) or once the trade is reserved. */
    private enum TradeCheck { RESERVE, SETTLE }

    /**
     * The single "may this trade exist / settle" test (Phase 5, 5A-03 step 3), run at {@code buy}
     * (before anything is reserved), at payment declaration, again at confirm, and by the
     * operator's force-settle. Order matters and is fail-closed: asset ISSUED and register open,
     * off-chain settlement allowed, seller's register entry active (row-locked) and covering the
     * units after pledges, both parties through the shared {@link PartyEligibilityGate}, then the
     * buyer's target market and holding limit. Nothing here mutates the register.
     */
    private void assertTradable(TradeExecution execution, TradeCheck phase, UUID actingEntityId) {
        Asset asset = assetRepository.findById(execution.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", execution.getAssetId()));
        requireIssued(asset, "Trade");
        // T3-07: the register is frozen from export until completion (and closed afterwards).
        de.makibytes.registerwerk.asset.api.RegisterFreezeGuard.requireOpen(asset, "Trade");
        requireOffchainSettlementAllowed(execution.getAssetId());
        AssetHolder sellerHolder = assetHolderRepository.findActiveByIdForUpdate(execution.getSellerHolderId())
                .orElseThrow(() -> new InvalidStateTransitionException("The seller's register entry "
                        + execution.getSellerHolderId() + " was removed - the trade cannot proceed."));
        // H11: a repo pledge of the same (entity, asset) takes this lock too; the encumbrance below is read after it
        tradeExecutionRepository.lockHolding(execution.getSellerEntityId(), execution.getAssetId());
        requirePartyEligible(execution, execution.getBuyerEntityId(), execution.getWalletAddress(), actingEntityId);
        requirePartyEligible(execution, execution.getSellerEntityId(), sellerHolder.getWalletAddress(), actingEntityId);
        requireBuyerWithinTargetMarket(execution, asset);
        requireBuyerWithinHoldingLimit(execution, asset);

        BigDecimal free = sellerHolder.getNominalAmount()
                .subtract(encumbrance.encumbered(execution.getSellerEntityId(), execution.getAssetId()));
        BigDecimal needed;
        if (phase == TradeCheck.RESERVE) {
            // The units being bought are still counted in the listing; the seller's whole commitment
            // (all open listings + all reserved trades) must fit the current nominal.
            needed = tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(
                            sellerHolder.getId(), List.of(ListingStatus.OPEN, ListingStatus.PARTIALLY_FILLED))
                    .add(tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(
                            sellerHolder.getId(), SettlementStatus.RESERVING));
        } else {
            needed = execution.getExecutedQuantity();
        }
        if (free.compareTo(needed) < 0) {
            throw new IllegalArgumentException("Seller no longer holds enough unencumbered units for this trade");
        }
    }

    /**
     * Eligibility of one trade party. The detailed refusal (sanctions / KYC / Sperrvermerk state) is only
     * shown to that party itself or to the operator ({@code actingEntityId == null}); the other side gets a
     * generic message so a trader cannot probe a counterparty's compliance status (tipping-off). The detail
     * is kept in the server log and, for an unresolved trade, in the audit event.
     */
    private void requirePartyEligible(TradeExecution execution, UUID partyEntityId, String walletAddress, UUID actingEntityId) {
        try {
            partyEligibilityGate.require(partyEntityId, walletAddress, "trade settlement");
        } catch (ComplianceGateException e) {
            if (actingEntityId == null || actingEntityId.equals(partyEntityId)) {
                throw e;
            }
            log.warn("Trade {}: counterparty {} not eligible: {}", execution.getId(), partyEntityId, e.getMessage());
            // cause keeps the detail for the audit event of an unresolved trade (never serialised to the caller)
            throw new ComplianceGateException("The counterparty is currently not eligible for this trade.", e);
        }
    }

    private void requireIssued(Asset asset, String operation) {
        if (asset.getStatus() != AssetStatus.ISSUED) {
            throw new InvalidStateTransitionException(operation + " refused: asset " + asset.getId()
                    + " is " + asset.getStatus() + " - only ISSUED assets can be traded.");
        }
    }

    /**
     * Lot size (5A-05 approved interim, T4-01/T5-05): bonds whose terms record a denomination trade in
     * whole multiples of it. Nothing is enforced where no denomination is recorded.
     */
    private void requireWholeDenomination(Asset asset, BigDecimal quantity) {
        BigDecimal denomination = asset.getDenomination();
        if (denomination == null || denomination.signum() <= 0 || tradingAssetTypeResolver.resolve(asset) != TradingAssetType.BOND) {
            return;
        }
        if (quantity.remainder(denomination).signum() != 0) {
            throw new IllegalArgumentException("Quantity must be a whole multiple of the bond denomination " + denomination);
        }
    }

    /**
     * 5A-06 reservation caps, applied after the gates and before the reservation: one open
     * reservation per buyer and listing, at most N open reservations per buyer, and a cool-down after
     * the buyer cancelled or let a reservation on this listing lapse. The per-buyer advisory lock
     * makes the count race-free across different listings.
     */
    private void enforceReservationLimits(UUID buyerEntityId, TradeListing listing) {
        tradeExecutionRepository.lockBuyerReservations(buyerEntityId.toString());
        if (tradeExecutionRepository.countByBuyerEntityIdAndListingIdAndSettlementStatusIn(
                buyerEntityId, listing.getId(), SettlementStatus.RESERVING) > 0) {
            throw new InvalidStateTransitionException(
                    "You already hold an open reservation on this listing - settle or cancel it first.");
        }
        if (tradeExecutionRepository.countByBuyerEntityIdAndSettlementStatusIn(
                buyerEntityId, SettlementStatus.RESERVING) >= tradingProperties.getMaxOpenReservationsPerBuyer()) {
            throw new InvalidStateTransitionException("You may hold at most "
                    + tradingProperties.getMaxOpenReservationsPerBuyer() + " open reservations at a time.");
        }
        if (tradeExecutionRepository.existsByBuyerEntityIdAndListingIdAndBuyerCooldownUntilAfter(
                buyerEntityId, listing.getId(), Instant.now())) {
            throw new InvalidStateTransitionException("You recently cancelled or let a reservation on this listing lapse; "
                    + "please wait " + tradingProperties.getReservationCooldownHours() + "h before reserving it again.");
        }
    }

    /**
     * 5C-03: a wallet that will receive registered securities must already be known to the platform
     * as belonging to this entity - a signature-verified org member wallet or one of its own address
     * endpoints. A free-text address can no longer become a register wallet.
     */
    private void requireEntityBoundAddress(UUID entityId, String address) {
        String wanted = AddressNormalizer.normalize(requireNotBlank(address, "Wallet address is required"));
        boolean bound = endpointRepository.findByOwnerTypeAndOwnerId(AddressEndpoint.OwnerType.ENTITY, entityId).stream()
                .anyMatch(e -> wanted.equals(AddressNormalizer.normalize(e.getAddress())))
                || orgMemberWalletRepository.findActiveByLegalEntityId(entityId).stream()
                        .map(OrgMemberWallet::getWalletAddress)
                        .anyMatch(w -> wanted.equals(AddressNormalizer.normalize(w)));
        if (!bound) {
            throw new IllegalArgumentException("The wallet address " + address + " is not registered for your company. "
                    + "Add it as an address endpoint (or bind it as a member wallet) first, then select it.");
        }
    }

    private void updateListingAfterExecution(TradeListing listing, BigDecimal executedQuantity) {
        BigDecimal remaining = listing.getQuantityAvailable().subtract(executedQuantity);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            // Invariant guard: must be unreachable with the row lock in buy(); abort
            // rather than persist a negative (oversold) position in a securities register.
            throw new IllegalStateException("Listing " + listing.getId()
                    + " would be oversold by " + remaining.negate() + " units — aborting execution.");
        }
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
            case CUSTOM_ADDRESS -> {
                requireEntityBoundAddress(entityId, walletAddress);
                yield new ResolvedWallet(WalletPreferenceMode.CUSTOM_ADDRESS, null, walletAddress);
            }
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
        // Legacy free-text defaults (stored before 5C-03) must now be bound to the entity too.
        requireEntityBoundAddress(entityId, walletDefault.getWalletAddress());
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
                listing.getCreatedAt(),
                lastTradePrice(listing.getAssetId()),
                listing.isAllowInstantSettlement(),
                listing.getCurrency(),
                listing.getPaymentRailCode(),
                listing.getTargetEntityId(),
                lastTradePrice(listing.getAssetId()) != null
        );
    }

    private TradingVenueOfferResponse toOfferResponse(TradingVenueOffer offer, TradeListing listing, UUID viewerEntityId) {
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
                offer.createdAt(),
                lastTradePrice(offer.assetId()),
                listing != null ? listing.getCurrency() : null,
                listing != null ? listing.getPaymentRailCode() : null,
                listing != null && viewerEntityId.equals(listing.getTargetEntityId())
        );
    }

    /** Reference price for a marketplace listing/offer — the most recent settled trade between
     *  UNRELATED parties for the same asset (5A-06: linked parties cannot move it), or null if
     *  none has settled yet. Indicative only. */
    private BigDecimal lastTradePrice(UUID assetId) {
        if (assetId == null) {
            return null;
        }
        return tradeExecutionRepository
                .findFirstByAssetIdAndSettlementStatusAndRelatedPartyFalseOrderBySettledAtDesc(assetId, SettlementStatus.SETTLED)
                .map(TradeExecution::getUnitPrice)
                .orElse(null);
    }

    private TradeExecutionResponse toTradeExecutionResponse(UUID viewerEntityId, TradeExecution execution) {
        return TradeResponses.execution(viewerEntityId, execution);
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
