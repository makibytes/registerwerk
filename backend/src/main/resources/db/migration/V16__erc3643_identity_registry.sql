-- Mirror of the on-chain IdentityRegistry: maps wallet addresses to ONCHAINID contracts per T-REX suite.
-- One row per (suite, wallet) — the on-chain IdentityRegistry stores the same mapping.
-- country_code (ISO-3166-1 numeric) is stored here for efficient country-restriction queries.

CREATE TABLE erc3643_identity_registry (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id             UUID        NOT NULL REFERENCES erc3643_suite(id),
    wallet_address       VARCHAR(66) NOT NULL,
    onchain_identity_id  UUID        NOT NULL REFERENCES onchain_identity(id),
    country_code         SMALLINT,               -- ISO-3166-1 numeric (0 = not set)
    registered_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by_tx     VARCHAR(66),            -- on-chain registerIdentity tx hash
    removed_at           TIMESTAMPTZ,            -- soft-delete; NULL = active
    UNIQUE (suite_id, wallet_address)
);

CREATE INDEX idx_erc3643_ir_suite    ON erc3643_identity_registry (suite_id);
CREATE INDEX idx_erc3643_ir_identity ON erc3643_identity_registry (onchain_identity_id);

-- Extend onchain_claim: store the signed claim payload so the registry can reconstruct
-- or verify claims off-chain and resubmit if needed.
ALTER TABLE onchain_claim
    ADD COLUMN claim_data      TEXT,   -- hex-encoded claim data bytes (ABI-encoded topic+issuer+data)
    ADD COLUMN claim_signature TEXT;   -- hex-encoded ECDSA signature (issuer signed the claim hash)

-- Extend erc3643_trusted_issuer: optional FK to our own legal entities acting as claim issuers.
-- NULL = external third-party KYC provider; non-NULL = this registry's own entity issuing claims.
ALTER TABLE erc3643_trusted_issuer
    ADD COLUMN legal_entity_id UUID REFERENCES legal_entity(id);

-- Extend erc3643_compliance_module: structured columns for the standard EwpgModularCompliance
-- parameters alongside the existing JSONB blob (kept for custom/future modules).
ALTER TABLE erc3643_compliance_module
    ADD COLUMN max_investors      INTEGER,       -- 0 or NULL = unlimited
    ADD COLUMN max_balance        NUMERIC(38,0), -- 0 or NULL = unlimited
    ADD COLUMN transfer_cooldown  INTEGER,       -- seconds; 0 or NULL = disabled
    ADD COLUMN blocked_countries  SMALLINT[];    -- ISO-3166-1 numeric codes array
