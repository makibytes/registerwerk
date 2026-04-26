-- V2: Blockchain transaction tracking
-- Stores every on-chain call submitted by the registry, with status and actor info.

CREATE TABLE blockchain_transaction (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tx_hash          VARCHAR(66),                       -- EVM 0x-prefixed hash; NULL while PENDING
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | SUCCESS | FAILED | TIMEOUT
    chain            VARCHAR(30),
    network          VARCHAR(30),
    contract_address VARCHAR(42),
    deployment_id    UUID,
    asset_id         UUID,
    method_name      VARCHAR(100),
    params           JSONB,
    actor_name       VARCHAR(255),                      -- email / username from JWT
    actor_role       VARCHAR(30),
    gas_used         BIGINT,
    block_number     BIGINT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_btx_deployment  ON blockchain_transaction (deployment_id);
CREATE INDEX idx_btx_asset       ON blockchain_transaction (asset_id);
CREATE INDEX idx_btx_actor       ON blockchain_transaction (actor_name);
CREATE INDEX idx_btx_status      ON blockchain_transaction (status) WHERE status = 'PENDING';
CREATE INDEX idx_btx_created     ON blockchain_transaction (created_at DESC);
