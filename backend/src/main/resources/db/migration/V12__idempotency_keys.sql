-- Idempotency-Key support (F-BLOCKER-2, integration surface): previously there was no
-- Idempotency-Key header contract on the mutating REST surface — a middleware team integrating
-- over an unreliable link had no safe retry story (a retried POST could double-execute).

CREATE TABLE idempotency_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_record_entity_key UNIQUE (entity_id, idempotency_key),
    CONSTRAINT chk_idempotency_record_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- Input for the cleanup job — old records (of either status; a crashed IN_PROGRESS row must not
-- block that key forever) are purged after the retention window.
CREATE INDEX idx_idempotency_record_created_at ON idempotency_record (created_at);
