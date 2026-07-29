-- Finding #15, Phase 10: CantonBondService.createZeroBond hardcoded issuePrice to 1.0 (100% of
-- face value) with no way to record a genuine discount price — the entire point of a
-- zero-coupon bond. issue_price is a fraction of face value (e.g. 0.80 for 80%); defaults to 1.0
-- for existing rows (fixed/floating bonds don't use this field at all, but it's harmless there).
ALTER TABLE asset_bond_terms
    ADD COLUMN issue_price NUMERIC(10, 8) NOT NULL DEFAULT 1.0;
