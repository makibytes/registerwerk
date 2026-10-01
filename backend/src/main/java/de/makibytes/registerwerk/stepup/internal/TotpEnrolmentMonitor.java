package de.makibytes.registerwerk.stepup.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code registerwerk_stepup_unenrolled_operators}: enabled local REGISTRY_ADMIN / COMPLIANCE_OFFICER accounts
 * older than seven days without a TOTP enrolment. A warning metric on purpose, never a boot failure
 * (that would brick a deployment whose only admin has not enrolled yet); alert on it &gt; 0.
 */
@Component
class TotpEnrolmentMonitor {

    TotpEnrolmentMonitor(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        Gauge.builder("registerwerk_stepup_unenrolled_operators", jdbc, TotpEnrolmentMonitor::count)
                .description("Enabled local operators with a privileged role, older than 7 days, without TOTP enrolment")
                .register(meterRegistry);
    }

    private static double count(JdbcTemplate jdbc) {
        try {
            Long n = jdbc.queryForObject("""
                    SELECT count(*) FROM app_user u
                    WHERE u.enabled AND NOT u.totp_enabled AND u.auth_provider = 'LOCAL'
                      AND u.created_at < now() - interval '7 days'
                      AND EXISTS (SELECT 1 FROM app_user_role r WHERE r.app_user_id = u.id
                                  AND r.role IN ('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'))
                    """, Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
