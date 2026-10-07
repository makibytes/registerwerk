-- T6-12: reinstatement of CLOSED/DISSOLVED entities goes through EntityStatus.PENDING_REACTIVATION (4-eyes + legal
-- reference), never straight to ACTIVE. Widen the status CHECK; no rows are changed.
-- migration-safety: ack (CHECK constraint is re-created immediately below with a strictly wider value set; no data is dropped or rewritten)
ALTER TABLE legal_entity DROP CONSTRAINT chk_entity_status;
ALTER TABLE legal_entity ADD CONSTRAINT chk_entity_status
    CHECK (status IN ('PENDING_ONBOARDING','ACTIVE','SUSPENDED','DISSOLVED','CLOSED','PENDING_REACTIVATION'));
