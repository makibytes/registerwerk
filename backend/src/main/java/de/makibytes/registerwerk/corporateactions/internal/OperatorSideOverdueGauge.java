package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.shared.RegisterClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 9A-04R: {@code registerwerk_corporate_action_operator_side_overdue} - past-due coupon / redemption actions that wait
 * for the OPERATOR (confirmation missing, settlement not dispatched, manual-settle lag). They are deliberately not
 * counted toward bond OVERDUE / DEFAULTED or coupon MISSED ({@link CorporateActionBlocks#responsibleSide}); this gauge
 * (and its alert) is how the operator's own lag stays visible.
 */
@Component
class OperatorSideOverdueGauge {

    OperatorSideOverdueGauge(CorporateActionRepository repository, RegisterClock registerClock,
                             MeterRegistry meterRegistry) {
        Gauge.builder("registerwerk_corporate_action_operator_side_overdue", repository,
                        r -> r.countOperatorSideOverdue(registerClock.today()))
                .description("Past-due coupon/redemption actions waiting for the operator (not counted as issuer non-payment)")
                .register(meterRegistry);
    }
}
