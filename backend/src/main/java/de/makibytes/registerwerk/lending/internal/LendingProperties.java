package de.makibytes.registerwerk.lending.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Release controls for the lending/repo module.
 *
 * <p>Both switches deliberately default to {@code false}. Enabling the endpoints is not enough:
 * the operator must also record that the release gate has been approved after the legal,
 * collateral-control, oracle, and operational controls have been completed.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.lending")
public class LendingProperties {

    private boolean enabled;
    private boolean releaseApproved;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isReleaseApproved() {
        return releaseApproved;
    }

    public void setReleaseApproved(boolean releaseApproved) {
        this.releaseApproved = releaseApproved;
    }

    boolean isReleased() {
        return enabled && releaseApproved;
    }
}
