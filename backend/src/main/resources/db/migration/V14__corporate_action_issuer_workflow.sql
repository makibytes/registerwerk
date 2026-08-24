-- Corporate actions: issuer proposal/attestation workflow.
--
-- Adds the issuer's half of the new cross-party settlement control (issuer attests the
-- obligation/cash-leg is ready; the existing dual_control_approver_id/dual_control_approved_at
-- columns are repurposed, unchanged in name, to mean "operator confirmation" — see
-- CorporateAction's updated javadoc) and the PROPOSED/REJECTED states an issuer-initiated
-- DIVIDEND/SPLIT/CALL proposal moves through before an operator approves it onto the register.
--
-- Also retires the PLEDGE action type (never read or written by any code path — the real
-- pledge/collateral mechanism lives in the `lending` module) via a CHECK constraint; all-additive
-- otherwise, no data migration risk since these columns hold no data anywhere yet.

ALTER TABLE corporate_action ADD COLUMN issuer_attested_by UUID;
ALTER TABLE corporate_action ADD COLUMN issuer_attested_at TIMESTAMPTZ;
ALTER TABLE corporate_action ADD COLUMN issuer_attestation_ref TEXT;

ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_action_type CHECK (action_type IN (
    'COUPON', 'DIVIDEND', 'SPLIT', 'REVERSE_SPLIT', 'CONVERSION',
    'REDEMPTION', 'PARTIAL_REDEMPTION', 'CALL', 'CAPITAL_CALL', 'INTEREST_PAYMENT'
));

ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_status CHECK (status IN (
    'PROPOSED', 'ANNOUNCED', 'RECORD_DATE_SET', 'COMPUTED', 'AWAITING_SETTLEMENT',
    'SETTLED', 'CLOSED', 'CANCELLED', 'REJECTED'
));

-- The operator review queue's hot query: every PROPOSED row, across all assets.
CREATE INDEX idx_ca_proposed ON corporate_action (asset_id) WHERE status = 'PROPOSED';
