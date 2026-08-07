package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.events.TradeListingCreatedEvent;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentConfirmedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDeclaredEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import de.makibytes.registerwerk.trading.events.TradePendingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePendingTimedOutEvent;
import de.makibytes.registerwerk.trading.events.TradeRefundedEvent;
import de.makibytes.registerwerk.trading.events.TraderSettingsUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.InvestorLimitGate;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.trading.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final TradingProperties tradingProperties;
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
    private final ScreeningGate screeningGate;
    private final HolderBlockGate holderBlockGate;

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
            ScreeningGate screeningGate,
            HolderBlockGate holderBlockGate) {
        this.tradingProperties = tradingProperties;
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
        this.screeningGate = screeningGate;
        this.holderBlockGate = holderBlockGate;
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

        eventPublisher.publishEvent(new TraderSettingsUpdatedEvent(
                entityId, actorId, "TRADER", settings.getDefaultPaymentOption(), settings.isImmediateSettlementEnabled()));
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
        AssetHolder holder = assetHolderRepository.findById(request.holderId())
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", request.holderId()));
        if (!entityId.equals(holder.getInvestorId())) {
            throw new AccessDeniedException("This holding does not belong to your company");
        }
        Asset asset = assetRepository.findById(holder.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", holder.getAssetId()));
        if (investorLimitGate.isLockedUp(asset.getId(), entityId)) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + entityId + "'s holding in this asset is under a lockup — cannot list for sale.");
        }
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
        return offers.stream()
                .filter(offer -> offer.listingId() == null
                        || !entityId.equals(findListing(offer.listingId()).getSellerEntityId()))
                .map(this::toOfferResponse)
                .toList();
    }

    public TradeExecutionResponse buy(UUID entityId, UUID actorId, UUID listingId, BuyTradingOfferRequest request) {
        ensureTradingEnabled();
        // Row-level lock: the availability check and the quantity decrement must be
        // atomic, or two concurrent buyers of the same units would both be filled.
        TradeListing listing = tradeListingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> unknownListingException(listingId));
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
        // Normalized here (not just in HolderService) so a settlement-created AssetHolder row
        // matches an existing one for the same wallet regardless of checksummed/lowercase input.
        execution.setWalletAddress(de.makibytes.registerwerk.blockchain.api.EvmUtils.normalizeAddress(resolvedWallet.address()));

        boolean rejected = false;
        if (listing.getVenueCode() == TradingVenueCode.SIMULATED) {
            if (settings.isImmediateSettlementEnabled()) {
                AssetHolder buyerHolder = settleExecution(execution, actorId);
                execution.setBuyerHolderId(buyerHolder.getId());
                execution.setSettlementStatus(SettlementStatus.SETTLED);
                execution.setSettledAt(Instant.now());
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

    /** Cancels a still-PENDING trade — nothing has settled on-chain yet, so cancelling is just a
     *  status flip plus restoring the listing's available quantity. Settled trades are NOT
     *  reversible through this method (see {@link #refundSettledTrade}); once the buyer has
     *  declared payment (AWAITING_SELLER_CONFIRMATION), the trade can only be resolved by the
     *  seller via {@link #confirmPaymentReceived} or {@link #disputePayment}. */
    public TradeExecutionResponse cancelPendingTrade(UUID entityId, UUID actorId, UUID executionId, String reason) {
        ensureTradingEnabled();
        TradeExecution execution = tradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (!entityId.equals(execution.getBuyerEntityId()) && !entityId.equals(execution.getSellerEntityId())) {
            throw new AccessDeniedException("Only the buyer or seller of this trade may cancel it");
        }
        if (execution.getSettlementStatus() != SettlementStatus.PENDING) {
            throw new IllegalStateException(
                    "Trade " + executionId + " is not PENDING (status=" + execution.getSettlementStatus() + ") — cannot cancel");
        }
        execution.setSettlementStatus(SettlementStatus.CANCELLED);
        execution.setFailureReason(reason);
        TradeExecution saved = tradeExecutionRepository.save(execution);
        restoreListingAvailability(execution);

        eventPublisher.publishEvent(new TradePendingCancelledEvent(executionId, actorId, "TRADER", reason));
        log.info("Trade execution cancelled: id={} by={} reason={}", executionId, actorId, reason);
        return toTradeExecutionResponse(entityId, saved);
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

    /** Daily job: a PENDING trade that never gets a payment declaration, or an
     *  AWAITING_SELLER_CONFIRMATION trade the seller never confirms/disputes, within the
     *  configured timeout is marked FAILED and its reserved quantity is released back to the
     *  listing — previously this left the units permanently stranded (unavailable to sell)
     *  because only status was flipped, never {@link #restoreListingAvailability}. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 15 6 * * *")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "tradePendingTimeout", lockAtMostFor = "PT30M")
    @Transactional
    public void timeoutStuckPendingTrades() {
        Instant cutoff = Instant.now().minus(tradingProperties.getPendingTimeoutHours(), java.time.temporal.ChronoUnit.HOURS);

        List<TradeExecution> stuckPending = tradeExecutionRepository.findBySettlementStatusAndCreatedAtBefore(
                SettlementStatus.PENDING, cutoff);
        for (TradeExecution execution : stuckPending) {
            execution.setSettlementStatus(SettlementStatus.FAILED);
            execution.setFailureReason("Timed out awaiting payment declaration after "
                    + tradingProperties.getPendingTimeoutHours() + "h");
            tradeExecutionRepository.save(execution);
            restoreListingAvailability(execution);
            eventPublisher.publishEvent(new TradePendingTimedOutEvent(
                    execution.getId(), "SYSTEM", tradingProperties.getPendingTimeoutHours()));
            log.warn("Trade execution timed out awaiting payment declaration: id={}", execution.getId());
        }

        List<TradeExecution> stuckAwaitingConfirmation = tradeExecutionRepository
                .findBySettlementStatusAndPaymentDeclaredAtBefore(SettlementStatus.AWAITING_SELLER_CONFIRMATION, cutoff);
        for (TradeExecution execution : stuckAwaitingConfirmation) {
            execution.setSettlementStatus(SettlementStatus.FAILED);
            execution.setFailureReason("Timed out awaiting seller confirmation of payment after "
                    + tradingProperties.getPendingTimeoutHours() + "h");
            tradeExecutionRepository.save(execution);
            restoreListingAvailability(execution);
            eventPublisher.publishEvent(new TradePendingTimedOutEvent(
                    execution.getId(), "SYSTEM", tradingProperties.getPendingTimeoutHours()));
            log.warn("Trade execution timed out awaiting seller confirmation: id={}", execution.getId());
        }

        int total = stuckPending.size() + stuckAwaitingConfirmation.size();
        if (total > 0) {
            log.info("Timed out {} stuck trade execution(s) ({} awaiting payment, {} awaiting seller confirmation).",
                    total, stuckPending.size(), stuckAwaitingConfirmation.size());
        }
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
                || execution.getSettlementStatus() == SettlementStatus.AWAITING_SELLER_CONFIRMATION) {
            return toTradeExecutionResponse(entityId, execution);
        }
        if (execution.getSettlementStatus() != SettlementStatus.PENDING) {
            throw new IllegalStateException(
                    "Trade " + executionId + " is not PENDING (status=" + execution.getSettlementStatus() + ") — cannot declare payment");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new IllegalArgumentException("A payment reference is required to declare payment");
        }
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
        if (execution.getSettlementStatus() != SettlementStatus.AWAITING_SELLER_CONFIRMATION) {
            throw new IllegalStateException("Trade " + executionId + " is not awaiting seller confirmation (status="
                    + execution.getSettlementStatus() + ")");
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
     * trade fails and its reserved quantity is released back to the listing — the register is
     * never touched, since {@link #settleExecution} is only ever reachable from
     * {@link #confirmPaymentReceived}.
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
        execution.setSettlementStatus(SettlementStatus.FAILED);
        execution.setFailureReason("Seller disputed payment: " + reason);
        TradeExecution saved = tradeExecutionRepository.save(execution);
        restoreListingAvailability(execution);
        eventPublisher.publishEvent(new TradePaymentDisputedEvent(executionId, actorId, "TRADER", reason));
        log.warn("Trade payment disputed by seller: id={} by={} reason={}", executionId, actorId, reason);
        return toTradeExecutionResponse(entityId, saved);
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
                holder.getWalletAddress(),
                asset.getJurisdiction()
        );
    }

    private BigDecimal computeAvailableQuantity(UUID holderId, BigDecimal nominalAmount) {
        BigDecimal openListed = tradeListingRepository.sumQuantityAvailableBySellerHolderIdAndStatusIn(
                holderId, List.of(ListingStatus.OPEN, ListingStatus.PARTIALLY_FILLED));
        // Units reserved by trades still in flight — PENDING (payment not yet declared) and
        // AWAITING_SELLER_CONFIRMATION (payment declared, not yet confirmed) — are not available.
        BigDecimal reservedByTrades = tradeExecutionRepository.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(
                holderId, List.of(SettlementStatus.PENDING, SettlementStatus.AWAITING_SELLER_CONFIRMATION));
        return nominalAmount.subtract(openListed).subtract(reservedByTrades).max(BigDecimal.ZERO);
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
        AssetHolder sellerHolder = assetHolderRepository.findById(execution.getSellerHolderId())
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", execution.getSellerHolderId()));
        // Compliance gates — checked before either side of the register is touched. A
        // settlement is a real transfer of registered securities and must be subject to
        // exactly the same KYC/sanctions/Sperrvermerk controls as an operator-initiated
        // forcedTransfer; nothing about "the two parties agreed on a price" exempts it.
        requireCompliant(execution.getBuyerEntityId(), execution.getWalletAddress());
        requireCompliant(execution.getSellerEntityId(), sellerHolder.getWalletAddress());
        requireBuyerWithinTargetMarket(execution);
        requireBuyerWithinHoldingLimit(execution);
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
                    holder.setAcquisitionDate(LocalDate.now());
                    holder.setWhitelisted(false);
                    return holder;
                });
        boolean newHolder = buyerHolder.getId() == null;
        buyerHolder.setNominalAmount(buyerHolder.getNominalAmount().add(execution.getExecutedQuantity()));
        buyerHolder.setAcquisitionDate(LocalDate.now());
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
    private void requireBuyerWithinTargetMarket(TradeExecution execution) {
        Asset asset = assetRepository.findById(execution.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", execution.getAssetId()));
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
    private void requireBuyerWithinHoldingLimit(TradeExecution execution) {
        Asset asset = assetRepository.findById(execution.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", execution.getAssetId()));
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

    private void requireCompliant(UUID entityId, String walletAddress) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        if (entity.getKycStatus() != KycStatus.APPROVED) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + entityId + " does not have an approved KYC status (current: "
                    + entity.getKycStatus() + ") — trade cannot settle.");
        }
        if (screeningGate.hasUnresolvedHit(entityId)) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + entityId + " has an unresolved sanctions screening hit — trade cannot settle.");
        }
        if (holderBlockGate.isBlocked(entityId, walletAddress)) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Entity " + entityId + " (or its settlement wallet) is subject to an active "
                    + "§16 eWpG Sperrvermerk (legal block) — trade cannot settle.");
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

    /** Releases a trade's reserved quantity back to its listing — used by every terminal path
     *  that does NOT end in SETTLED (cancel, dispute, timeout), so the seller's units become
     *  sellable again instead of being silently and permanently stranded. */
    private void restoreListingAvailability(TradeExecution execution) {
        tradeListingRepository.findByIdForUpdate(execution.getListingId()).ifPresent(listing -> {
            listing.setQuantityAvailable(listing.getQuantityAvailable().add(execution.getExecutedQuantity()));
            if (listing.getStatus() == ListingStatus.FILLED || listing.getStatus() == ListingStatus.PARTIALLY_FILLED) {
                listing.setStatus(ListingStatus.OPEN);
            }
            tradeListingRepository.save(listing);
        });
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
                listing.getCreatedAt(),
                lastTradePrice(listing.getAssetId())
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
                offer.createdAt(),
                lastTradePrice(offer.assetId())
        );
    }

    /** Reference price for a marketplace listing/offer — the most recent
     *  settled trade for the same asset, or null if none has settled yet. */
    private BigDecimal lastTradePrice(UUID assetId) {
        if (assetId == null) {
            return null;
        }
        return tradeExecutionRepository
                .findFirstByAssetIdAndSettlementStatusOrderBySettledAtDesc(assetId, SettlementStatus.SETTLED)
                .map(TradeExecution::getUnitPrice)
                .orElse(null);
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
                execution.getSettledAt(),
                execution.getFailureReason(),
                execution.getPaymentReference(),
                execution.getPaymentDeclaredAt()
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
