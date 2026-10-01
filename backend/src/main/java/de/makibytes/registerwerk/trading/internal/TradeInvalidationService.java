package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Pulls listings and in-flight trades off the market when what they stand on disappears (Phase 5,
 * 5A-04 / 5A-05 / 5C-03): the seller's register entry was removed or handed over, the asset was
 * suspended or redeemed, or a party left the platform.
 *
 * <p>Rules, identical for every trigger:
 * <ul>
 *   <li>OPEN / PARTIALLY_FILLED listings -> CANCELLED (event per listing), also a FILLED listing
 *       that still has reserved trades, so a later release cannot re-open it;</li>
 *   <li>PENDING trades (nothing paid) -> CANCELLED, quantity restored, no buyer cool-down;</li>
 *   <li>AWAITING_SELLER_CONFIRMATION trades (cash may have moved) -> PAYMENT_UNRESOLVED for the
 *       operator - never silently FAILED;</li>
 *   <li>PAYMENT_UNRESOLVED trades are already in the queue and are left alone.</li>
 * </ul>
 * Idempotent: a second delivery of the triggering event finds nothing left to change.
 */
@Component
class TradeInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(TradeInvalidationService.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    private static final String SYSTEM = "SYSTEM";

    private final TradeListingRepository listingRepository;
    private final TradeExecutionRepository executionRepository;
    private final TradeTransitions transitions;
    private final ApplicationEventPublisher eventPublisher;

    TradeInvalidationService(TradeListingRepository listingRepository, TradeExecutionRepository executionRepository,
                             TradeTransitions transitions, ApplicationEventPublisher eventPublisher) {
        this.listingRepository = listingRepository;
        this.executionRepository = executionRepository;
        this.transitions = transitions;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    void onHolderRemoved(UUID holderId) {
        apply(listingRepository.findBySellerHolderId(holderId),
                executionRepository.findBySellerHolderIdAndSettlementStatusIn(holderId, SettlementStatus.RESERVING),
                "REGISTER_ENTRY_REMOVED", "Seller's register entry was removed or handed over");
    }

    @Transactional
    void onAssetUnavailable(UUID assetId, String source, String reason) {
        apply(listingRepository.findByAssetId(assetId),
                executionRepository.findByAssetIdAndSettlementStatusIn(assetId, SettlementStatus.RESERVING),
                source, reason);
    }

    @Transactional
    void onEntityOffboarded(UUID entityId) {
        apply(listingRepository.findBySellerEntityIdOrderByCreatedAtDesc(entityId),
                executionRepository.findByPartyAndSettlementStatusIn(entityId, SettlementStatus.RESERVING),
                "CUSTOMER_OFFBOARDED", "A trade party left the platform");
    }

    private void apply(List<TradeListing> listings, Collection<TradeExecution> candidates, String source, String reason) {
        // Lock order everywhere in this module is execution -> listing (cancel, timeout, release all do
        // it), so lock the affected executions first, in a stable order, before touching any listing.
        List<TradeExecution> locked = new ArrayList<>();
        candidates.stream().map(TradeExecution::getId).sorted().forEach(id ->
                executionRepository.findByIdForUpdate(id).ifPresent(locked::add));

        int cancelledListings = 0;
        for (TradeListing scanned : listings) {
            TradeListing listing = listingRepository.findByIdForUpdate(scanned.getId()).orElse(null);
            if (listing == null || listing.getStatus() == ListingStatus.CANCELLED) {
                continue;
            }
            boolean open = listing.getStatus() == ListingStatus.OPEN || listing.getStatus() == ListingStatus.PARTIALLY_FILLED;
            boolean filledButReserved = listing.getStatus() == ListingStatus.FILLED
                    && executionRepository.countByListingIdAndSettlementStatusIn(listing.getId(), SettlementStatus.RESERVING) > 0;
            if (open || filledButReserved) {
                listing.setStatus(ListingStatus.CANCELLED);
                listingRepository.save(listing);
                eventPublisher.publishEvent(new TradeListingCancelledEvent(
                        listing.getId(), SYSTEM_ACTOR, SYSTEM, listing.getSellerEntityId()));
                cancelledListings++;
            }
        }
        int cancelledTrades = 0;
        int unresolved = 0;
        for (TradeExecution execution : locked) {
            if (execution.getSettlementStatus() == SettlementStatus.PENDING) {
                transitions.cancelPending(execution, reason, SYSTEM_ACTOR, SYSTEM, false);
                cancelledTrades++;
            } else if (execution.getSettlementStatus() == SettlementStatus.AWAITING_SELLER_CONFIRMATION) {
                transitions.markUnresolved(execution, source, reason, SYSTEM_ACTOR, SYSTEM);
                unresolved++;
            }
        }
        if (cancelledListings + cancelledTrades + unresolved > 0) {
            log.info("Trade invalidation ({}): {} listing(s) cancelled, {} pending trade(s) cancelled, {} moved to PAYMENT_UNRESOLVED",
                    source, cancelledListings, cancelledTrades, unresolved);
        }
    }
}
