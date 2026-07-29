package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SIMULATED venue settles the canonical register off-chain, so production must refuse it.
 * Outside production the same condition only warns — otherwise every dev/demo stack, which
 * enables SIMULATED by default, would fail to boot.
 */
class TradingProductionReadinessCheckTest {

    private static TradingProperties propertiesWithSimulated(boolean tradingEnabled, boolean simulatedEnabled) {
        TradingProperties properties = new TradingProperties();
        properties.setEnabled(tradingEnabled);
        properties.venue(TradingVenueCode.SIMULATED).setEnabled(simulatedEnabled);
        return properties;
    }

    @Test
    void productionRefusesEnabledSimulation() {
        TradingProperties properties = propertiesWithSimulated(true, true);

        assertThatThrownBy(() -> new TradingProductionReadinessCheck(properties).check(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be disabled in production");
    }

    @Test
    void productionAcceptsDisabledSimulation() {
        TradingProperties properties = propertiesWithSimulated(true, false);

        assertThatCode(() -> new TradingProductionReadinessCheck(properties).check(true))
                .doesNotThrowAnyException();
    }

    @Test
    void tradingDisabledEntirelyNeedsNoVenueCheck() {
        TradingProperties properties = propertiesWithSimulated(false, true);

        assertThatCode(() -> new TradingProductionReadinessCheck(properties).check(true))
                .doesNotThrowAnyException();
    }

    @Test
    void nonProductionOnlyWarnsSoDevStacksStillBoot() {
        TradingProperties properties = propertiesWithSimulated(true, true);

        assertThatCode(() -> new TradingProductionReadinessCheck(properties).check(false))
                .doesNotThrowAnyException();
    }
}
