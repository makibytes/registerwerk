-- H5 (Sperrvermerk on-chain freeze reach): one row per (block, deployment, wallet) that records whether the
-- legal block actually reached the chain.
--
-- Until now SperrvermerkOnchainSyncListener submitted a freeze (or threw and logged) and forgot: the outcome of a
-- submitted-but-reverted freeze was never read, holder_block.on_chain_freeze_tx_hash was never written, and a
-- standard or chain with no automated freeze path (SPL, Stellar, Starknet, Canton, confidential ERC-20) left the
-- register saying "blocked" while nothing on-chain was. The register-level block stays authoritative; this table
-- only tracks how far the chain follows it.
--
--   SUBMITTED             freeze transaction handed to the durable outbox, outcome not final yet
--   CONFIRMED             the freeze transaction is final and SUCCESS (the nightly job also reads isFrozen back)
--   FAILED                the freeze could not be submitted, reverted, or was replaced: the wallet may still move
--   UNSUPPORTED_ON_CHAIN  no automated, outcome-tracked freeze exists for this standard/chain: manual action needed
--   RELEASE_SUBMITTED / RELEASED / RELEASE_FAILED
--                         the same for the unfreeze that follows a lifted block (RELEASED also covers "nothing to
--                         release" and "another block still covers the wallet, the freeze stays")
CREATE TABLE holder_block_freeze (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    holder_block_id  UUID         NOT NULL REFERENCES holder_block(id),
    deployment_id    UUID         NOT NULL REFERENCES asset_deployment(id),
    wallet_address   TEXT         NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    -- blockchain_transaction.id of the latest freeze/unfreeze transaction (that table is partitioned: no FK)
    tx_id            UUID,
    tx_hash          VARCHAR(128),
    detail           TEXT,
    attempts         INT          NOT NULL DEFAULT 0,
    -- how often the nightly read-back found the wallet NOT frozen although the freeze was CONFIRMED
    drift_count      INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at     TIMESTAMPTZ,
    verified_at      TIMESTAMPTZ,
    CONSTRAINT uq_holder_block_freeze UNIQUE (holder_block_id, deployment_id, wallet_address)
);

CREATE INDEX idx_holder_block_freeze_tx     ON holder_block_freeze (tx_id) WHERE tx_id IS NOT NULL;
CREATE INDEX idx_holder_block_freeze_open   ON holder_block_freeze (status)
    WHERE status IN ('SUBMITTED', 'FAILED', 'UNSUPPORTED_ON_CHAIN', 'RELEASE_SUBMITTED', 'RELEASE_FAILED');
CREATE INDEX idx_holder_block_freeze_deployment ON holder_block_freeze (deployment_id);
