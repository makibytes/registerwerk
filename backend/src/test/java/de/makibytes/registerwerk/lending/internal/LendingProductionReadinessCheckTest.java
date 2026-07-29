package de.makibytes.registerwerk.lending.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LendingProductionReadinessCheckTest {

    @Test
    void refusesPartiallyEnabledDeployment() {
        LendingProperties properties = new LendingProperties();
        properties.setEnabled(true);

        assertThatThrownBy(new LendingProductionReadinessCheck(properties)::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release-approved=true");
    }

    @Test
    void acceptsDefaultOffDeployment() {
        new LendingProductionReadinessCheck(new LendingProperties()).check();
    }

    @Test
    void acceptsExplicitlyApprovedDeployment() {
        LendingProperties properties = new LendingProperties();
        properties.setEnabled(true);
        properties.setReleaseApproved(true);

        new LendingProductionReadinessCheck(properties).check();
    }
}
