package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * T3-02: {@code registerwerk_corporate_action_held_outstanding} — SETTLED corporate actions kept
 * open because nominee-pool (HELD_LOOK_THROUGH) entitlements are unresolved (PARK-T2-18).
 */
@Component
class HeldEntitlementGauge {

    HeldEntitlementGauge(CorporateActionRepository repository, MeterRegistry meterRegistry) {
        Gauge.builder("registerwerk_corporate_action_held_outstanding", repository,
                        CorporateActionRepository::countByHeldOutstandingTrue)
                .description("Settled corporate actions not closed because nominee-pool entitlements are held unresolved")
                .register(meterRegistry);
    }
}
