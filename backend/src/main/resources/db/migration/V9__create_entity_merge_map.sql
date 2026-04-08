-- Records M&A events: dissolved source entity -> surviving target entity
CREATE TABLE entity_merge_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    merge_type       VARCHAR(20) NOT NULL DEFAULT 'ABSORPTION',
    effective_date   DATE NOT NULL,
    notes            TEXT,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by      UUID,
    CONSTRAINT chk_merge_type CHECK (merge_type IN ('ABSORPTION','CONSOLIDATION')),
    CONSTRAINT chk_no_self_merge CHECK (source_entity_id != target_entity_id)
);

CREATE INDEX idx_merge_source ON entity_merge_record (source_entity_id);
CREATE INDEX idx_merge_target ON entity_merge_record (target_entity_id);
