-- T2-19: KYC expiry / rejection reaches the chain.
--
-- KycChainPropagationListener records one row per (legal entity, chain) when an entity's KYC
-- expires or is rejected, and drives it until every on-chain step is submitted:
--   * OrgRegistry.suspendOrg (via the existing fail-closed OrgRegistrationService.suspend path);
--   * ONCHAINID.removeClaim + ClaimIssuer.revokeClaimBySignature for the KYC (1) / AML (2) claims.
-- Every step is idempotent; the row only tracks whether the pass is complete and why not.
-- Reversal is never automatic: it goes through the existing 4-eyes re-approval / reinstate path.
CREATE TABLE kyc_chain_propagation (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id   UUID        NOT NULL REFERENCES chain_config(id),
    -- KYC_EXPIRED | KYC_REJECTED
    trigger_reason    VARCHAR(32) NOT NULL,
    -- PENDING | COMPLETED | FAILED | SUPERSEDED (KYC re-approved before completion)
    status            VARCHAR(24) NOT NULL,
    -- SUSPEND_SUBMITTED | ALREADY_SUSPENDED | NOT_REGISTERED | WAITING (registration/reinstate in flight)
    org_action        VARCHAR(32),
    unresolved_claims INT         NOT NULL DEFAULT 0,
    attempts          INT         NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT uq_kyc_chain_propagation_entity_chain UNIQUE (legal_entity_id, chain_config_id)
);

CREATE INDEX idx_kyc_chain_propagation_open
    ON kyc_chain_propagation (status)
    WHERE status IN ('PENDING', 'FAILED');

-- Issuer-level revocation (ClaimIssuer.revokeClaimBySignature). removeClaim alone is reversible:
-- the issuer still vouches for the signature, so a CLAIM key on the identity can re-add it.
ALTER TABLE onchain_claim ADD COLUMN issuer_revocation_tx_hash VARCHAR(80);
ALTER TABLE onchain_claim ADD COLUMN issuer_revoked_at TIMESTAMPTZ;

CREATE INDEX idx_onchain_claim_issuer_revocation_pending
    ON onchain_claim (issuer_revocation_tx_hash)
    WHERE issuer_revoked_at IS NULL AND issuer_revocation_tx_hash IS NOT NULL;
