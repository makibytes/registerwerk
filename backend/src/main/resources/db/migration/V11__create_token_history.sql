-- Full token transfer history — every on-chain movement from mint to burn,
-- deduplication enforced via (chain_config_id, tx_hash, log_index).

CREATE TABLE token_transfer (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID        REFERENCES asset(id),
    deployment_id    UUID        REFERENCES asset_deployment(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    contract_address VARCHAR(66) NOT NULL,
    from_address     VARCHAR(66) NOT NULL,   -- address(0) on mint
    to_address       VARCHAR(66) NOT NULL,   -- address(0) on burn
    token_id         NUMERIC,               -- ERC-721 / ERC-1155 token id
    amount           NUMERIC(38, 18),       -- ERC-20 / ERC-1155 amount (NULL for ERC-721)
    event_type       VARCHAR(10) NOT NULL,   -- 'MINT' | 'TRANSFER' | 'BURN'
    tx_hash          VARCHAR(66) NOT NULL,
    block_number     BIGINT      NOT NULL,
    log_index        INT,                    -- NULL for Solana
    slot             BIGINT,                 -- Solana slot (NULL for EVM)
    occurred_at      TIMESTAMPTZ NOT NULL,
    explorer_tx_url  VARCHAR(600),           -- direct link to the tx on the block explorer
    raw_data         JSONB,                  -- full event/log for debugging

    CONSTRAINT chk_event_type CHECK (event_type IN ('MINT', 'TRANSFER', 'BURN')),
    -- Deduplication: same tx + same log position cannot be inserted twice
    CONSTRAINT uq_transfer_evm    UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, log_index),
    CONSTRAINT uq_transfer_solana UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, slot)
);

CREATE INDEX idx_transfer_asset       ON token_transfer (asset_id);
CREATE INDEX idx_transfer_deployment  ON token_transfer (deployment_id);
CREATE INDEX idx_transfer_chain       ON token_transfer (chain_config_id, block_number DESC);
CREATE INDEX idx_transfer_contract    ON token_transfer (contract_address, occurred_at DESC);
CREATE INDEX idx_transfer_from        ON token_transfer (from_address);
CREATE INDEX idx_transfer_to          ON token_transfer (to_address);
CREATE INDEX idx_transfer_event_type  ON token_transfer (event_type);
