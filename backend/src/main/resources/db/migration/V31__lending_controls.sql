-- Phase 5 K6: lending market binding verification (5B-09), collateral balance guard and
-- reconciliation tasks (5B-10), stale position reads (5B-11).

-- 5B-09: a market is only offered to customers while its on-chain binding (factory provenance,
-- collateral token == the asset's confirmed deployment, loan token == the enabled rail's token)
-- verifies. Legacy rows stay visible until the admin re-verification flags a mismatch.
ALTER TABLE lending_market
    ADD COLUMN binding_verified     BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN binding_verified_at  TIMESTAMPTZ,
    ADD COLUMN binding_failure      VARCHAR(500),
    ADD COLUMN code_hash            VARCHAR(66),
    -- 5B-11: true when surplusOf(address) exists on the deployed market; a failed read on such a
    -- market is an error, not a zero. Legacy rows default to true (fail closed) until re-probed.
    ADD COLUMN surplus_supported    BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 5B-10: collateral token balance of the market is below its recorded totalCollateral.
    ADD COLUMN collateral_shortfall BOOLEAN     NOT NULL DEFAULT FALSE;

-- 5B-11: a failed on-chain read keeps the previous values and marks the row stale.
ALTER TABLE lending_position
    ADD COLUMN sync_stale      BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN last_sync_error VARCHAR(500);

CREATE TABLE lending_reconciliation_task (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id           UUID         NOT NULL REFERENCES lending_market(id),
    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    source              VARCHAR(30)  NOT NULL,
    shortfall           NUMERIC(78,0) NOT NULL DEFAULT 0,
    token_admin_method  VARCHAR(60),
    detail              VARCHAR(1000),
    detected_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         UUID,
    reconcile_tx_hash   VARCHAR(66),
    borrower_wallet     VARCHAR(66),
    attributed_amount   NUMERIC(78,0),
    forced_transfer_ref VARCHAR(66),
    legal_basis         VARCHAR(500),
    CONSTRAINT chk_lending_recon_status CHECK (status IN ('OPEN','SUBMITTED','RESOLVED')),
    CONSTRAINT chk_lending_recon_source CHECK (source IN ('BALANCE_GUARD','FORCED_TRANSFER_EVENT'))
);
-- at most one unresolved task per market: detections upsert into it
CREATE UNIQUE INDEX uq_lending_recon_open_market
    ON lending_reconciliation_task (market_id) WHERE status IN ('OPEN','SUBMITTED');
CREATE INDEX idx_lending_recon_status ON lending_reconciliation_task (status, detected_at);
