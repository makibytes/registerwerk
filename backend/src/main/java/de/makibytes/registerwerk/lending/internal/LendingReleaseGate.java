package de.makibytes.registerwerk.lending.internal;

import org.springframework.stereotype.Component;

/** Service-boundary gate so internal callers cannot bypass the HTTP release switch. */
@Component
class LendingReleaseGate {

    static final String DISABLED_MESSAGE = "Lending is disabled pending explicit release approval";

    private final LendingProperties properties;

    LendingReleaseGate(LendingProperties properties) {
        this.properties = properties;
    }

    void requireReleased() {
        if (!properties.isReleased()) {
            throw new UnsupportedOperationException(DISABLED_MESSAGE);
        }
    }
}
