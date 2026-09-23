-- A confidential (Zama fhEVM) forcedTransfer / confidentialBurn is an all-or-nothing FHE select:
-- when the holder's encrypted balance is below the ordered amount the contract moves 0 and the
-- transaction still succeeds. `status = SUCCESS` therefore does not mean the court-ordered
-- correction was executed. ConfidentialForcedOpVerifier decrypts the moved-amount handle from
-- the receipt (operator-viewer ACL) and records the verified outcome here.
--
-- NULL = not applicable, or verification still pending (see execution_outcome_attempts).
-- Existing SUCCESS rows for confidentialForcedTransfer / confidentialForceBurn stay NULL and are
-- picked up by the verifier on its next run, so historic corrections are verified as well.
ALTER TABLE blockchain_transaction
    ADD COLUMN execution_outcome            VARCHAR(40),
    ADD COLUMN execution_outcome_attempts   INT NOT NULL DEFAULT 0,
    ADD COLUMN execution_outcome_checked_at TIMESTAMPTZ;
