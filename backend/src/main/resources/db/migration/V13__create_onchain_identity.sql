-- ONCHAINID: one identity smart contract per legal entity per chain.
-- The ONCHAINID stores cryptographic keys and KYC claims on-chain.
-- Required by ERC-3643 (T-REX) for all investors and issuers.

CREATE TABLE onchain_identity (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id  UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    identity_address VARCHAR(66) NOT NULL,
    deployed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deployed_by_tx   VARCHAR(66),
    UNIQUE (legal_entity_id, chain_config_id)
);

CREATE INDEX idx_onchain_identity_entity ON onchain_identity (legal_entity_id);
CREATE INDEX idx_onchain_identity_chain  ON onchain_identity (chain_config_id);

-- Claims issued to an ONCHAINID (off-chain mirror of on-chain claims)
CREATE TABLE onchain_claim (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    onchain_identity_id UUID     NOT NULL REFERENCES onchain_identity(id),
    topic            BIGINT      NOT NULL,   -- claim topic (e.g. 1 = KYC, 2 = AML)
    topic_label      VARCHAR(50),            -- human-readable label
    issuer_address   VARCHAR(66) NOT NULL,   -- trusted issuer who signed this claim
    claim_id         VARCHAR(66),            -- on-chain claim ID
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    tx_hash          VARCHAR(66)
);

CREATE INDEX idx_claim_identity ON onchain_claim (onchain_identity_id);
CREATE INDEX idx_claim_topic    ON onchain_claim (topic);
