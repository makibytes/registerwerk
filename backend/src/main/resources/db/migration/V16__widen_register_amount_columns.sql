-- Phase 4 (P4B-6): raw uint256 token amounts overflowed NUMERIC(38,18).
--
-- The subgraph stores Transfer.value in base units and the indexer writes it unscaled, so a single
-- mint of 100 tokens at 18 decimals (1e20 base units) exceeded the 20 integer digits of
-- NUMERIC(38,18): the INSERT failed inside the sync transaction and the chain silently stopped
-- indexing. Every column that carries or sums those amounts is widened to NUMERIC(96,18): a full
-- uint256 is 78 digits, plus the unchanged scale of 18. Scale is unchanged, so PostgreSQL does not
-- rewrite the tables for the precision increase. Amounts are deliberately NOT rescaled here (T4-01
-- is parked): registers keep raw base units.
--
-- Obstacles handled: the V13 history trigger names nominal_amount in its column list and WHEN
-- clause (PostgreSQL refuses ALTER TYPE on such a column), and chain_drift_event.delta is a stored
-- generated column over the two balance columns.

DROP TRIGGER trg_asset_holder_position_update ON asset_holder;

ALTER TABLE asset_holder ALTER COLUMN nominal_amount TYPE NUMERIC(96,18);

-- Recreated exactly as in V13.
CREATE TRIGGER trg_asset_holder_position_update
    AFTER UPDATE OF nominal_amount, removed_at, holder_kind ON asset_holder
    FOR EACH ROW
    WHEN (OLD.nominal_amount IS DISTINCT FROM NEW.nominal_amount
          OR OLD.removed_at IS DISTINCT FROM NEW.removed_at
          OR OLD.holder_kind IS DISTINCT FROM NEW.holder_kind)
    EXECUTE FUNCTION capture_asset_holder_position();

ALTER TABLE asset_holder_position_history ALTER COLUMN nominal_amount TYPE NUMERIC(96,18);

-- RANGE-partitioned: the type change on the parent propagates to every partition.
ALTER TABLE token_transfer ALTER COLUMN amount TYPE NUMERIC(96,18);

-- migration-safety: ack (generated column is re-added with the identical expression right below; it is recomputed from the two base columns, no data is lost)
ALTER TABLE chain_drift_event DROP COLUMN delta;
ALTER TABLE chain_drift_event
    ALTER COLUMN db_balance      TYPE NUMERIC(96,18),
    ALTER COLUMN onchain_balance TYPE NUMERIC(96,18);
ALTER TABLE chain_drift_event
    ADD COLUMN delta NUMERIC(96,18) GENERATED ALWAYS AS (onchain_balance - db_balance) STORED;

-- Record-date snapshots, entitlements and register statements are derived from the balances above.
ALTER TABLE corporate_action_entry
    ALTER COLUMN nominal_at_record  TYPE NUMERIC(96,18),
    ALTER COLUMN entitlement_amount TYPE NUMERIC(96,18);
ALTER TABLE corporate_action
    ALTER COLUMN total_amount      TYPE NUMERIC(96,18),
    ALTER COLUMN rounding_residual TYPE NUMERIC(96,18);
ALTER TABLE register_statement ALTER COLUMN nominal_amount TYPE NUMERIC(96,18);
