-- The finality module's own ledger of blocks observed while still "unsettled" (PROVISIONAL or
-- SAFE) — see BlockFinalityFeed's javadoc for exactly what is and isn't tracked here. Owned by
-- the finality module, fed by the indexer's ReorgGuard, so "what level did block N on chain C
-- reach, and was it ever retracted" is answerable without importing the indexer module or
-- scanning token_transfer.

CREATE TABLE block_finality (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id   UUID         NOT NULL REFERENCES chain_config(id),
    block_number      BIGINT       NOT NULL,
    block_hash        VARCHAR(128),
    finality_level    VARCHAR(16)  NOT NULL,
    observed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_block_finality_level CHECK (finality_level IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED')),
    CONSTRAINT uq_block_finality UNIQUE (chain_config_id, block_number)
);

-- The hot query: bulk-marking every row at/after a fork block ORPHANED, and looking up one
-- specific (chain, block).
CREATE INDEX idx_block_finality_chain_block ON block_finality (chain_config_id, block_number);
