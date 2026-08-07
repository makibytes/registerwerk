-- Lets an operator annotate a FAILED/TIMEOUT blockchain_transaction row once they've handled it
-- (usually out-of-band, via the chain's own tooling) — TIMEOUT is currently terminal with no
-- automated resubmit path (see the global transaction console: no nonce/calldata is captured at
-- submission time, so a safe gas-bump resubmit isn't implementable without also touching the
-- shared signing path in EvmContractService, which this migration deliberately does not do).
ALTER TABLE blockchain_transaction
    ADD COLUMN ops_note TEXT,
    ADD COLUMN ops_reviewed_at TIMESTAMPTZ,
    ADD COLUMN ops_reviewed_by UUID;
