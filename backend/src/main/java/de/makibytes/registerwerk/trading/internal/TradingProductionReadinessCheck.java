package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard: the SIMULATED trading venue must not be enabled in production.
 *
 * <p>SIMULATED settles trades by mutating the off-chain register (the legally canonical
 * holdings under eWpG §16) with no corresponding on-chain transfer. That directly
 * contradicts the "registry is canonical AND consistent with chain" invariant enforced
 * by the chain-drift detector, which would otherwise raise a CRITICAL divergence for
 * every simulated sale. It is a demo/testing convenience only.
 *
 * <p>Activated by {@code REGISTERWERK_PRODUCTION_MODE=true} (same switch the auth-module
 * readiness check uses). Outside production the warning still fires so the risk is visible.
 */
@Component
class TradingProductionReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(TradingProductionReadinessCheck.class);

    private final TradingProperties tradingProperties;

    TradingProductionReadinessCheck(TradingProperties tradingProperties) {
        this.tradingProperties = tradingProperties;
    }

    @PostConstruct
    void check() {
        check(tradingProperties.isProductionMode());
    }

    void check(boolean productionMode) {
        if (!tradingProperties.isEnabled()) {
            return; // trading off entirely — no venue can settle anything
        }
        // 5A-01: the instant path moves the register with no cash evidence at all - never in production.
        if (productionMode && tradingProperties.isDemoInstantSettlement()) {
            throw new IllegalStateException("TRADING: registerwerk.trading.demo-instant-settlement=true moves the "
                    + "register without any cash leg and must not be enabled in production.");
        }
        // 5C-06 (parked T5-06): a non-demo classification is a claim about the operator's regulatory
        // position and must point at the legal opinion that supports it.
        if (tradingProperties.getVenueClassification() != TradingProperties.VenueClassification.DEMO_ONLY
                && (tradingProperties.getLegalOpinionRef() == null || tradingProperties.getLegalOpinionRef().isBlank())) {
            throw new IllegalStateException("TRADING: registerwerk.trading.venue-classification="
                    + tradingProperties.getVenueClassification() + " requires registerwerk.trading.legal-opinion-ref.");
        }
        boolean simulatedEnabled = tradingProperties.venue(TradingVenueCode.SIMULATED).isEnabled();
        if (!simulatedEnabled) {
            return;
        }
        if (productionMode && tradingProperties.getVenueClassification() == TradingProperties.VenueClassification.DEMO_ONLY) {
            log.warn("TRADING: venue-classification=DEMO_ONLY - peer listings and purchases are refused at runtime in production mode.");
        }
        String message = "TRADING: the SIMULATED venue settles the canonical register off-chain with no "
                + "on-chain leg and must be disabled in production "
                + "(set registerwerk.trading.venues.SIMULATED.enabled=false).";
        if (productionMode) {
            throw new IllegalStateException(message);
        }
        log.warn("*** {} *** (allowed here because production mode is off)", message);
    }
}
