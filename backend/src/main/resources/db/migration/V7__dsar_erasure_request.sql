-- V7: DSGVO Art. 17 erasure requests.
--
-- The erasure endpoint previously acknowledged a request ("ERASURE_REQUESTED") without
-- storing anything, so a legally-binding request was dropped and no operator could act
-- on it. This table makes each request a persisted operator work item carrying the
-- 30-day response clock (Art. 12(3)) and its resolution. Actual erasure remains a manual,
-- reviewed step because most fields fall under statutory retention (eWpG §15(3), GwG §8).

CREATE TABLE erasure_request (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id            UUID           NOT NULL REFERENCES legal_entity(id),
    requested_by_user_id UUID,
    status               VARCHAR(20)    NOT NULL DEFAULT 'REQUESTED',
    requested_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    due_at               TIMESTAMPTZ    NOT NULL,
    reviewed_by          UUID,
    reviewed_at          TIMESTAMPTZ,
    resolution_note      TEXT,
    CONSTRAINT chk_erasure_request_status CHECK (
        status IN ('REQUESTED','IN_REVIEW','COMPLETED','REJECTED')
    )
);

-- Operator queue: open requests, oldest first.
CREATE INDEX idx_erasure_request_open ON erasure_request (requested_at)
    WHERE status IN ('REQUESTED','IN_REVIEW');

-- Dedup lookup for repeated submissions by the same entity.
CREATE INDEX idx_erasure_request_entity ON erasure_request (entity_id, status);
