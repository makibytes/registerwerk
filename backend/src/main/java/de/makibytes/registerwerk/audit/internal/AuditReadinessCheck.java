package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production readiness for audit integrity (6-11, parked T6-17). Two conditions:
 * <ul>
 *   <li>a signing key provider must be active;</li>
 *   <li>the runtime DB role must not own (or be a superuser over) {@code audit_event}: an owner
 *       can lift any privilege or trigger, so REVOKE/WORM trigger defences do not bind it. Until
 *       migrator and runtime logins are split, {@code registerwerk.audit.allow-owner-runtime-role=true}
 *       acknowledges the interim (warning + gauge stay).</li>
 * </ul>
 * The gauge {@code registerwerk_audit_runtime_role_owns_table} is published in every mode.
 */
@Component
class AuditReadinessCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditReadinessCheck.class);

    private final JdbcTemplate jdbc;
    private final Optional<SigningKeyProvider> signing;
    private final AtomicInteger ownsGauge = new AtomicInteger(-1);

    @Value("${registerwerk.audit.allow-owner-runtime-role:false}")
    private boolean allowOwnerRuntimeRole;

    private final de.makibytes.registerwerk.shared.ProductionMode productionMode;

    AuditReadinessCheck(JdbcTemplate jdbc, Optional<SigningKeyProvider> signing, MeterRegistry registry,
                        org.springframework.core.env.Environment environment) {
        this.productionMode = de.makibytes.registerwerk.shared.ProductionMode.of(environment);
        this.jdbc = jdbc;
        this.signing = signing;
        Gauge.builder("registerwerk_audit_runtime_role_owns_table", ownsGauge, AtomicInteger::get)
                .description("1 if the runtime DB role owns/superuses audit_event (WORM privileges not binding), 0 if not")
                .register(registry);
    }

    @Override
    public void run(ApplicationArguments args) {
        check(productionMode.enabled());
    }

    void check(boolean productionMode) {
        boolean owns = runtimeRoleOwnsAuditTable();
        ownsGauge.set(owns ? 1 : 0);
        if (owns) {
            log.warn("AUDIT: the runtime database role owns audit_event (or is a superuser); REVOKE and the WORM "
                    + "trigger do not bind an owner. Split migrator and runtime logins (docs: platform/audit-log).");
        }
        if (!productionMode) {
            return;
        }
        if (signing.isEmpty()) {
            throw new IllegalStateException("AUDIT: production mode requires an audit signing key provider "
                    + "(registerwerk.audit.signing.provider).");
        }
        if (owns && !allowOwnerRuntimeRole) {
            throw new IllegalStateException("AUDIT: the runtime database role owns audit_event. Use separate migrator "
                    + "and runtime logins (runbook: platform/audit-log), or set "
                    + "registerwerk.audit.allow-owner-runtime-role=true to acknowledge the interim risk.");
        }
    }

    boolean runtimeRoleOwnsAuditTable() {
        Boolean owns = jdbc.queryForObject("""
                SELECT pg_has_role(current_user, c.relowner, 'USAGE')
                       OR (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
                FROM pg_class c WHERE c.oid = 'audit_event'::regclass
                """, Boolean.class);
        return Boolean.TRUE.equals(owns);
    }
}
