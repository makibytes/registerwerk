-- Erc4626AdminService.strikeNav/setDepositCap and Erc7540AdminService.fulfill/cancelExpectedRequest
-- submit a real EVM transaction and then immediately wrote the off-chain mirror state
-- (asset_vault_state, vault_nav_strike, vault_request) as if it were already confirmed — before
-- any receipt existed. This mirrors the exact "optimistic write with no confirmation-gated
-- moment" gap V8 closed for erc3643_identity_registry; see VaultConfirmationListener for the
-- polling/confirmation side of this fix.

-- One row per NAV-strike attempt; tx_hash already existed but was never populated.
ALTER TABLE vault_nav_strike ADD COLUMN chain_config_id UUID REFERENCES chain_config(id);
ALTER TABLE vault_nav_strike ADD COLUMN block_number BIGINT;
ALTER TABLE vault_nav_strike ADD COLUMN confirmed BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_vault_nav_strike_pending
    ON vault_nav_strike (tx_hash)
    WHERE confirmed = false AND tx_hash IS NOT NULL;

-- request_status now deliberately stays PENDING through the confirmation window for both the
-- fulfil and the cancel path — confirmed distinguishes "submitted, awaiting confirmation" from
-- "genuinely never touched". cancelled_tx is new (fulfilled_tx already existed but, like
-- vault_nav_strike.tx_hash, was never populated); the two tx columns are mutually exclusive per
-- row since a request can only ever be fulfilled or cancelled once.
ALTER TABLE vault_request ADD COLUMN chain_config_id UUID REFERENCES chain_config(id);
ALTER TABLE vault_request ADD COLUMN block_number BIGINT;
ALTER TABLE vault_request ADD COLUMN confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE vault_request ADD COLUMN cancelled_tx VARCHAR(80);

CREATE INDEX idx_vault_request_pending_fulfill
    ON vault_request (fulfilled_tx)
    WHERE confirmed = false AND fulfilled_tx IS NOT NULL;

CREATE INDEX idx_vault_request_pending_cancel
    ON vault_request (cancelled_tx)
    WHERE confirmed = false AND cancelled_tx IS NOT NULL;

-- setDepositCap has no history table of its own (unlike NAV strikes), so the in-flight value and
-- its tx are tracked directly on the state row; deposit_cap_tx_hash IS NOT NULL is itself the
-- "pending" signal (cleared once VaultConfirmationListener copies pending_deposit_cap into
-- deposit_cap on confirmation) — no separate boolean needed since, unlike vault_request, this
-- table has nothing else to poll concurrently.
ALTER TABLE asset_vault_state ADD COLUMN pending_deposit_cap NUMERIC(78, 0);
ALTER TABLE asset_vault_state ADD COLUMN deposit_cap_tx_hash VARCHAR(80);
ALTER TABLE asset_vault_state ADD COLUMN deposit_cap_chain_config_id UUID REFERENCES chain_config(id);
ALTER TABLE asset_vault_state ADD COLUMN deposit_cap_block_number BIGINT;

CREATE INDEX idx_asset_vault_state_pending_deposit_cap
    ON asset_vault_state (deposit_cap_tx_hash)
    WHERE deposit_cap_tx_hash IS NOT NULL;
