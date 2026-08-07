-- Baseline economic terms on every asset, regardless of token standard — distinct from
-- asset_bond_terms, which only exists for bond-standard assets and is entered separately.
-- Without these, statements, valuations, tax reporting, and corporate actions have no
-- amount or currency to work from for any non-bond asset (ledger finding: F-BLOCKER-1).
ALTER TABLE asset
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN issue_size NUMERIC(38, 8),
    ADD COLUMN denomination NUMERIC(38, 8),
    ADD COLUMN issue_date DATE,
    ADD COLUMN maturity_date DATE;
