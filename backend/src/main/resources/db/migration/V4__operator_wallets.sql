-- V4: Operator wallet management
-- Stores encrypted keystore metadata for wallets used to sign on-chain operations.
-- Private key material lives in files on the docker volume; this table holds metadata only.

CREATE TABLE operator_wallet (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(120) NOT NULL UNIQUE,
    type            VARCHAR(10)  NOT NULL,             -- 'EVM' | 'SOLANA'
    address         VARCHAR(64)  NOT NULL,             -- 0x-checksummed or base58
    keystore_path   VARCHAR(255) NOT NULL,             -- path on volume, relative to storage root
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    CONSTRAINT chk_wallet_type CHECK (type IN ('EVM','SOLANA')),
    CONSTRAINT uq_wallet_addr  UNIQUE (type, address)
);

-- Per-chain default: exactly one wallet is the active signer for each chain.
-- PK on chain_config_id enforces the "exactly one default" rule.
CREATE TABLE wallet_chain_default (
    chain_config_id UUID        PRIMARY KEY REFERENCES chain_config(id) ON DELETE CASCADE,
    wallet_id       UUID        NOT NULL REFERENCES operator_wallet(id) ON DELETE RESTRICT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID
);

CREATE INDEX idx_wallet_chain_default_wallet ON wallet_chain_default (wallet_id);
