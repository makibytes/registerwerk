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
 * Production readiness for audit integrity (6-11, T6-17). Conditions:
 * <ul>
 *   <li>a signing key provider must be active;</li>
 *   <li>the runtime DB login must not own (or be a superuser over) {@code audit_event}: an owner
 *       can lift any privilege or trigger, so the REVOKEs in V1__initial_schema.sql and the WORM triggers do not bind it.
 *       Compose and Helm split the logins (owner/migrator for Flyway only, {@code registerwerk_app}
 *       for the application); {@code registerwerk.audit.allow-owner-runtime-role=true} remains only as
 *       an explicit, logged acknowledgement for an environment that has not split them yet;</li>
 *   <li>a non-owner runtime login must not hold UPDATE/DELETE/TRUNCATE on {@code audit_event} or its
 *       partitions, nor CREATE in schema public (no ack: that is a mis-grant, not an interim state).</li>
 * </ul>
 * The gauge {@code registerwerk_audit_runtime_role_owns_table} (1 = the WORM defences do not bind
 * this login, for either reason) is published in every mode.
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
                .description("1 if the runtime DB role owns/superuses audit_event or can modify it / run DDL "
                        + "(WORM privileges not binding), 0 if not")
                .register(registry);
    }

    @Override
    public void run(ApplicationArguments args) {
        check(productionMode.enabled());
    }

    void check(boolean productionMode) {
        boolean owns = runtimeRoleOwnsAuditTable();
        boolean mutates = !owns && runtimeRoleCanModifyAuditTable();
        ownsGauge.set(owns || mutates ? 1 : 0);
        if (owns) {
            log.warn("AUDIT: {}", ownerMessage());
        } else if (mutates) {
            log.warn("AUDIT: {}", privilegeMessage());
        }
        if (!productionMode) {
            return;
        }
        if (signing.isEmpty()) {
            throw new IllegalStateException("AUDIT: production mode requires an audit signing key provider "
                    + "(registerwerk.audit.signing.provider).");
        }
        if (owns && !allowOwnerRuntimeRole) {
            throw new IllegalStateException("AUDIT: " + ownerMessage() + " Set "
                    + "registerwerk.audit.allow-owner-runtime-role=true only to acknowledge this interim risk.");
        }
        if (mutates) {
            throw new IllegalStateException("AUDIT: " + privilegeMessage());
        }
    }

    private String runtimeLogin() {
        try {
            String user = jdbc.queryForObject("SELECT current_user", String.class);
            return user == null ? "<unknown>" : user;
        } catch (RuntimeException e) {
            return "<unknown>";
        }
    }

    private String ownerMessage() {
        return "the application's runtime database login '" + runtimeLogin() + "' owns audit_event (or is a "
                + "superuser). Failure mode: an owner can ALTER TABLE ... DISABLE TRIGGER, GRANT itself "
                + "UPDATE/DELETE/TRUNCATE or DROP the table, so neither the REVOKEs (V1__initial_schema.sql) nor the WORM triggers "
                + "protect the audit trail. Fix: run the application as the DML-only login (DB_APP_USER / "
                + "DB_APP_PASSWORD, default registerwerk_app, created by postgres-init/roles/ensure-runtime-role.sh) "
                + "and keep the schema-owner login for Flyway only (DB_USER / DB_PASSWORD -> spring.flyway.user / "
                + "spring.flyway.password). Docker Compose does this by default; Helm: postgresql.auth.runtimeUsername "
                + "(runbook: platform/audit-log).";
    }

    private String privilegeMessage() {
        return "the runtime database login '" + runtimeLogin() + "' is not the owner of audit_event but still holds "
                + "UPDATE/DELETE/TRUNCATE on audit_event or one of its partitions, or CREATE on schema public. "
                + "Failure mode: audit rows can be rewritten or removed, or the schema altered, by the application's "
                + "own credentials. Fix: REVOKE UPDATE, DELETE, TRUNCATE ON audit_event (and its partitions) and "
                + "REVOKE CREATE ON SCHEMA public from this login (the last section of V1__initial_schema.sql and "
                + "audit_event_ensure_partitions() do this for registerwerk_app).";
    }

    boolean runtimeRoleCanModifyAuditTable() {
        Boolean can = jdbc.queryForObject("""
                SELECT has_table_privilege(current_user, 'audit_event', 'UPDATE')
                    OR has_table_privilege(current_user, 'audit_event', 'DELETE')
                    OR has_table_privilege(current_user, 'audit_event', 'TRUNCATE')
                    OR has_schema_privilege(current_user, 'public', 'CREATE')
                    OR EXISTS (SELECT 1 FROM pg_inherits i
                               WHERE i.inhparent = 'audit_event'::regclass
                                 AND (has_table_privilege(current_user, i.inhrelid, 'UPDATE')
                                   OR has_table_privilege(current_user, i.inhrelid, 'DELETE')
                                   OR has_table_privilege(current_user, i.inhrelid, 'TRUNCATE')))
                """, Boolean.class);
        return Boolean.TRUE.equals(can);
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
