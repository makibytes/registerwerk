-- Phase 6 K6 (6-07, 6-15, 6-16, 6-17): KYC/CDD gate controls.
--
-- kyc_document.issue_date: captured at upload next to expires_at so expiry and "too old" are real checks
--   (expires_at had no writer outside the demo seeder; the checklist never saw an expired upload).
-- beneficial_owner: cease needs a reason (and optionally a register extract), a verification trail
--   (verification_document_id with the existing verified_by/verified_at) and the documented reason of a
--   senior-managing-official fallback (control_type SENIOR_MANAGING_OFFICIAL; parked decision T6-01).
-- edd_approval: enhanced-due-diligence approval of a confirmed PEP; four-eyes (approved_by + second_approver_id),
--   review_due is capped at 6 months by the service (parked decision T6-02). A confirmed PEP passes the
--   screening gate only while an unexpired row covers it.
-- kyc_approval_record: evidence snapshot of every entity-level KYC approval (checklist outcome, override note,
--   BO coverage, expiry, second approver) - the audit event carries the same payload, this row is queryable.
ALTER TABLE kyc_document ADD COLUMN issue_date DATE;

ALTER TABLE beneficial_owner ADD COLUMN verification_document_id UUID;
ALTER TABLE beneficial_owner ADD COLUMN fallback_reason TEXT;
ALTER TABLE beneficial_owner ADD COLUMN ceased_by UUID;
ALTER TABLE beneficial_owner ADD COLUMN cease_reason TEXT;
ALTER TABLE beneficial_owner ADD COLUMN cease_document_id UUID;

CREATE TABLE edd_approval (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    natural_person_id   UUID         NOT NULL REFERENCES natural_person(id),
    approved_by         UUID         NOT NULL,
    second_approver_id  UUID         NOT NULL,
    note                TEXT         NOT NULL,
    review_due          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_edd_two_people CHECK (approved_by <> second_approver_id)
);
CREATE INDEX idx_edd_approval_person ON edd_approval (natural_person_id, review_due DESC);

CREATE TABLE kyc_approval_record (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id           UUID         NOT NULL REFERENCES legal_entity(id),
    approved_by         UUID,
    second_approver_id  UUID,
    jurisdiction        VARCHAR(20)  NOT NULL,
    expiry_date         DATE         NOT NULL,
    checklist_compliant BOOLEAN      NOT NULL,
    override_note       TEXT,
    identified_pct      NUMERIC(6,2) NOT NULL DEFAULT 0,
    smo_fallback        BOOLEAN      NOT NULL DEFAULT FALSE,
    evidence_snapshot   TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_kyc_approval_record_entity ON kyc_approval_record (entity_id, created_at DESC);
