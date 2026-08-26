-- Registerwerk is an at-least-once Chaincache consumer.  These tables form the local
-- transactional inbox and occurrence ledger which turn redelivery into exactly-once effects.
CREATE TABLE chaincache_event_inbox (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    durability_domain_id  VARCHAR(200) NOT NULL,
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    chain_key             VARCHAR(200) NOT NULL,
    source_sequence       BIGINT NOT NULL CHECK (source_sequence >= 0),
    event_id              VARCHAR(512) NOT NULL,
    schema_version        VARCHAR(20) NOT NULL,
    event_kind            VARCHAR(32) NOT NULL,
    finality              VARCHAR(16),
    payload_hash          VARCHAR(64) NOT NULL,
    raw_event             JSONB NOT NULL,
    processing_state      VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    delivery_count        BIGINT NOT NULL DEFAULT 1,
    first_received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_received_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at          TIMESTAMPTZ,
    last_error            TEXT,
    CONSTRAINT uq_chaincache_inbox_transport
        UNIQUE (durability_domain_id, chain_config_id, event_id),
    CONSTRAINT ck_chaincache_inbox_state
        CHECK (processing_state IN ('RECEIVED', 'PROCESSED', 'FAILED', 'QUARANTINED')),
    CONSTRAINT ck_chaincache_inbox_finality
        CHECK (finality IS NULL OR finality IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED'))
);

CREATE INDEX idx_chaincache_inbox_sequence
    ON chaincache_event_inbox (durability_domain_id, chain_config_id, source_sequence);
CREATE INDEX idx_chaincache_inbox_unprocessed
    ON chaincache_event_inbox (chain_config_id, source_sequence)
    WHERE processing_state <> 'PROCESSED';

CREATE TABLE chain_contract_subscription (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    durability_domain_id  VARCHAR(200) NOT NULL,
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    chain_key             VARCHAR(200) NOT NULL,
    consumer_id           VARCHAR(300) NOT NULL,
    last_sequence         BIGINT CHECK (last_sequence >= 0),
    last_event_id         VARCHAR(512),
    subscription_state    VARCHAR(20) NOT NULL DEFAULT 'LIVE',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chain_contract_subscription
        UNIQUE (durability_domain_id, chain_config_id, consumer_id),
    CONSTRAINT ck_chain_contract_subscription_state
        CHECK (subscription_state IN ('BOOTSTRAP', 'REPLAY', 'LIVE', 'QUARANTINED'))
);

CREATE TABLE chain_event_occurrence (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    durability_domain_id  VARCHAR(200) NOT NULL,
    chain_key             VARCHAR(200) NOT NULL,
    block_number          BIGINT NOT NULL CHECK (block_number >= 0),
    block_hash            VARCHAR(128) NOT NULL,
    transaction_hash      VARCHAR(128) NOT NULL,
    transaction_index     INTEGER,
    log_index             INTEGER NOT NULL CHECK (log_index >= 0),
    contract_address      VARCHAR(128) NOT NULL,
    canonical_tenure      VARCHAR(200) NOT NULL DEFAULT '0',
    logical_event_id      VARCHAR(512) NOT NULL,
    first_event_id        VARCHAR(512) NOT NULL,
    last_event_id         VARCHAR(512) NOT NULL,
    current_finality      VARCHAR(16) NOT NULL,
    canonical             BOOLEAN NOT NULL DEFAULT TRUE,
    occurred_at           TIMESTAMPTZ NOT NULL,
    token_transfer_id     UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chain_event_occurrence UNIQUE
        (chain_config_id, block_hash, transaction_hash, log_index, contract_address, canonical_tenure),
    CONSTRAINT ck_chain_event_occurrence_finality
        CHECK (current_finality IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED')),
    CONSTRAINT ck_chain_event_occurrence_canonical
        CHECK ((canonical AND current_finality <> 'ORPHANED')
            OR (NOT canonical AND current_finality = 'ORPHANED'))
);

CREATE INDEX idx_chain_event_occurrence_logical_event
    ON chain_event_occurrence (durability_domain_id, chain_config_id, logical_event_id);
CREATE INDEX idx_chain_event_occurrence_block
    ON chain_event_occurrence (chain_config_id, block_number, block_hash);

