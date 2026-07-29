-- Regulatory reporting is an opt-in, non-production draft generator. Historic status labels
-- described local gateway outcomes too strongly. Normalize them to transport-only meanings;
-- none of these states proves official-schema validity, filing, acceptance, or legal compliance.
ALTER TABLE regreport_submission ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE regreport_submission ALTER COLUMN status SET DEFAULT 'DRAFT_UNVALIDATED';
ALTER TABLE regreport_submission ADD COLUMN IF NOT EXISTS transported_at TIMESTAMPTZ;
ALTER TABLE regreport_submission ADD COLUMN IF NOT EXISTS transport_ref TEXT;
ALTER TABLE regreport_submission ADD COLUMN IF NOT EXISTS transport_error TEXT;

-- Preserve the old adapter evidence while correcting its meaning. Legacy columns remain so
-- rollback does not destroy evidence; new code never writes them.
UPDATE regreport_submission
SET transported_at = COALESCE(transported_at, submitted_at),
    transport_ref = COALESCE(transport_ref, submission_ref),
    transport_error = COALESCE(transport_error, rejection_reason);

UPDATE regreport_submission
SET status = CASE status
    WHEN 'GENERATING'  THEN 'DRAFT_UNVALIDATED'
    WHEN 'READY'       THEN 'DRAFT_UNVALIDATED'
    WHEN 'SUBMITTED'   THEN 'TRANSPORTED_UNVERIFIED'
    WHEN 'PENDING_ACK' THEN 'TRANSPORTED_UNVERIFIED'
    WHEN 'ACCEPTED'    THEN 'TRANSPORTED_UNVERIFIED'
    WHEN 'ACKNOWLEDGED' THEN 'TRANSPORTED_UNVERIFIED'
    WHEN 'REJECTED'    THEN 'TRANSPORT_FAILED'
    ELSE status
END;

-- Replace the legacy authority-outcome predicate with transport-only states.
DROP INDEX IF EXISTS idx_regreport_status;
CREATE INDEX idx_regreport_status ON regreport_submission (status)
WHERE status IN ('DRAFT_UNVALIDATED', 'NOT_TRANSPORTED', 'TRANSPORT_FAILED',
                 'TRANSPORTED_UNVERIFIED');
