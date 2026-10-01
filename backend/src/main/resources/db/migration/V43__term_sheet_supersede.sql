-- Phase 6 K11 (6-34, parked T6-18): after issuance a term sheet is only replaced through an operator-approved
-- amendment (step-up + second approver). The replaced version stays in the table (never deleted) and points at
-- its successor; the public ISIN endpoint serves the deterministic current version, never a superseded one.
ALTER TABLE asset_document
    ADD COLUMN superseded_by UUID REFERENCES asset_document(id);

COMMENT ON COLUMN asset_document.superseded_by IS
    'Set when an operator-approved amendment replaced this document; the row is kept for the audit trail.';
