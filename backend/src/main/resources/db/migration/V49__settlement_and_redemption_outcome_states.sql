-- Wave 0b C7: business state follows the chain OUTCOME, not the transaction submission.
--
-- 1. subscription_order: a mint is only submitted at settlement -> SETTLEMENT_PENDING; it becomes SETTLED once the
--    mint transaction is final and the indexed MINT transfer is FINALIZED. A reverted / replaced mint ->
--    SETTLEMENT_FAILED (retryable by settling again; a timed-out mint is NOT failed - it may still mine). The status
--    column is VARCHAR(20) without a CHECK, so only the failure evidence is new.
ALTER TABLE subscription_order ADD COLUMN settlement_failed_at       TIMESTAMPTZ;
ALTER TABLE subscription_order ADD COLUMN settlement_failure_reason  TEXT;

-- 2. asset: redemption is REDEMPTION_PENDING until every burn it dispatched is final.
-- migration-safety: ack (CHECK constraint is re-created immediately below with one added value; no data is dropped)
ALTER TABLE asset DROP CONSTRAINT chk_asset_status;
ALTER TABLE asset ADD CONSTRAINT chk_asset_status CHECK (
    status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ISSUED','SUSPENDED','REDEEMED','REDEMPTION_PENDING',
               'TRANSFER_PENDING','TRANSFERRED_OUT')
);

-- 3. One row per (asset, deployment, wallet) burn the redemption dispatched. The unique key makes dispatch idempotent
--    (a redelivered redemption event finds the row and never burns twice); the row tracks the burn to its outcome.
CREATE TABLE asset_redemption_burn (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID NOT NULL REFERENCES asset(id),
    deployment_id   UUID NOT NULL,
    wallet_address  VARCHAR(128) NOT NULL,
    amount          NUMERIC(96,18) NOT NULL,
    tx_id           UUID,
    status          VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at    TIMESTAMPTZ,
    CONSTRAINT chk_asset_redemption_burn_status CHECK (status IN ('SUBMITTED', 'CONFIRMED', 'FAILED')),
    CONSTRAINT chk_asset_redemption_burn_amount CHECK (amount > 0),
    CONSTRAINT uq_asset_redemption_burn UNIQUE (asset_id, deployment_id, wallet_address)
);
CREATE INDEX idx_asset_redemption_burn_open ON asset_redemption_burn (asset_id) WHERE status <> 'CONFIRMED';
CREATE INDEX idx_asset_redemption_burn_tx   ON asset_redemption_burn (tx_id) WHERE tx_id IS NOT NULL;
