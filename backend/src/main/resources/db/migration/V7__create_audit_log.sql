-- Partitioned audit log — append-only, no FK constraints for throughput
CREATE TABLE audit_event (
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    event_type   VARCHAR(100) NOT NULL,
    subject_type VARCHAR(50) NOT NULL,
    subject_id   UUID NOT NULL,
    actor_id     UUID,
    actor_role   VARCHAR(30),
    payload      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (occurred_at);

-- Create initial monthly partitions (current month + next)
CREATE TABLE audit_event_2026_04
    PARTITION OF audit_event
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE audit_event_2026_05
    PARTITION OF audit_event
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

-- Default partition catches any out-of-range inserts
CREATE TABLE audit_event_default
    PARTITION OF audit_event DEFAULT;

-- Indexes on the parent propagate to partitions
CREATE INDEX idx_audit_subject    ON audit_event (subject_type, subject_id);
CREATE INDEX idx_audit_actor      ON audit_event (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_event_type ON audit_event (event_type);
CREATE INDEX idx_audit_payload    ON audit_event USING GIN (payload);
