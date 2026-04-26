-- ─── Jurisdiction-aware KYC ───────────────────────────────────────────────────

-- 1. Jurisdiction selection per security (asset)
ALTER TABLE asset ADD COLUMN jurisdiction VARCHAR(20);

-- 2. Optional jurisdiction tag on KYC documents (null = universal, counts for all jurisdictions)
ALTER TABLE kyc_document ADD COLUMN jurisdiction VARCHAR(20);

-- 3. Remove the narrow document_type check — validation now enforced in the application layer,
--    allowing the DocumentType enum to grow without a schema change per value.
ALTER TABLE kyc_document DROP CONSTRAINT IF EXISTS kyc_document_type_chk;

-- 4. Per-entity per-jurisdiction KYC approval tracking.
--    Replaces the single entity-level kycStatus for jurisdiction-specific workflows.
CREATE TABLE kyc_jurisdiction_approval (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID         NOT NULL REFERENCES legal_entity(id),
    jurisdiction     VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    approved_by      UUID,
    approved_at      TIMESTAMPTZ,
    expires_at       DATE,
    rejection_reason TEXT,
    override_note    TEXT,        -- populated when operator approved despite a compliance gap
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, jurisdiction)
);

CREATE INDEX idx_kyc_jur_entity ON kyc_jurisdiction_approval(entity_id);
