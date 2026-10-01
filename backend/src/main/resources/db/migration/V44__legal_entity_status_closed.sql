-- Phase 6 fix: EntityStatus.CLOSED (set by CustomerOffboardingService.terminate) was rejected by the V1
-- CHECK constraint on legal_entity.status. Widen the constraint; no rows are changed.
-- migration-safety: ack (CHECK constraint is re-created immediately below with a strictly wider value set; no data is dropped or rewritten)
ALTER TABLE legal_entity DROP CONSTRAINT chk_entity_status;
ALTER TABLE legal_entity ADD CONSTRAINT chk_entity_status
    CHECK (status IN ('PENDING_ONBOARDING','ACTIVE','SUSPENDED','DISSOLVED','CLOSED'));
