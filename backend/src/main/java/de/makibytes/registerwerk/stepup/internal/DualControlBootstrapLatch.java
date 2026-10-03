package de.makibytes.registerwerk.stepup.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The persisted, one-way "bootstrap complete" fact (Wave 0a C3, table {@code dual_control_bootstrap}).
 *
 * <p>While fewer than two enabled, TOTP-enrolled REGISTRY_ADMINs have <em>ever</em> existed, a second approver
 * cannot exist and {@code requireIfNotBootstrap} lets a single step-up through. The latch closes the first
 * time two exist at once - the database closes it itself the instant that happens - and never re-opens:
 * losing a colleague afterwards must not bring the single-step-up path back.
 */
@Component
class DualControlBootstrapLatch {

    private final JdbcTemplate jdbc;

    DualControlBootstrapLatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True once two enabled, enrolled administrators have existed (closing the latch now if that is already so). */
    boolean isComplete() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT dual_control_bootstrap_observe()", Boolean.class));
    }
}
