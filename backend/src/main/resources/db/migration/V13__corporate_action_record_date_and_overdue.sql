-- K3b (Phase 3): corporate-action engine.
--
-- T3-06: entitlements are fixed as of the END of the record date. Chain-deployed assets net the
-- indexed FINALIZED transfers up to that instant; off-chain register rows have no transfer
-- history, so every change to a holder's position is now captured here by a trigger.
-- T3-05: settled actions clear OVERDUE/MISSED/DEFAULTED signals; per-holder entitlements are
-- rounded to the currency's minor unit and the difference is kept on the action.
-- T3-02: SETTLED actions with unresolved nominee-pool (HELD_LOOK_THROUGH) entitlements are not
-- closed; the flag makes them countable for the gauge and visible in the operator UI.

ALTER TABLE corporate_action
    ADD COLUMN rounding_residual NUMERIC(38,18),
    ADD COLUMN held_outstanding  BOOLEAN NOT NULL DEFAULT false;

-- ── Position history (T3-06) ────────────────────────────────────────────────
-- One row per state of an asset_holder row; the as-of position is the latest row with
-- valid_from < cut-off, counted only when removed_at is null or after the cut-off.
-- No FK to asset_holder: the history is append-only evidence and must outlive any row cleanup.
CREATE TABLE asset_holder_position_history (
    id             BIGSERIAL PRIMARY KEY,
    holder_id      UUID           NOT NULL,
    asset_id       UUID           NOT NULL,
    investor_id    UUID           NOT NULL,
    wallet_address VARCHAR(66)    NOT NULL,
    holder_kind    VARCHAR(20)    NOT NULL,
    nominal_amount NUMERIC(38,18) NOT NULL,
    removed_at     TIMESTAMPTZ,
    valid_from     TIMESTAMPTZ    NOT NULL,
    -- true for the one row per holder written by this migration: its values are the state at
    -- migration time, back-dated to created_at. Record dates before recorded_at may therefore use
    -- a later value than the true one (only relevant for actions still pending at deploy time).
    backfilled     BOOLEAN        NOT NULL DEFAULT false,
    recorded_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_holder_position_history_asof
    ON asset_holder_position_history (asset_id, holder_id, valid_from DESC);

CREATE FUNCTION capture_asset_holder_position() RETURNS trigger AS $$
BEGIN
    INSERT INTO asset_holder_position_history
        (holder_id, asset_id, investor_id, wallet_address, holder_kind, nominal_amount, removed_at, valid_from)
    VALUES
        (NEW.id, NEW.asset_id, NEW.investor_id, NEW.wallet_address, NEW.holder_kind, NEW.nominal_amount,
         NEW.removed_at, clock_timestamp());
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_holder_position_insert
    AFTER INSERT ON asset_holder
    FOR EACH ROW EXECUTE FUNCTION capture_asset_holder_position();

-- Hibernate writes every column on update, so the column list alone would fire on unrelated
-- edits; the WHEN clause keeps the history to real position changes.
CREATE TRIGGER trg_asset_holder_position_update
    AFTER UPDATE OF nominal_amount, removed_at, holder_kind ON asset_holder
    FOR EACH ROW
    WHEN (OLD.nominal_amount IS DISTINCT FROM NEW.nominal_amount
          OR OLD.removed_at IS DISTINCT FROM NEW.removed_at
          OR OLD.holder_kind IS DISTINCT FROM NEW.holder_kind)
    EXECUTE FUNCTION capture_asset_holder_position();

INSERT INTO asset_holder_position_history
    (holder_id, asset_id, investor_id, wallet_address, holder_kind, nominal_amount, removed_at, valid_from, backfilled)
SELECT id, asset_id, investor_id, wallet_address, holder_kind, nominal_amount, removed_at, created_at, true
FROM asset_holder;

-- ── Status backfill (T3-05, SRE) ────────────────────────────────────────────
-- Bonds flagged DEFAULTED although their redemption did settle later.
UPDATE asset_bond_terms t SET bond_status = 'REDEEMED', updated_at = now()
WHERE t.bond_status = 'DEFAULTED'
  AND EXISTS (SELECT 1 FROM corporate_action ca
              WHERE ca.asset_id = t.asset_id AND ca.action_type = 'REDEMPTION'
                AND ca.status IN ('SETTLED', 'CLOSED'));

-- A settled CALL never moved the bond on, so the maturity job would later have raised a second
-- redemption for it.
UPDATE asset_bond_terms t SET bond_status = 'CALLED', updated_at = now()
WHERE t.bond_status IN ('ACTIVE', 'MATURED', 'DEFAULTED')
  AND EXISTS (SELECT 1 FROM corporate_action ca
              WHERE ca.asset_id = t.asset_id AND ca.action_type = 'CALL'
                AND ca.status IN ('SETTLED', 'CLOSED'));

-- Coupons flagged MISSED although their action settled.
UPDATE asset_coupon_payment p
SET coupon_status = 'PAID',
    paid_date = COALESCE(p.paid_date, (SELECT MAX(ca.settled_at)::date FROM corporate_action ca
                                      WHERE ca.coupon_payment_id = p.id AND ca.status IN ('SETTLED', 'CLOSED'))),
    updated_at = now()
WHERE p.coupon_status = 'MISSED'
  AND EXISTS (SELECT 1 FROM corporate_action ca
              WHERE ca.coupon_payment_id = p.id AND ca.status IN ('SETTLED', 'CLOSED'));

-- What is still DEFAULTED/MISSED was flagged the day after the payment date, with no grace
-- period. Inside the grace period it is only OVERDUE (operator-visible, not a public default).
UPDATE asset_bond_terms t SET bond_status = 'OVERDUE', updated_at = now()
WHERE t.bond_status = 'DEFAULTED'
  AND EXISTS (SELECT 1 FROM corporate_action ca
              WHERE ca.asset_id = t.asset_id AND ca.action_type = 'REDEMPTION'
                AND ca.status NOT IN ('SETTLED', 'CLOSED', 'CANCELLED')
                AND ca.payment_date + t.principal_grace_days >= current_date);

UPDATE asset_coupon_payment p SET coupon_status = 'OVERDUE', updated_at = now()
FROM asset_bond_terms t
WHERE p.asset_id = t.asset_id
  AND p.coupon_status = 'MISSED'
  AND p.scheduled_date + t.interest_grace_days >= current_date;
