-- Durable episode claim for Chaincache's typed reorg envelope. The unique key is the boundary
-- that makes replay after commit-before-ACK harmless: mutation and claim share one transaction.
CREATE TABLE chain_reorg_episode (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id         UUID         NOT NULL REFERENCES chain_config(id),
    reorg_id                VARCHAR(128) NOT NULL,
    schema_version          VARCHAR(16)  NOT NULL,
    severity                VARCHAR(32)  NOT NULL,
    common_ancestor_number  BIGINT,
    common_ancestor_hash    VARCHAR(128),
    episode                 JSONB        NOT NULL,
    observed_at             TIMESTAMPTZ  NOT NULL,
    applied_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_chain_reorg_episode UNIQUE (chain_config_id, reorg_id),
    CONSTRAINT chk_chain_reorg_severity CHECK (
        severity IN ('ROUTINE', 'FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY')
    )
);

CREATE INDEX idx_chain_reorg_episode_chain_observed
    ON chain_reorg_episode (chain_config_id, observed_at DESC);
