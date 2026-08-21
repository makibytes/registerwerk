-- The effect journal: one row per state change caused by an on-chain event, written in the same
-- transaction as the change it describes. Descriptor-primary — the dispatcher routes on
-- module_name + effect_type + entity_type + entity_id and never interprets before_state/
-- after_state; the owning module decides how to undo (RECOMPUTE re-derives, INVERSE_FLIP restores
-- a prior column value, IRREVERSIBLE only escalates). See finality.api.ChainEffectRecorder's
-- javadoc for the full rationale.

CREATE TABLE chain_effect (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id     UUID          NOT NULL REFERENCES chain_config(id),
    block_number        BIGINT        NOT NULL,
    block_hash          VARCHAR(128),
    tx_hash             VARCHAR(128),
    log_index           INT,
    source_event_key    VARCHAR(300)  NOT NULL,
    module_name         VARCHAR(40)   NOT NULL,
    effect_type         VARCHAR(60)   NOT NULL,
    entity_type         VARCHAR(60)   NOT NULL,
    entity_id           UUID          NOT NULL,
    category            VARCHAR(20)   NOT NULL,
    before_state        JSONB,
    after_state         JSONB,
    audit_event_id      UUID,
    correlation_id      UUID,
    status              VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    attempt_count        INT          NOT NULL DEFAULT 0,
    last_error          TEXT,
    recorded_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    compensated_at      TIMESTAMPTZ,
    acknowledged_by     UUID,
    acknowledged_at     TIMESTAMPTZ,
    CONSTRAINT chk_chain_effect_category CHECK (category IN ('RECOMPUTE', 'INVERSE_FLIP', 'IRREVERSIBLE')),
    CONSTRAINT chk_chain_effect_status CHECK (status IN (
        'ACTIVE', 'SETTLED', 'COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED', 'IRREVERSIBLE_ESCALATED')),
    -- Idempotent recording: two dispatches of the same source event for the same entity collapse
    -- into one row (ON CONFLICT DO NOTHING on the write side, see ChainEffectRecorderImpl).
    CONSTRAINT uq_chain_effect_source UNIQUE (source_event_key, effect_type, entity_id)
);

-- The hot query: "which effects are still unresolved for entity X" (the FinalityGate freeze check,
-- a later phase) and "find this entity's effects for a given module".
CREATE INDEX idx_chain_effect_entity ON chain_effect (module_name, entity_type, entity_id);

-- The retry job's query: everything still ACTIVE or COMPENSATION_FAILED, oldest first.
CREATE INDEX idx_chain_effect_status ON chain_effect (status, recorded_at);
