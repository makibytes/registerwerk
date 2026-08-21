-- ClaimIssuanceService.issueClaim/revokeClaim (via Erc3643DeploymentService.issueKycClaim/
-- revokeKycClaim) submitted a real EVM transaction (or, worse, silently skipped it entirely when
-- the target identity was still 0x-PENDING-...) and then immediately persisted/mutated
-- onchain_claim as if the on-chain call had already succeeded — before any receipt existed, and
-- in the PENDING-identity case before anything was ever submitted at all. This mirrors the exact
-- "optimistic write with no confirmation-gated moment" gap V8/V10 closed for
-- erc3643_identity_registry / the vault-admin services; see Erc3643ClaimConfirmationListener for
-- the polling/confirmation side of this fix.

ALTER TABLE onchain_claim ADD COLUMN chain_config_id UUID REFERENCES chain_config(id);
ALTER TABLE onchain_claim ADD COLUMN block_number BIGINT;
ALTER TABLE onchain_claim ADD COLUMN confirmed BOOLEAN NOT NULL DEFAULT false;

-- revocation_tx_hash IS NOT NULL is itself the "revocation pending" signal — revoked_at is only
-- ever set once Erc3643ClaimConfirmationListener confirms this tx, so there is no separate
-- "revocation confirmed" boolean needed (unlike issuance, nothing else on this row is concurrently
-- pending once a revocation is in flight: confirmed is already true by then).
ALTER TABLE onchain_claim ADD COLUMN revocation_tx_hash VARCHAR(80);

-- migration-safety: ack (backfill only, no DROP/TRUNCATE) — claims issued before this migration
-- were never tracked to confirmation at all (issueKycClaim used a blocking send() and discarded
-- the receipt); getActiveClaims must not suddenly stop treating them as active just because the
-- new confirmed column defaults to false. Grandfather them in; only claims issued from here on go
-- through the new submit-then-confirm gate.
UPDATE onchain_claim SET confirmed = true;

CREATE INDEX idx_onchain_claim_pending_issuance
    ON onchain_claim (tx_hash)
    WHERE confirmed = false AND tx_hash IS NOT NULL;

CREATE INDEX idx_onchain_claim_pending_revocation
    ON onchain_claim (revocation_tx_hash)
    WHERE revoked_at IS NULL AND revocation_tx_hash IS NOT NULL;
