package de.makibytes.registerwerk.lending.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LendingReleaseGateTest {

    @Test
    void defaultsOffAndRefusesServiceAccess() {
        LendingProperties properties = new LendingProperties();
        LendingReleaseGate gate = new LendingReleaseGate(properties);

        assertThatThrownBy(gate::requireReleased)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("explicit release approval");
    }

    @Test
    void enablingOnlyTheFeatureStillFailsClosed() {
        LendingProperties properties = new LendingProperties();
        properties.setEnabled(true);

        assertThatThrownBy(new LendingReleaseGate(properties)::requireReleased)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void permitsAccessOnlyWhenEnabledAndApproved() {
        LendingProperties properties = new LendingProperties();
        properties.setEnabled(true);
        properties.setReleaseApproved(true);

        new LendingReleaseGate(properties).requireReleased();
    }
}
