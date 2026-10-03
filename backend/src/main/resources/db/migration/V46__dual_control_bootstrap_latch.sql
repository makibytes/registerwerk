-- Wave 0a C3: the "fewer than two enrolled administrators" bootstrap exception of the four-eyes rule is a
-- ONE-WAY door. Without a persisted fact, disabling a colleague (one step-up) dropped the count of enabled,
-- TOTP-enrolled REGISTRY_ADMINs back below two and re-opened the single-step-up path (sock-puppet admin).

CREATE TABLE dual_control_bootstrap (
    id           BOOLEAN     NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    completed_at TIMESTAMPTZ
);
INSERT INTO dual_control_bootstrap (id) VALUES (TRUE);

COMMENT ON TABLE dual_control_bootstrap IS
    'Singleton, write-once. completed_at is set the first time two enabled, TOTP-enrolled REGISTRY_ADMINs '
    'existed at once; from then on DualControlService.requireIfNotBootstrap never falls back to a single step-up.';

-- completed_at can be set once and never cleared; the row cannot be deleted or the table truncated.
CREATE FUNCTION dual_control_bootstrap_one_way() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'dual_control_bootstrap is one-way: the row cannot be removed. Operation: %', TG_OP;
    END IF;
    IF OLD.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'dual_control_bootstrap is one-way: the bootstrap exception cannot be re-opened. Operation: %', TG_OP;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_dual_control_bootstrap_one_way
    BEFORE UPDATE OR DELETE ON dual_control_bootstrap
    FOR EACH ROW EXECUTE FUNCTION dual_control_bootstrap_one_way();
CREATE TRIGGER trg_dual_control_bootstrap_no_truncate
    BEFORE TRUNCATE ON dual_control_bootstrap
    FOR EACH STATEMENT EXECUTE FUNCTION dual_control_bootstrap_one_way();

-- Closes the latch when two enabled, enrolled REGISTRY_ADMINs exist; returns whether it is closed.
-- The single definition of "enrolled administrator" for both the triggers and the application.
CREATE FUNCTION dual_control_bootstrap_observe() RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    closed BOOLEAN;
BEGIN
    SELECT completed_at IS NOT NULL INTO closed FROM dual_control_bootstrap WHERE id;
    IF closed THEN
        RETURN TRUE;
    END IF;
    IF (SELECT count(*) FROM app_user u
         WHERE u.enabled AND u.totp_enabled
           AND EXISTS (SELECT 1 FROM app_user_role r WHERE r.app_user_id = u.id AND r.role = 'REGISTRY_ADMIN')) >= 2 THEN
        UPDATE dual_control_bootstrap SET completed_at = now() WHERE id AND completed_at IS NULL;
        RETURN TRUE;
    END IF;
    RETURN FALSE;
END;
$$;

-- The latch must see the moment two such administrators exist, however they came to be (the operator
-- API, an IdP provisioning path, a seeder, SQL), not only when a privileged action next asks.
CREATE FUNCTION dual_control_bootstrap_observe_trg() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM dual_control_bootstrap_observe();
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_app_user_dual_control_bootstrap
    AFTER INSERT OR UPDATE OF enabled, totp_enabled ON app_user
    FOR EACH ROW WHEN (NEW.enabled AND NEW.totp_enabled)
    EXECUTE FUNCTION dual_control_bootstrap_observe_trg();
CREATE TRIGGER trg_app_user_role_dual_control_bootstrap
    AFTER INSERT ON app_user_role
    FOR EACH ROW WHEN (NEW.role = 'REGISTRY_ADMIN')
    EXECUTE FUNCTION dual_control_bootstrap_observe_trg();

-- Installations that already have two such administrators are latched by this migration.
SELECT dual_control_bootstrap_observe();
