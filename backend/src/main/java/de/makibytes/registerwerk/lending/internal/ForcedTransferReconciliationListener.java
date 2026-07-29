package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.events.LendingCollateralReconciliationNeededEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Detects when an operator's forced-transfer/force-burn (either the generic
 * {@code TokenAdminService} path or the ERC-3643 {@code Erc3643LifecycleService} path — both
 * publish {@link TokenAdminActionEvent} through the same chokepoint) moves collateral out of a
 * known {@code EwpgRepoMarket}'s own balance. Surfaces the desync for
 * operator follow-up via {@link LendingCollateralReconciliationNeededEvent} — deliberately does
 * NOT call {@code EwpgRepoMarket.reconcileCollateral} automatically, since only an off-chain
 * reconciliation of the specific forced-transfer transaction can correctly attribute the
 * reduction to one borrower's position (see that method's own NatSpec).
 */
@Component
class ForcedTransferReconciliationListener {

    private static final Logger log = LoggerFactory.getLogger(ForcedTransferReconciliationListener.class);

    private static final Set<String> FORCED_MOVE_METHODS = Set.of(
            "forcedTransfer", "forcedTransferSingle", "forceBurn", "forceBurnSingle");

    private final LendingMarketRepository marketRepository;
    private final ApplicationEventPublisher eventPublisher;

    ForcedTransferReconciliationListener(LendingMarketRepository marketRepository, ApplicationEventPublisher eventPublisher) {
        this.marketRepository = marketRepository;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onTokenAdminAction(TokenAdminActionEvent event) {
        if (!FORCED_MOVE_METHODS.contains(event.methodName())) {
            return;
        }
        Object from = event.payload().get("from");
        if (from == null) {
            return;
        }
        marketRepository.findByMarketAddressIgnoreCase(from.toString()).ifPresent(market -> {
            log.warn("Forced move '{}' on lending market {} (id={}) — collateral accounting may now be "
                    + "desynced; an operator must call reconcileCollateral for the affected borrower.",
                    event.methodName(), market.getMarketAddress(), market.getId());
            eventPublisher.publishEvent(new LendingCollateralReconciliationNeededEvent(
                    market.getId(), market.getMarketAddress(), event.methodName(),
                    stringValue(event.payload().get("to")), stringValue(event.payload().get("value"),
                            event.payload().get("amount"))));
        });
    }

    private static String stringValue(Object v) {
        return v != null ? v.toString() : null;
    }

    private static String stringValue(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        return v != null ? v.toString() : null;
    }
}
