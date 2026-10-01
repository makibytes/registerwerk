package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.events.LendingCollateralReconciliationNeededEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** Normalised (lower-case) method-name prefixes of every admin path that can remove tokens from a holder. */
    private static final List<String> FORCED_MOVE_PREFIXES = List.of(
            "forcedtransfer", "forceburn", "batchforcedtransfer", "batchburn", "burn", "recover");

    private final LendingMarketRepository marketRepository;
    private final ApplicationEventPublisher eventPublisher;

    ForcedTransferReconciliationListener(LendingMarketRepository marketRepository, ApplicationEventPublisher eventPublisher) {
        this.marketRepository = marketRepository;
        this.eventPublisher = eventPublisher;
    }

    static boolean isForcedMove(String methodName) {
        if (methodName == null) return false;
        String n = methodName.toLowerCase(Locale.ROOT);
        return FORCED_MOVE_PREFIXES.stream().anyMatch(n::startsWith);
    }

    /** 5A-10: the single {@code from} and every element of a list-valued {@code froms}. */
    static List<String> sources(Map<String, Object> payload) {
        List<String> out = new ArrayList<>();
        addAll(out, payload.get("from"));
        addAll(out, payload.get("froms"));
        addAll(out, payload.get("lostWallet"));
        return out;
    }

    private static void addAll(List<String> out, Object value) {
        if (value == null) return;
        if (value instanceof Collection<?> c) {
            c.forEach(v -> addAll(out, v));
        } else if (value instanceof Object[] arr) {
            for (Object v : arr) addAll(out, v);
        } else {
            for (String part : value.toString().split("[,\\s]+")) {
                if (!part.isBlank()) out.add(part.replaceAll("[\\[\\]\"]", ""));
            }
        }
    }

    @ApplicationModuleListener
    void onTokenAdminAction(TokenAdminActionEvent event) {
        if (!isForcedMove(event.methodName())) {
            return;
        }
        Map<String, Object> payload = event.payload();
        for (String from : sources(payload)) {
            marketRepository.findByMarketAddressIgnoreCase(from).ifPresent(market -> {
                log.warn("Forced move '{}' on lending market {} (id={}) — collateral accounting may now be "
                        + "desynced; an operator must call reconcileCollateral for the affected borrower.",
                        event.methodName(), market.getMarketAddress(), market.getId());
                eventPublisher.publishEvent(new LendingCollateralReconciliationNeededEvent(
                        market.getId(), market.getMarketAddress(), event.methodName(),
                        stringValue(payload.get("to")), stringValue(payload.get("value"),
                                payload.get("amount"))));
            });
        }
    }

    private static String stringValue(Object v) {
        return v != null ? v.toString() : null;
    }

    private static String stringValue(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        return v != null ? v.toString() : null;
    }
}
