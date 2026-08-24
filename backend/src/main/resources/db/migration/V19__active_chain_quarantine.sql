-- Current fail-closed state for a chain whose canonical history cannot safely be mutated.
-- chain_reorg_episode remains the immutable incident journal; this row is the operational
-- snapshot consulted by FinalityGate and ingestion. Resolution fields are reserved for the
-- explicit, audited operator workflow rather than making quarantine self-clearing.
CREATE TABLE chain_quarantine (
    chain_config_id  UUID         PRIMARY KEY REFERENCES chain_config(id),
    reorg_id         VARCHAR(128) NOT NULL,
    severity         VARCHAR(32)  NOT NULL,
    observed_at      TIMESTAMPTZ  NOT NULL,
    activated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    resolved_at      TIMESTAMPTZ,
    CONSTRAINT fk_chain_quarantine_episode
        FOREIGN KEY (chain_config_id, reorg_id)
        REFERENCES chain_reorg_episode(chain_config_id, reorg_id),
    CONSTRAINT chk_chain_quarantine_severity
        CHECK (severity IN ('FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY')),
    CONSTRAINT chk_chain_quarantine_resolution
        CHECK ((active AND resolved_at IS NULL) OR (NOT active AND resolved_at IS NOT NULL))
);

CREATE INDEX idx_chain_quarantine_active
    ON chain_quarantine (chain_config_id) WHERE active = TRUE;
