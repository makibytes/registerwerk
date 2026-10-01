-- Phase 6 K8 (6-21..6-24, DSAR part of 6-30): operator work items raised by the entity lifecycle, and the
-- COMPLETED_PARTIAL outcome of a DSAR erasure.
--
-- entity_task: one OPEN row per (entity, kind, ref). Kinds written by the code today:
--   ISSUER_ASSET_LIVE, SPERRVERMERK_HOLDING, REPO_TRADE_OPEN, LENDING_POSITION_OPEN, TRADE_OPEN,
--   CORPORATE_ACTION_PENDING, PORTFOLIO_MIGRATION_OPEN (offboarding follow-ups, alerting until DONE),
--   KYC_REVIEW_REQUIRED (name/LEI/country change, merger target), CHAIN_REINSTATEMENT_REQUIRED (reactivation).
-- Nothing here deletes or purges anything; retention periods and sweeps are a parked legal decision (T6-10).
CREATE TABLE entity_task (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID         NOT NULL REFERENCES legal_entity(id),
    kind         VARCHAR(48)  NOT NULL,
    ref_id       VARCHAR(128) NOT NULL DEFAULT '',
    detail       TEXT,
    status       VARCHAR(8)   NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   UUID,
    done_at      TIMESTAMPTZ,
    done_by      UUID,
    done_note    TEXT,
    CONSTRAINT chk_entity_task_status CHECK (status IN ('OPEN', 'DONE'))
);

-- A repeated delivery of the same trigger must not stack tasks.
CREATE UNIQUE INDEX uq_entity_task_open ON entity_task (entity_id, kind, ref_id) WHERE status = 'OPEN';
CREATE INDEX idx_entity_task_open ON entity_task (created_at) WHERE status = 'OPEN';
CREATE INDEX idx_entity_task_entity ON entity_task (entity_id, status);

-- DSAR erasure: list what was erased, retained (with legal basis) and not covered; COMPLETED_PARTIAL
-- states that categories of personal data were deliberately not touched by the routine.
ALTER TABLE erasure_request ADD COLUMN resolution_detail TEXT;
-- migration-safety: ack (CHECK constraint is re-created immediately below with a strictly wider value set; no data is dropped or rewritten)
ALTER TABLE erasure_request DROP CONSTRAINT chk_erasure_request_status;
ALTER TABLE erasure_request ADD CONSTRAINT chk_erasure_request_status
    CHECK (status IN ('REQUESTED', 'IN_REVIEW', 'COMPLETED', 'COMPLETED_PARTIAL', 'REJECTED'));
