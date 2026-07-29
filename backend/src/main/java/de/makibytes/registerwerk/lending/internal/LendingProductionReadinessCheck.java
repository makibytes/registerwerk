package de.makibytes.registerwerk.lending.internal;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Refuses a partially enabled lending deployment instead of silently exposing an unsafe API. */
@Component
class LendingProductionReadinessCheck {

    private final LendingProperties properties;

    LendingProductionReadinessCheck(LendingProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void check() {
        if (properties.isEnabled() && !properties.isReleaseApproved()) {
            throw new IllegalStateException(
                    "LENDING: registerwerk.lending.enabled=true requires explicit "
                            + "registerwerk.lending.release-approved=true");
        }
    }
}
