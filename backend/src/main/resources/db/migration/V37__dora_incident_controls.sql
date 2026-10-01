-- V37: DORA incident controls (Phase 6 / K5, finding 6-13).
--  * awareness_at: the anchor for the 24 h / 1 month clocks (operator-entered, immutable)
--  * classification evidence + downgrade marker + intermediate (72 h) report tracking
--  * ict_incident_report: append-only authority submissions (initial / intermediate / final)
--  * guard trigger: awareness and the first-submission columns are write-once
--  * ict_incident_alert: one row per (incident, breach type) so overdue alerts are not re-sent every run

ALTER TABLE ict_incident
    ADD COLUMN awareness_at                  TIMESTAMPTZ,
    ADD COLUMN classification_reason         TEXT,
    ADD COLUMN classification_criteria       JSONB,
    ADD COLUMN classified_by                 UUID,
    ADD COLUMN classification_pending        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN intermediate_report_deadline  TIMESTAMPTZ,
    ADD COLUMN intermediate_reported_at      TIMESTAMPTZ,
    ADD COLUMN downgraded_at                 TIMESTAMPTZ,
    ADD COLUMN downgrade_reason              TEXT;

UPDATE ict_incident SET awareness_at = detected_at WHERE awareness_at IS NULL;
ALTER TABLE ict_incident ALTER COLUMN awareness_at SET NOT NULL;

-- Deadlines are computed for every incident (informational below MAJOR; monitoring keys on severity).
UPDATE ict_incident SET initial_report_deadline = awareness_at + INTERVAL '24 hours'
 WHERE initial_report_deadline IS NULL;
UPDATE ict_incident SET final_report_deadline = awareness_at + INTERVAL '30 days'
 WHERE final_report_deadline IS NULL;
-- A MAJOR incident that already has an initial report now has the 72 h intermediate clock running.
UPDATE ict_incident SET intermediate_report_deadline = initial_reported_at + INTERVAL '72 hours'
 WHERE severity = 'MAJOR' AND initial_reported_at IS NOT NULL;
-- MAJOR rows created before this change were classified at entry.
UPDATE ict_incident SET classified_at = detected_at
 WHERE severity = 'MAJOR' AND classified_at IS NULL;
UPDATE ict_incident SET classification_deadline = classified_at + INTERVAL '4 hours'
 WHERE severity = 'MAJOR' AND classification_deadline IS NULL;

CREATE TABLE ict_incident_report (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id   UUID NOT NULL REFERENCES ict_incident (id),
    report_type   VARCHAR(20) NOT NULL CHECK (report_type IN ('INITIAL', 'INTERMEDIATE', 'FINAL')),
    submitted_at  TIMESTAMPTZ NOT NULL,
    authority_ref TEXT,
    submitted_by  UUID,
    note          TEXT,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ict_incident_report_incident ON ict_incident_report (incident_id, submitted_at);

-- Backfill one report row per legacy current-state column. The single legacy authority_ref was
-- overwritten by every call, so it is attributed to the most recent report only.
INSERT INTO ict_incident_report (incident_id, report_type, submitted_at, authority_ref, submitted_by, note)
SELECT id, 'INITIAL', initial_reported_at,
       CASE WHEN final_reported_at IS NULL THEN authority_ref END, reported_by,
       'legacy backfill (V37): pre-existing initial_reported_at'
  FROM ict_incident WHERE initial_reported_at IS NOT NULL;
INSERT INTO ict_incident_report (incident_id, report_type, submitted_at, authority_ref, submitted_by, note)
SELECT id, 'FINAL', final_reported_at, authority_ref, reported_by,
       'legacy backfill (V37): pre-existing final_reported_at'
  FROM ict_incident WHERE final_reported_at IS NOT NULL;

CREATE OR REPLACE FUNCTION ict_incident_report_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ict_incident_report is append-only (% refused)', TG_OP;
END $$;

CREATE TRIGGER trg_ict_incident_report_immutable
    BEFORE UPDATE OR DELETE ON ict_incident_report
    FOR EACH ROW EXECUTE FUNCTION ict_incident_report_immutable();

CREATE OR REPLACE FUNCTION ict_incident_write_once()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.awareness_at IS DISTINCT FROM OLD.awareness_at THEN
        RAISE EXCEPTION 'ict_incident.awareness_at is immutable';
    END IF;
    IF OLD.initial_reported_at IS NOT NULL AND NEW.initial_reported_at IS DISTINCT FROM OLD.initial_reported_at THEN
        RAISE EXCEPTION 'ict_incident.initial_reported_at is write-once';
    END IF;
    IF OLD.intermediate_reported_at IS NOT NULL AND NEW.intermediate_reported_at IS DISTINCT FROM OLD.intermediate_reported_at THEN
        RAISE EXCEPTION 'ict_incident.intermediate_reported_at is write-once';
    END IF;
    IF OLD.final_reported_at IS NOT NULL AND NEW.final_reported_at IS DISTINCT FROM OLD.final_reported_at THEN
        RAISE EXCEPTION 'ict_incident.final_reported_at is write-once';
    END IF;
    IF OLD.authority_ref IS NOT NULL AND NEW.authority_ref IS DISTINCT FROM OLD.authority_ref THEN
        RAISE EXCEPTION 'ict_incident.authority_ref is write-once';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_ict_incident_write_once
    BEFORE UPDATE ON ict_incident
    FOR EACH ROW EXECUTE FUNCTION ict_incident_write_once();

CREATE TABLE ict_incident_alert (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES ict_incident (id),
    breach_type VARCHAR(30) NOT NULL,
    alerted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (incident_id, breach_type)
);

-- Open-incident index must not hide an unreported MAJOR incident (monitoring is status-independent).
DROP INDEX IF EXISTS idx_ict_incident_open;
CREATE INDEX idx_ict_incident_open ON ict_incident (status) WHERE status <> 'CLOSED';
