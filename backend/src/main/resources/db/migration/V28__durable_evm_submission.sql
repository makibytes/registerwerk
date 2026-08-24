-- Persist exact signed bytes before RPC broadcast. Retrying a prepared row can only resubmit the
-- same sender/nonce/hash, closing the post-broadcast database-failure duplication window.
CREATE TABLE evm_signed_submission (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id  UUID          NOT NULL REFERENCES chain_config(id),
    chain_id          NUMERIC(78,0) NOT NULL,
    sender_address    VARCHAR(42)   NOT NULL,
    nonce             NUMERIC(78,0) NOT NULL,
    tx_hash           VARCHAR(66)   NOT NULL UNIQUE,
    signed_payload    TEXT          NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PREPARED',
    chain_name        VARCHAR(30)   NOT NULL,
    network           VARCHAR(30)   NOT NULL,
    contract_address  VARCHAR(42)   NOT NULL,
    method_name       VARCHAR(100)  NOT NULL,
    params             JSONB,
    actor_name        VARCHAR(255),
    actor_role        VARCHAR(30),
    attempt_count     INTEGER       NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    broadcast_at      TIMESTAMPTZ,
    CONSTRAINT uq_evm_signed_submission_nonce UNIQUE (chain_id, sender_address, nonce),
    CONSTRAINT chk_evm_signed_submission_status CHECK (status IN ('PREPARED', 'BROADCAST')),
    CONSTRAINT chk_evm_signed_submission_payload CHECK (signed_payload ~ '^0x[0-9a-fA-F]+$'),
    CONSTRAINT chk_evm_signed_submission_hash CHECK (tx_hash ~ '^0x[0-9a-fA-F]{64}$'),
    CONSTRAINT chk_evm_signed_submission_broadcast CHECK (
        (status = 'PREPARED' AND broadcast_at IS NULL)
        OR (status = 'BROADCAST' AND broadcast_at IS NOT NULL)
    )
);

CREATE INDEX idx_evm_signed_submission_pending
    ON evm_signed_submission (created_at) WHERE status = 'PREPARED';

-- A deposit-cap intent is one inseparable pair. Partial legacy rows fail migration instead of
-- being silently repaired because either value could describe an already-broadcast transaction.
ALTER TABLE asset_vault_state
    ADD CONSTRAINT chk_deposit_cap_pending_pair CHECK (
        (pending_deposit_cap IS NULL AND deposit_cap_tx_hash IS NULL)
        OR (pending_deposit_cap IS NOT NULL AND deposit_cap_tx_hash IS NOT NULL)
    );
