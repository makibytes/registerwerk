-- T6-17: separate migrator and runtime database logins.
--
--   migrator (owns the schema; used ONLY by Flyway: spring.flyway.user)   e.g. "registerwerk"
--   runtime  (the application datasource)                                  "registerwerk_app"
--
-- V1/V36 already name registerwerk_app and REVOKE UPDATE/DELETE/TRUNCATE on the audit tables from it,
-- but while the application connected as the table owner those REVOKEs were no-ops (an owner can
-- lift any privilege or trigger). The runtime login is created out of band BEFORE Flyway by
-- postgres-init/roles/ensure-runtime-role.sh (compose db-roles service, Helm init container,
-- Terraform/Cloud SQL runbook) as LOGIN, non-superuser, no CREATE. This migration gives it exactly the
-- DML it needs and re-asserts the audit WORM privileges. AuditReadinessCheck fails production startup
-- if the runtime login still owns audit_event.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk_app') THEN
        CREATE ROLE registerwerk_app NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO registerwerk_app;
-- No DDL for the runtime login. PUBLIC has no CREATE on public since PG 15; revoking explicitly also
-- covers clusters upgraded from older majors, where it would otherwise leak through PUBLIC.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM registerwerk_app;

-- V1 granted DML only on the tables that existed then; V2..V56 added more.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO registerwerk_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO registerwerk_app;

-- Objects created by later migrations (run by the migrator) and by definer functions below.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO registerwerk_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO registerwerk_app;

-- The blanket grant above re-granted UPDATE/DELETE on the append-only tables; take them away again.
DO $$
DECLARE t REGCLASS;
BEGIN
    FOR t IN
        SELECT 'audit_event'::regclass
        UNION ALL SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'audit_event'::regclass
        UNION ALL SELECT 'audit_chain_anchor'::regclass
        UNION ALL SELECT 'audit_chain_verification'::regclass
        UNION ALL SELECT 'audit_chain_verification_ack'::regclass
    LOOP
        -- migration-safety: ack (privilege revoke from the runtime login on append-only audit tables; removes no data)
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM registerwerk_app', t);
    END LOOP;
END
$$;

-- Partition maintenance. The runtime login does not own audit_event / token_transfer, so it cannot
-- CREATE ... PARTITION OF them itself. These functions run with the privileges of their owner (the
-- migrator) and a pinned search_path; they only ever CREATE future monthly partitions, bounded, and
-- the generic helper refuses audit tables (those need the WORM guard that audit_event_ensure_partitions adds).
CREATE OR REPLACE FUNCTION audit_event_ensure_partitions(months_ahead INT DEFAULT 6)
    RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 60 THEN
        RAISE EXCEPTION 'audit_event_ensure_partitions: months_ahead must be between 0 and 60';
    END IF;
    FOR i IN 0..months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := 'audit_event_' || to_char(month_start, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
                part_name, month_start, month_end);
            -- migration-safety: ack (privilege revoke from the runtime login on a new append-only partition; removes no data)
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM registerwerk_app', part_name);
            -- migration-safety: ack (guard trigger against statement-level removal on a new partition; removes no data)
            EXECUTE format('CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON %I '
                           -- migration-safety: ack (guard trigger against statement-level removal on a new partition; removes no data)
                           'FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate()', part_name);
        END IF;
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION rw_ensure_monthly_partitions(
    p_table        regclass,
    p_time_column  text,
    p_months_ahead int DEFAULT 6
) RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    schema_name TEXT;
    bare_name   TEXT;
    actual_col  TEXT;
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    SELECT n.nspname, c.relname INTO schema_name, bare_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = p_table;

    IF bare_name LIKE 'audit\_%' THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: audit tables are managed by audit_event_ensure_partitions()';
    END IF;
    IF p_months_ahead IS NULL OR p_months_ahead < 0 OR p_months_ahead > 60 THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: p_months_ahead must be between 0 and 60';
    END IF;

    SELECT a.attname INTO actual_col
    FROM pg_partitioned_table pt
    JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
    WHERE pt.partrelid = p_table;

    IF actual_col IS NULL THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: % is not a partitioned table', p_table;
    ELSIF actual_col IS DISTINCT FROM p_time_column THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: % is partitioned on column % not %',
            p_table, actual_col, p_time_column;
    END IF;

    FOR i IN 0..p_months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := bare_name || '_' || to_char(month_start, 'YYYY_MM');
        BEGIN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                schema_name, part_name, p_table::text, month_start, month_end
            );
        EXCEPTION WHEN duplicate_table THEN
            NULL;
        END;
    END LOOP;
END;
$$;

-- Detaching/retiring partitions stays a migrator (operator) action: the application never calls it.
REVOKE EXECUTE ON FUNCTION rw_retire_partitions(regclass, text, int, text) FROM PUBLIC;
